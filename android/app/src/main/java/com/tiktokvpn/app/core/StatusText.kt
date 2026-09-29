package com.tiktokvpn.app.core

import android.content.Context
import com.tiktokvpn.app.R

/** Short status line for a connection state. */
fun statusText(context: Context, state: ConnectionState): String = when (state.phase) {
    TunnelPhase.Optimizing -> detailLabel(context, state.detail)
    TunnelPhase.Failed -> context.getString(R.string.status_failed)
    else -> context.getString(state.phase.labelRes)
}

/** Notification/secondary line: the phase only, never the raw core text. */
fun phaseText(context: Context, phase: TunnelPhase): String =
    context.getString(phase.labelRes)

private val TunnelPhase.labelRes: Int
    get() = when (this) {
        TunnelPhase.Idle -> R.string.status_idle
        TunnelPhase.Preparing -> R.string.status_preparing
        TunnelPhase.Optimizing -> R.string.status_optimizing
        TunnelPhase.Connecting -> R.string.status_connecting
        TunnelPhase.Connected -> R.string.status_connected
        TunnelPhase.Disconnecting -> R.string.status_disconnecting
        TunnelPhase.Failed -> R.string.status_failed
    }

/**
 * Maps a raw core phase onto a neutral label. Anything unrecognised falls back
 * to a generic one: the core reports process detail (probe stages, tunnel
 * internals) that must not surface in the interface.
 */
fun detailLabel(context: Context, detail: String): String = when {
    detail.startsWith("Phase 1") -> context.getString(R.string.phase_port_discovery)
    detail.startsWith("Phase 2") -> context.getString(R.string.phase_tunnel_verification)
    detail.startsWith("Speedtest") -> context.getString(R.string.phase_speed_test)
    detail == "registration" -> context.getString(R.string.phase_registration)
    detail == "handshake" -> context.getString(R.string.phase_handshake)
    detail == "listening" -> context.getString(R.string.phase_listening)
    detail == "Through" -> context.getString(R.string.phase_outer_tunnel)
    detail == "Port" -> context.getString(R.string.phase_port_selected)
    else -> context.getString(R.string.phase_finding_route)
}

/** Maps a core error code onto a friendly message. Unknown codes stay generic. */
fun errorMessage(context: Context, code: String?): String = when (code) {
    "core_missing" -> context.getString(R.string.error_core_missing)
    "account_required", "invalid_account" -> context.getString(R.string.error_account_required)
    "no_route" -> context.getString(R.string.error_no_route)
    "endpoint_unreachable", "outer_tunnel_failed" -> context.getString(R.string.error_unreachable)
    "network_lost" -> context.getString(R.string.error_network_lost)
    "operation_active" -> context.getString(R.string.error_engine_busy)
    else -> context.getString(R.string.error_generic)
}
