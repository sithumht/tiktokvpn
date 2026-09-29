package com.tiktokvpn.app.core

/**
 * Every value the tunnel and the route search agree on. Keeping them in one
 * place is what makes a route reproducible: an endpoint found with these
 * parameters is only ever connected with the same ones.
 */
object TunnelDefaults {
    /** The tunnel that crosses the network - the only one DPI sees at all. */
    const val PROTOCOL = "awg"

    /**
     * The tunnel carried inside the outer one. Obfuscation buys nothing where
     * no observer exists, and it only eats MTU, so the nested hop stays plain.
     */
    const val INNER_PROTOCOL = "wg"

    const val MTU = 1280
    const val MTU_NESTED = 1220

    const val DNS = "1.1.1.1"
    const val FALLBACK_ADDRESS = "172.16.0.2"
    const val FALLBACK_PREFIX = 32

    // Route search
    const val SCAN_TIMEOUT_SEC = 2
    const val SCAN_JOBS = 10
    const val SCAN_SAMPLE = 5
    const val SCAN_PINGS = 5

    // Obfuscation profile the search validates an endpoint against.
    const val JUNK_COUNT = 6
    const val JUNK_MIN = 10
    const val JUNK_MAX = 50

    const val CONNECT_TIMEOUT_SEC = 15
}
