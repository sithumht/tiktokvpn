package com.tiktokvpn.app.core

import com.tiktokvpn.app.data.EndpointRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestsTest {

    private fun payloadOf(request: String): JSONObject = JSONObject(request).getJSONObject("payload")

    @Test
    fun requestCarriesSchemaAndAccount() {
        val request = JSONObject(Requests.scan("account-json"))
        assertEquals(1, request.getInt("schemaVersion"))
        assertEquals("scan", request.getString("operation"))
        assertEquals("account-json", request.getString("accountJson"))
    }

    @Test
    fun searchUsesTheProfileTheConnectionIsOpenedWith() {
        val payload = payloadOf(Requests.scan("account"))
        assertEquals("awg", payload.getString("protocol"))
        assertEquals(TunnelDefaults.SCAN_TIMEOUT_SEC, payload.getInt("timeoutSec"))
        assertEquals(TunnelDefaults.SCAN_JOBS, payload.getInt("jobs"))
        assertEquals(TunnelDefaults.SCAN_SAMPLE, payload.getInt("samplePerSubnet"))
        assertEquals(TunnelDefaults.SCAN_PINGS, payload.getInt("tunnelPingCount"))
        assertEquals(TunnelDefaults.JUNK_COUNT, payload.getInt("awgJunkCount"))
        assertEquals(TunnelDefaults.JUNK_MIN, payload.getInt("awgJunkMin"))
        assertEquals(TunnelDefaults.JUNK_MAX, payload.getInt("awgJunkMax"))
        assertTrue(payload.getBoolean("awgAutoI1"))
        assertEquals("ping", payload.getString("bestBy"))
        assertFalse(payload.getBoolean("speedTest"))
    }

    @Test
    fun registerAsksForAnAccountCarryingASecondTunnel() {
        val payload = payloadOf(Requests.register(true, "https://relay.example"))
        assertTrue(payload.getBoolean("fresh"))
        assertEquals(2, payload.getInt("timeoutSec"))
        assertFalse(payload.getBoolean("ipv6"))
    }

    @Test
    fun registerUsesTheRelayOnlyWhenItIsEnabled() {
        val enabled = payloadOf(Requests.register(true, "https://relay.example"))
        assertEquals("https://relay.example", enabled.getString("relay"))

        val disabled = payloadOf(Requests.register(false, "https://relay.example"))
        assertEquals("none", disabled.getString("relay"))

        val blank = payloadOf(Requests.register(true, "   "))
        assertEquals("none", blank.getString("relay"))
    }

    @Test
    fun connectionReproducesTheProfileTheRouteWasFoundWith() {
        val record = EndpointRecord(endpoint = "1.2.3.4:2408", awgI1 = "<r 4>", updatedAt = 7L)
        val payload = payloadOf(Requests.connect("account", 42, TunnelDefaults.MTU, record, false))

        assertEquals(42, payload.getInt("tunFd"))
        assertEquals(TunnelDefaults.MTU, payload.getInt("mtu"))
        assertEquals("1.2.3.4:2408", payload.getString("endpoint"))
        assertEquals("<r 4>", payload.getString("awgI1"))
        assertEquals("awg", payload.getString("protocol"))
        assertEquals(TunnelDefaults.JUNK_COUNT, payload.getInt("awgJunkCount"))
        assertEquals(TunnelDefaults.CONNECT_TIMEOUT_SEC, payload.getInt("timeoutSec"))
        assertFalse(payload.has("throughEndpoint"))
    }

    @Test
    fun nestedModeWrapsTheSameEndpointItConnectsTo() {
        val record = EndpointRecord(endpoint = "5.6.7.8:51820", awgI1 = "", updatedAt = 1L)
        val payload = payloadOf(
            Requests.connect("account", 7, TunnelDefaults.MTU_NESTED, record, true)
        )

        assertEquals(TunnelDefaults.MTU_NESTED, payload.getInt("mtu"))
        assertEquals(record.endpoint, payload.getString("throughEndpoint"))
        assertEquals(record.endpoint, payload.getString("endpoint"))
        assertFalse(payload.has("awgI1"))
    }

    @Test
    fun bestEndpointIsTheFirstResultThatAnsweredAndHeld() {
        val report = JSONObject()
            .put("awgI1", "<r 1>")
            .put(
                "results",
                org.json.JSONArray()
                    .put(JSONObject().put("endpoint", "torn:1").put("working", true).put("durable", false))
                    .put(JSONObject().put("endpoint", "best:2").put("working", true).put("durable", true))
                    .put(JSONObject().put("endpoint", "next:3").put("working", true).put("durable", true))
            )

        val best = Requests.bestEndpoint(report)

        assertNotNull(best)
        assertEquals("best:2", best!!.endpoint)
        assertEquals("<r 1>", best.awgI1)
        assertTrue(best.updatedAt > 0L)
    }

    @Test
    fun bestEndpointIsNullWhenNothingSurvived() {
        val empty = JSONObject().put("results", org.json.JSONArray())
        assertNull(Requests.bestEndpoint(empty))

        val tornOnly = JSONObject().put(
            "results",
            org.json.JSONArray()
                .put(JSONObject().put("endpoint", "torn:1").put("working", true).put("durable", false))
                .put(JSONObject().put("endpoint", "down:2").put("working", false).put("durable", true))
        )
        assertNull(Requests.bestEndpoint(tornOnly))
        assertNull(Requests.bestEndpoint(JSONObject()))
    }
}
