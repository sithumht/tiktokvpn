package com.tiktokvpn.app.core

/**
 * The neutral vocabulary shared by the service and the UI. Core phases arrive
 * as raw strings from the engine; they are mapped to these labels right before
 * they are shown, so engine wording never reaches the screen.
 */
enum class TunnelPhase {
    Idle,
    Preparing,
    Optimizing,
    Connecting,
    Connected,
    Disconnecting,
    Failed;

    val busy: Boolean
        get() = this != Idle && this != Failed

    val tunnelUp: Boolean
        get() = this == Connected || this == Connecting || this == Disconnecting
}

data class ConnectionState(
    val phase: TunnelPhase = TunnelPhase.Idle,
    /** Raw phase reported by the core. Displayed only through [phaseLabel]. */
    val detail: String = "",
    val endpoint: String = "",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    /** 0f..1f while a route is being searched. */
    val progress: Float = 0f,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val connected: Boolean
        get() = phase == TunnelPhase.Connected
}
