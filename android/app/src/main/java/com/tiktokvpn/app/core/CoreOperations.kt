package com.tiktokvpn.app.core

import com.tiktokvpn.app.data.AccountStore
import com.tiktokvpn.app.data.EndpointCache
import com.tiktokvpn.app.data.EndpointRecord
import com.tiktokvpn.app.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the operations that do not need a tunnel: setting an account up and
 * looking for a working route. The core accepts one operation at a time, so
 * everything here is a single blocking call on the IO dispatcher.
 */
@Singleton
class CoreOperations @Inject constructor(
    private val bridge: CoreBridge,
    private val accountStore: AccountStore,
    private val endpointCache: EndpointCache,
    private val settingsStore: SettingsStore,
    private val repository: ConnectionRepository
) {

    /** Creates an account and seals it on the device. */
    suspend fun register(relayEnabled: Boolean, relayUrl: String): Result<String> = guarded {
        var capturedAccount: String? = null
        var capturedFailure: CoreEvent.Error? = null
        run(Requests.register(relayEnabled, relayUrl)) { event ->
            when (event) {
                is CoreEvent.Completed -> capturedAccount =
                    event.payload?.optString("rawJson").orEmpty().ifBlank { null }
                is CoreEvent.Error -> capturedFailure = event
                else -> Unit
            }
        }
        val account = capturedAccount
        if (account == null) {
            val failure = capturedFailure
            throw CoreOperationException(
                failure?.code ?: "operation_failed",
                failure?.message
            )
        }
        accountStore.write(account)
        account
    }

    /**
     * Searches for a route and remembers it, together with the parameters it
     * was found under.
     *
     * The nested mode searches twice. The first pass finds the endpoint that
     * will carry the chain - it decides where traffic leaves, so it is picked
     * purely on quality. The second pass then runs *inside* that tunnel, which
     * means every endpoint it reports already exits through the first one's
     * region; that inner endpoint is the one the app actually opens a tunnel
     * to. Searching it any other way would report endpoints reachable straight
     * from this network, which is not where the chain would end.
     */
    suspend fun optimize(): Result<EndpointRecord> = guarded {
        val accountJson = accountStore.read()
            ?: throw CoreOperationException("account_required")
        val nested = settingsStore.current().warpInWarp

        val outer = scanOnce(accountJson, through = "")
        val record = if (!nested) {
            outer
        } else {
            val inner = scanOnce(accountJson, through = outer.endpoint, chained = true)
            // The chain was only ever proven end to end under the second
            // search's profile, so it keeps its own parameters where it has
            // any, falling back to the outer ones when it does not.
            if (inner.awgI1.isBlank()) inner.copy(awgI1 = outer.awgI1) else inner
        }

        endpointCache.save(record)
        repository.update { it.copy(endpoint = record.label) }
        record
    }

    /** One search pass: reports progress into the connection state and hands back the best result. */
    private suspend fun scanOnce(
        accountJson: String,
        through: String,
        chained: Boolean = false
    ): EndpointRecord {
        if (chained) repository.update { it.copy(detail = "Through", progress = 0f) }
        var capturedReport: JSONObject? = null
        var capturedFailure: CoreEvent.Error? = null
        run(Requests.scan(accountJson, through)) { event ->
            when (event) {
                is CoreEvent.Progress -> repository.update {
                    it.copy(
                        detail = event.phase,
                        progress = if (event.total > 0) {
                            event.completed.toFloat() / event.total
                        } else {
                            0f
                        }
                    )
                }
                is CoreEvent.Completed -> capturedReport = event.payload
                is CoreEvent.Error -> capturedFailure = event
                else -> Unit
            }
        }
        val report = capturedReport
        if (report == null) {
            val failure = capturedFailure
            throw CoreOperationException(
                failure?.code ?: "operation_failed",
                failure?.message
            )
        }
        return Requests.bestEndpoint(report, outerEndpoint = through)
            ?: throw CoreOperationException("no_route")
    }

    /**
     * Hands a prepared request to the core and reports how it ended: null when
     * the operation ran to completion, the failure otherwise.
     */
    suspend fun run(requestJson: String, onEvent: (CoreEvent) -> Unit): Throwable? =
        withContext(Dispatchers.IO) {
            try {
                bridge.start(requestJson) { raw -> parseCoreEvent(raw)?.let(onEvent) }
                null
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                error
            }
        }

    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
