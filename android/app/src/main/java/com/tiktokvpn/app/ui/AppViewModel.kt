package com.tiktokvpn.app.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiktokvpn.app.core.ConnectionRepository
import com.tiktokvpn.app.core.ConnectionState
import com.tiktokvpn.app.core.CoreBridge
import com.tiktokvpn.app.core.CoreOperations
import com.tiktokvpn.app.core.CoreOperationException
import com.tiktokvpn.app.core.TunnelPhase
import com.tiktokvpn.app.core.errorMessage
import com.tiktokvpn.app.data.AccountStore
import com.tiktokvpn.app.data.EndpointCache
import com.tiktokvpn.app.data.EndpointRecord
import com.tiktokvpn.app.data.AppSettings
import com.tiktokvpn.app.data.SettingsStore
import com.tiktokvpn.app.service.TikTokVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountStore: AccountStore,
    private val settingsStore: SettingsStore,
    private val endpointCache: EndpointCache,
    private val operations: CoreOperations,
    private val repository: ConnectionRepository,
    private val coreBridge: CoreBridge
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = repository.state

    private val mutableSettings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val mutableHasAccount = MutableStateFlow<Boolean?>(null)
    val hasAccount: StateFlow<Boolean?> = mutableHasAccount.asStateFlow()

    private val mutableAccountBusy = MutableStateFlow(false)
    val accountBusy: StateFlow<Boolean> = mutableAccountBusy.asStateFlow()

    private val mutableOptimizing = MutableStateFlow(false)
    val optimizing: StateFlow<Boolean> = mutableOptimizing.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableVpnConsent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val vpnConsent: SharedFlow<Intent> = mutableVpnConsent.asSharedFlow()

    private var connectAfterConsent = false

    val endpoint: StateFlow<EndpointRecord?> = endpointCache.record
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val coreAvailable: Boolean = runCatching { coreBridge.available() }.getOrDefault(false)

    /** Reported to the About screen; "unavailable" when the core is missing. */
    val coreVersion: String = if (coreAvailable) {
        runCatching { coreBridge.coreVersion() }.getOrDefault("unavailable")
    } else {
        "unavailable"
    }

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { value ->
                mutableSettings.value = value
                mutableReady.value = true
            }
        }
        viewModelScope.launch { mutableHasAccount.value = accountStore.hasAccount() }
    }

    // region onboarding

    fun createAccount() {
        if (mutableAccountBusy.value) return
        if (!coreAvailable) {
            mutableMessage.value = errorMessage(context, "core_missing")
            return
        }
        viewModelScope.launch {
            mutableAccountBusy.value = true
            mutableMessage.value = null
            val current = mutableSettings.value
            operations.register(current.relayEnabled, current.relayUrl)
                .onSuccess {
                    mutableHasAccount.value = true
                    settingsStore.setOnboardingDone(true)
                    mutableSettings.value = current.copy(onboardingDone = true)
                }
                .onFailure { mutableMessage.value = messageFor(it) }
            mutableAccountBusy.value = false
        }
    }

    fun recreateAccount() {
        if (mutableAccountBusy.value || connection.value.phase.busy) return
        createAccount()
    }

    // endregion

    // region connection

    fun toggleConnection() {
        when (connection.value.phase) {
            TunnelPhase.Idle, TunnelPhase.Failed -> requestConnection()
            else -> disconnect()
        }
    }

    /**
     * Asks for the system's consent first - routing the device's traffic is
     * something the user has to agree to once per install.
     */
    private fun requestConnection() {
        if (!coreAvailable) {
            mutableMessage.value = errorMessage(context, "core_missing")
            return
        }
        if (mutableOptimizing.value) return
        val consent = VpnService.prepare(context)
        if (consent != null) {
            connectAfterConsent = true
            mutableVpnConsent.tryEmit(consent)
            return
        }
        startTunnel()
    }

    fun onVpnConsent(granted: Boolean) {
        val pending = connectAfterConsent
        connectAfterConsent = false
        if (granted && pending) startTunnel()
    }

    private fun startTunnel() {
        if (connection.value.phase.busy) return
        repository.set(ConnectionState(phase = TunnelPhase.Preparing))
        ContextCompat.startForegroundService(context, TikTokVpnService.connectIntent(context))
    }

    fun disconnect() {
        runCatching {
            context.startService(TikTokVpnService.disconnectIntent(context))
        }
    }

    fun optimize() {
        if (connection.value.phase.busy || mutableOptimizing.value) return
        if (!coreAvailable) {
            mutableMessage.value = errorMessage(context, "core_missing")
            return
        }
        viewModelScope.launch {
            mutableOptimizing.value = true
            mutableMessage.value = null
            repository.update {
                it.copy(
                    phase = TunnelPhase.Optimizing,
                    detail = "",
                    progress = 0f,
                    errorCode = null,
                    errorMessage = null
                )
            }
            val result = operations.optimize()
            // Only roll the state back when it is still ours to touch: a
            // connection may have taken over while the search was running.
            repository.update { current ->
                if (current.phase == TunnelPhase.Optimizing) ConnectionState() else current
            }
            mutableOptimizing.value = false
            result.onFailure { mutableMessage.value = messageFor(it) }
        }
    }

    // endregion

    // region settings

    fun setWarpInWarp(enabled: Boolean) {
        if (connection.value.phase.tunnelUp) return
        mutableSettings.value = mutableSettings.value.copy(warpInWarp = enabled)
        viewModelScope.launch { settingsStore.setWarpInWarp(enabled) }
    }

    fun setRelayEnabled(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(relayEnabled = enabled)
        viewModelScope.launch { settingsStore.setRelayEnabled(enabled) }
    }

    fun setRelayUrl(value: String) {
        mutableSettings.value = mutableSettings.value.copy(relayUrl = value)
        viewModelScope.launch { settingsStore.setRelayUrl(value) }
    }

    fun resetEndpoint() {
        if (connection.value.phase.tunnelUp) return
        viewModelScope.launch {
            endpointCache.clear()
            repository.update { if (it.phase == TunnelPhase.Idle) ConnectionState() else it }
            mutableMessage.value = context.getString(com.tiktokvpn.app.R.string.settings_reset_endpoint_done)
        }
    }

    fun clearAllData() {
        if (connection.value.phase.busy) return
        viewModelScope.launch {
            accountStore.clear()
            endpointCache.clear()
            settingsStore.clear()
            repository.reset()
            mutableSettings.value = AppSettings()
            mutableHasAccount.value = false
            mutableMessage.value = null
        }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    // endregion

    private fun messageFor(error: Throwable): String {
        val code = (error as? CoreOperationException)?.code
            ?: if (error is ClassNotFoundException || error is NoSuchMethodException) "core_missing" else null
        return errorMessage(context, code)
    }
}
