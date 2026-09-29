package com.tiktokvpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tiktokvpn.app.MainActivity
import com.tiktokvpn.app.R
import com.tiktokvpn.app.core.ConnectionRepository
import com.tiktokvpn.app.core.ConnectionState
import com.tiktokvpn.app.core.CoreBridge
import com.tiktokvpn.app.core.CoreEvent
import com.tiktokvpn.app.core.CoreOperations
import com.tiktokvpn.app.core.Requests
import com.tiktokvpn.app.core.TunnelDefaults
import com.tiktokvpn.app.core.TunnelPhase
import com.tiktokvpn.app.core.errorMessage
import com.tiktokvpn.app.core.statusText
import com.tiktokvpn.app.data.AccountStore
import com.tiktokvpn.app.data.EndpointCache
import com.tiktokvpn.app.data.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Owns the whole connection: it decides when an interface exists, when the
 * core may take it over and when everything comes down again.
 *
 * The engine accepts one operation at a time, so a live tunnel also holds off
 * route searches - which is exactly what we want, since a search only makes
 * sense while the interface is down.
 */
@AndroidEntryPoint
class TikTokVpnService : VpnService() {

    @Inject lateinit var bridge: CoreBridge
    @Inject lateinit var operations: CoreOperations
    @Inject lateinit var accountStore: AccountStore
    @Inject lateinit var endpointCache: EndpointCache
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var repository: ConnectionRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var tunnel: ParcelFileDescriptor? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch { repository.state.collect { state -> updateNotification(state) } }
        // Every socket the core opens is passed through here so its packets
        // leave on the physical network instead of looping through the
        // interface they are meant to carry.
        bridge.setSocketProtector { fd ->
            runCatching { protect(fd) }
                .onSuccess { if (!it) trace("protect refused fd=$fd") }
                .getOrDefault(false)
        }
        trace("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trace("start action=${intent?.action ?: "none"}")
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            else -> {
                // Restarted without instructions: honour the foreground start
                // contract first, then get out of the way.
                startAsForeground(repository.state.value)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        stopping.set(true)
        bridge.stop()
        if (activeJob?.isActive != true) finish()
    }

    override fun onDestroy() {
        stopping.set(true)
        bridge.setSocketProtector(null)
        bridge.cancel()
        closeTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        if (activeJob?.isActive == true) return
        stopping.set(false)
        repository.set(ConnectionState(phase = TunnelPhase.Preparing))
        startAsForeground(repository.state.value)
        activeJob = serviceScope.launch { runTunnel() }
    }

    private fun disconnect() {
        if (activeJob?.isActive != true) {
            finish()
            return
        }
        stopping.set(true)
        repository.update { it.copy(phase = TunnelPhase.Disconnecting) }
        // The core unwinds on its own; the job closes the interface on return.
        bridge.stop()
    }

    private suspend fun runTunnel() {
        try {
            var retried = false
            while (true) {
                if (stopping.get() || !prepare()) return
                val failure = connectOnce()
                if (failure == null) {
                    trace("tunnel ended normally")
                    return
                }
                trace("attempt failed: $failure")
                if (stopping.get()) return
                if (failure == "endpoint_unreachable" && !retried) {
                    // The remembered route went quiet: forget it, look for a new one.
                    retried = true
                    trace("route went quiet, searching for a new one")
                    endpointCache.clear()
                    continue
                }
                fail(failure)
                return
            }
        } finally {
            finish()
        }
    }

    /**
     * Makes sure an account and a known-good route exist before the interface
     * comes up. Returns false when the attempt should be abandoned.
     */
    private suspend fun prepare(): Boolean {
        repository.update {
            it.copy(
                phase = TunnelPhase.Preparing,
                detail = "",
                progress = 0f,
                errorCode = null,
                errorMessage = null
            )
        }
        val settings = settingsStore.current()
        var accountJson = accountStore.read()
        if (accountJson == null) {
            trace("no account on disk")
            fail("account_required")
            return false
        }
        if (settings.warpInWarp && !hasNestedAccount(accountJson)) {
            // The nested mode needs an account carrying a second tunnel, and
            // only a fresh registration mints one.
            trace("nested mode needs a fresh account, registering")
            val created = operations.register(settings.relayEnabled, settings.relayUrl)
            val fresh = created.getOrElse { error ->
                trace("register failed: ${codeOf(error)}")
                fail(codeOf(error))
                return false
            }
            accountJson = fresh
        }

        val cached = endpointCache.read()
        if (cached == null) {
            trace("no route remembered, searching")
            repository.update { it.copy(phase = TunnelPhase.Optimizing, progress = 0f) }
            val optimized = operations.optimize()
            optimized.getOrElse { error ->
                trace("search failed: ${codeOf(error)}")
                fail(codeOf(error))
                return false
            }
        } else {
            trace("using remembered route ${cached.endpoint}")
        }
        return !stopping.get()
    }

    /**
     * Opens the tunnel and blocks until the core is done with it. Returns the
     * failure code, or null when the tunnel ended normally.
     */
    private suspend fun connectOnce(): String? {
        val record = endpointCache.read()
        if (record == null) return "no_route"
        val accountJson = accountStore.read()
        if (accountJson == null) return "account_required"

        val warpInWarp = settingsStore.current().warpInWarp
        val mtu = if (warpInWarp) TunnelDefaults.MTU_NESTED else TunnelDefaults.MTU
        val descriptor = openTunnel(accountJson, mtu)
        if (descriptor == null) {
            trace("interface could not be established")
            return "tunnel_unavailable"
        }
        tunnel = descriptor
        trace("interface up route=${record.endpoint} mtu=$mtu nested=$warpInWarp fd=${descriptor.fd}")

        repository.update {
            it.copy(
                phase = TunnelPhase.Connecting,
                detail = "",
                progress = 0f,
                errorCode = null,
                errorMessage = null,
                endpoint = record.endpoint,
                rxBytes = 0L,
                txBytes = 0L
            )
        }

        val outcome = ConnectOutcome()
        val request = Requests.connect(accountJson, descriptor.fd, mtu, record, warpInWarp)
        try {
            val failure = operations.run(request) { event ->
                when (event) {
                    is CoreEvent.Handshaking -> {
                        trace("handshaking ${event.endpoint}")
                        repository.update {
                            it.copy(phase = TunnelPhase.Connecting, detail = event.endpoint)
                        }
                    }
                    is CoreEvent.Connected -> {
                        trace("connected ${event.endpoint}")
                        repository.update {
                            it.copy(
                                phase = TunnelPhase.Connected,
                                endpoint = event.endpoint.ifBlank { record.endpoint },
                                progress = 0f
                            )
                        }
                    }
                    is CoreEvent.Stats -> repository.update {
                        it.copy(rxBytes = event.rxBytes, txBytes = event.txBytes)
                    }
                    is CoreEvent.Error -> {
                        trace("core error ${event.code}")
                        outcome.code = event.code
                        outcome.detail = event.message
                    }
                    else -> Unit
                }
            }
            val code = outcome.code
            if (code != null) return code
            if (failure != null) return codeOf(failure)
            return null
        } finally {
            // The core works on its own duplicate of the descriptor, so closing
            // ours here always brings the interface down - however the tunnel
            // ended, and even if the core never got to start at all.
            closeTunnel()
        }
    }

    private fun openTunnel(accountJson: String, mtu: Int): ParcelFileDescriptor? {
        val address = runCatching { JSONObject(accountJson).optString("ipv4") }
            .getOrDefault("")
            .substringBefore('/')
            .ifBlank { TunnelDefaults.FALLBACK_ADDRESS }
        return Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)
            .addAddress(address, TunnelDefaults.FALLBACK_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TunnelDefaults.DNS)
            .establish()
    }

