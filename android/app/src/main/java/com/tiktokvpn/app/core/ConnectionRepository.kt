package com.tiktokvpn.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single picture of the tunnel the whole app reads. The service is the
 * only writer that matters while it runs; the view model seeds it when it
 * kicks a connection off so the interface reacts before the service is up.
 */
@Singleton
class ConnectionRepository @Inject constructor() {
    private val mutableState = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    fun set(state: ConnectionState) {
        mutableState.value = state
    }

    fun update(transform: (ConnectionState) -> ConnectionState) {
        mutableState.value = transform(mutableState.value)
    }

    fun reset() {
        mutableState.value = ConnectionState()
    }
}
