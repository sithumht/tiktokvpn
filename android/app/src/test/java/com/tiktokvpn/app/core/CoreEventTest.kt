package com.tiktokvpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEventTest {

    @Test
    fun connectionPhasesAreDecoded() {
        val handshaking = parseCoreEvent(
            """{"schemaVersion":1,"operation":"connect","type":"handshaking","message":"1.2.3.4:2408"}"""
        )
        assertTrue(handshaking is CoreEvent.Handshaking)
        assertEquals("1.2.3.4:2408", (handshaking as CoreEvent.Handshaking).endpoint)

        val connected = parseCoreEvent(
            """{"schemaVersion":1,"operation":"connect","type":"connected","message":"1.2.3.4:2408"}"""
        )
        assertTrue(connected is CoreEvent.Connected)

        val stats = parseCoreEvent(
            """{"schemaVersion":1,"operation":"connect","type":"stats","payload":{"rxBytes":2048,"txBytes":512}}"""
        ) as CoreEvent.Stats
        assertEquals(2048L, stats.rxBytes)
        assertEquals(512L, stats.txBytes)
    }

    @Test
    fun progressCarriesTheRawPhaseForLaterMapping() {
        val progress = parseCoreEvent(
            """{"schemaVersion":1,"operation":"scan","type":"progress","phase":"Phase 1","completed":3,"total":10}"""
        ) as CoreEvent.Progress
        assertEquals("Phase 1", progress.phase)
        assertEquals(3, progress.completed)
        assertEquals(10, progress.total)
    }

    @Test
    fun errorsAndCompletionAreDistinguished() {
        val error = parseCoreEvent(
            """{"schemaVersion":1,"type":"error","error":{"code":"endpoint_unreachable","message":"no answer","retryable":true}}"""
        ) as CoreEvent.Error
        assertEquals("endpoint_unreachable", error.code)
        assertTrue(error.retryable)

        val completed = parseCoreEvent(
            """{"schemaVersion":1,"operation":"register","type":"completed","payload":{"rawJson":"{}"}}"""
        ) as CoreEvent.Completed
        assertEquals("{}", completed.payload?.optString("rawJson"))

        val bareCompletion = parseCoreEvent(
            """{"schemaVersion":1,"operation":"connect","type":"completed"}"""
        ) as CoreEvent.Completed
        assertNull(bareCompletion.payload)
    }

    @Test
    fun junkInputNeverReachesTheInterface() {
        assertNull(parseCoreEvent("not json"))
        assertNull(parseCoreEvent(""))
    }
}