    private fun closeTunnel() {
        val descriptor = tunnel ?: return
        tunnel = null
        runCatching { descriptor.close() }
    }

    private fun hasNestedAccount(accountJson: String): Boolean = runCatching {
        JSONObject(accountJson).optJSONObject("outer") != null
    }.getOrDefault(false)

    private fun codeOf(error: Throwable): String =
        (error as? com.tiktokvpn.app.core.CoreOperationException)?.code ?: "operation_failed"

    private fun fail(code: String) {
        trace("giving up: $code")
        repository.update {
            it.copy(
                phase = TunnelPhase.Failed,
                detail = "",
                progress = 0f,
                errorCode = code,
                errorMessage = errorMessage(this, code)
            )
        }
    }

    private fun finish() {
        closeTunnel()
        foregroundStarted = false
        activeJob = null
        repository.update { current ->
            // A failure stays on screen until the next attempt clears it.
            if (current.phase == TunnelPhase.Failed) current else ConnectionState()
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startAsForeground(state: ConnectionState) {
        val notification = notification(state)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
        foregroundStarted = true
    }

    private fun updateNotification(state: ConnectionState) {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: ConnectionState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, TikTokVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText(this, state))
            .setOnlyAlertOnce(true)
            .setOngoing(state.phase != TunnelPhase.Failed)
            .setContentIntent(openApp)
            .addAction(
                0,
                getString(R.string.notification_action_disconnect),
                disconnect
            )
        if (state.phase == TunnelPhase.Optimizing) {
            val percent = (state.progress * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, state.progress <= 0f)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Local-only breadcrumb; logcat never leaves the device. */
    private fun trace(message: String) {
        Log.i(TAG, message)
    }

    /** What the core reported about a connect attempt, read once it returns. */
    private class ConnectOutcome {
        @Volatile var code: String? = null
        @Volatile var detail: String? = null
    }

    companion object {
        const val ACTION_CONNECT = "com.tiktokvpn.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.tiktokvpn.app.action.DISCONNECT"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "TikTokVPN"

        fun connectIntent(context: Context): Intent =
            Intent(context, TikTokVpnService::class.java).setAction(ACTION_CONNECT)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, TikTokVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
