package com.tiktokvpn.app.core

import com.tiktokvpn.app.data.AccountStore
import com.tiktokvpn.app.data.EndpointCache
import com.tiktokvpn.app.data.EndpointRecord
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
     * Searches for a route and remembers the first endpoint that answered and
     * held, together with the parameters it was found under.
     */
    suspend fun optimize(): Result<EndpointRecord> = guarded {
        val accountJson = accountStore.read()
            ?: throw CoreOperationException("account_required")
        var capturedReport: JSONObject? = null
        var capturedFailure: CoreEvent.Error? = null
        run(Requests.scan(accountJson)) { event ->
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
        val record = Requests.bestEndpoint(report) ?: throw CoreOperationException("no_route")
        endpointCache.save(record)
        repository.update { it.copy(endpoint = record.endpoint) }
        record
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
