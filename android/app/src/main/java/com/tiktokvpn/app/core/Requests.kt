package com.tiktokvpn.app.core

import com.tiktokvpn.app.data.EndpointRecord
import org.json.JSONObject

/**
 * The exact JSON handed to the core. Building it in one place keeps the
 * contract with the engine explicit - and testable - instead of scattered
 * across the screens that trigger each operation.
 */
object Requests {

    fun register(relayEnabled: Boolean, relayUrl: String): String = base(
        operation = "register",
        accountJson = null,
        payload = JSONObject()
            .put("proxy", "")
            .put("relay", if (relayEnabled && relayUrl.isNotBlank()) relayUrl else "none")
            .put("fresh", true)
            .put("timeoutSec", 2)
            .put("ipv6", false)
    )

    /**
     * A short, deterministic search: it looks for an endpoint that survives a
     * handful of tunnel pings under a fixed obfuscation profile, because the
     * connection will be opened with that same profile afterwards.
     */
    fun scan(accountJson: String): String = base(
        operation = "scan",
        accountJson = accountJson,
        payload = JSONObject()
            .put("protocol", TunnelDefaults.PROTOCOL)
            .put("ipv6", false)
            .put("port", 0)
            .put("timeoutSec", TunnelDefaults.SCAN_TIMEOUT_SEC)
            .put("jobs", TunnelDefaults.SCAN_JOBS)
            .put("samplePerSubnet", TunnelDefaults.SCAN_SAMPLE)
            .put("full", false)
            .put("tunnelPingCount", TunnelDefaults.SCAN_PINGS)
            .put("awgJunkCount", TunnelDefaults.JUNK_COUNT)
            .put("awgJunkMin", TunnelDefaults.JUNK_MIN)
            .put("awgJunkMax", TunnelDefaults.JUNK_MAX)
            .put("awgAutoI1", true)
            .put("speedTest", false)
            .put("bestBy", "ping")
    )

    /**
     * Opens the tunnel on an interface the app already brought up. In the
     * nested mode the outer tunnel wraps the endpoint, and the inner one runs
     * to the very same endpoint inside it.
     */
    fun connect(
        accountJson: String,
        tunFd: Int,
        mtu: Int,
        record: EndpointRecord,
        warpInWarp: Boolean
    ): String = base(
        operation = "connect",
        accountJson = accountJson,
        payload = JSONObject()
            .put("tunFd", tunFd)
            .put("mtu", mtu)
            .put("endpoint", record.endpoint)
            .put("protocol", TunnelDefaults.PROTOCOL)
            .put("innerProtocol", TunnelDefaults.PROTOCOL)
            .put("awgJunkCount", TunnelDefaults.JUNK_COUNT)
            .put("awgJunkMin", TunnelDefaults.JUNK_MIN)
            .put("awgJunkMax", TunnelDefaults.JUNK_MAX)
            .put("timeoutSec", TunnelDefaults.CONNECT_TIMEOUT_SEC)
            .apply {
                if (record.awgI1.isNotBlank()) put("awgI1", record.awgI1)
                if (warpInWarp) put("throughEndpoint", record.endpoint)
            }
    )

    /**
     * The first endpoint of a finished search that both answered and stayed up.
     * The core returns results ordered, so the first match is the best one.
     */
    fun bestEndpoint(report: JSONObject): EndpointRecord? {
        val results = report.optJSONArray("results") ?: return null
        for (index in 0 until results.length()) {
            val endpoint = results.optJSONObject(index) ?: continue
            if (!endpoint.optBoolean("working") || !endpoint.optBoolean("durable")) continue
            val address = endpoint.optString("endpoint")
            if (address.isBlank()) continue
            return EndpointRecord(
                endpoint = address,
                awgI1 = report.optString("awgI1"),
                updatedAt = System.currentTimeMillis()
            )
        }
        return null
    }

    private fun base(operation: String, accountJson: String?, payload: JSONObject): String = JSONObject()
        .put("schemaVersion", 1)
        .put("operation", operation)
        .put("accountJson", accountJson.orEmpty())
        .put("payload", payload)
        .toString()
}
