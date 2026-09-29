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
     *
     * With [through] set the whole search runs from inside the tunnel to that
     * endpoint, so every endpoint it returns exits through the outer node's
     * region. The engine drops the search to one tunnel at a time on its own,
     * since the nested tunnels share a single outer device.
     */
    fun scan(accountJson: String, through: String = ""): String = base(
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
            .apply {
                if (through.isNotBlank()) {
                    put("throughEndpoint", through)
                    put("innerProtocol", TunnelDefaults.INNER_PROTOCOL)
                }
            }
    )

    /**
     * Opens the tunnel on an interface the app already brought up.
     *
     * [record] carries the whole route: in the nested mode [EndpointRecord.endpoint]
     * is the tunnel inside, reached from [EndpointRecord.outerEndpoint], which
     * is the only one of the two that crosses this network and so the only one
     * anything out there can see.
     */
    fun connect(
        accountJson: String,
        tunFd: Int,
        mtu: Int,
        record: EndpointRecord,
        warpInWarp: Boolean
    ): String {
        val nested = warpInWarp && record.nested
        return base(
            operation = "connect",
            accountJson = accountJson,
            payload = JSONObject()
                .put("tunFd", tunFd)
                .put("mtu", mtu)
                .put("endpoint", record.endpoint)
                .put("protocol", TunnelDefaults.PROTOCOL)
                .put("awgJunkCount", TunnelDefaults.JUNK_COUNT)
                .put("awgJunkMin", TunnelDefaults.JUNK_MIN)
                .put("awgJunkMax", TunnelDefaults.JUNK_MAX)
                .put("timeoutSec", TunnelDefaults.CONNECT_TIMEOUT_SEC)
                .apply {
                    if (record.awgI1.isNotBlank()) put("awgI1", record.awgI1)
                    if (nested) {
                        put("throughEndpoint", record.outerEndpoint)
                        put("innerProtocol", TunnelDefaults.INNER_PROTOCOL)
                    }
                }
        )
    }

    /**
     * The first endpoint of a finished search that both answered and stayed up.
     * The core returns results ordered, so the first match is the best one.
     *
     * [outerEndpoint] is carried through when the search itself ran inside an
     * outer tunnel, which is what turns a plain result into a chain.
     */
    fun bestEndpoint(report: JSONObject, outerEndpoint: String = ""): EndpointRecord? {
        val results = report.optJSONArray("results") ?: return null
        for (index in 0 until results.length()) {
            val endpoint = results.optJSONObject(index) ?: continue
            if (!endpoint.optBoolean("working") || !endpoint.optBoolean("durable")) continue
            val address = endpoint.optString("endpoint")
            if (address.isBlank()) continue
            return EndpointRecord(
                endpoint = address,
                outerEndpoint = outerEndpoint,
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
