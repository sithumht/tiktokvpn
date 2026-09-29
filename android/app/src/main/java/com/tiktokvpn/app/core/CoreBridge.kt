package com.tiktokvpn.app.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflection wrapper around the core library shipped as a separate AAR.
 *
 * The core is built from Go with gomobile, so its classes only exist when the
 * AAR is on the classpath: every call is resolved at runtime and the app still
 * compiles, runs and reports itself as unconfigured when it is not.
 */
@Singleton
class CoreBridge @Inject constructor() {

    /** Runs a request to completion. [onEvent] receives every core message. */
    fun start(requestJson: String, onEvent: (String) -> Unit) {
        val api = apiClass()
        val listenerType = Class.forName("mobileapi.Listener")
        val listener = Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType)
        ) { _, method, arguments ->
            when {
                method.name == "onEvent" && arguments?.size == 1 -> {
                    onEvent(arguments[0] as String)
                    null
                }
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        try {
            api.getMethod("start", String::class.java, listenerType).invoke(null, requestJson, listener)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    /** Aborts the running request without tearing the process down. */
    fun cancel() {
        runCatching { apiClass().getMethod("cancel").invoke(null) }
    }

    /** Alias of [cancel]: the core treats both as "stop what is running". */
    fun stop() = cancel()

    fun coreVersion(): String = version("coreVersion")

    fun generateI1(host: String): String = try {
        apiClass().getMethod("generateI1", String::class.java).invoke(null, host) as String
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    /**
     * Hands the system's "keep this socket off the tunnel" call to the core.
     * Passing null takes it back, which the service does when it goes away so
     * a dead instance is never asked to protect a socket again.
     */
    fun setSocketProtector(protect: ((Int) -> Boolean)?) {
        val api = runCatching { apiClass() }.getOrNull() ?: return
        val protectorType = runCatching { Class.forName("mobileapi.SocketProtector") }.getOrNull() ?: return
        val handler: (Any, java.lang.reflect.Method, Array<out Any?>?) -> Any? = { _, method, arguments ->
            when {
                method.name == "protect" && arguments?.size == 1 ->
                    // gomobile widens Go's `int` to a Java long.
                    protect?.invoke((arguments[0] as Number).toInt()) ?: false
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        val proxy = Proxy.newProxyInstance(protectorType.classLoader, arrayOf(protectorType)) { receiver, method, arguments ->
            handler(receiver, method, arguments)
        }
        val argument = protect?.let { proxy }
        runCatching { api.getMethod("setSocketProtector", protectorType).invoke(null, argument) }
    }

    /** True when the core AAR is present and callable. */
    fun available(): Boolean = runCatching { apiClass() }.isSuccess

    private fun version(method: String): String = runCatching {
        apiClass().getMethod(method).invoke(null) as String
    }.getOrDefault("unavailable")

    private fun apiClass(): Class<*> = Class.forName("mobileapi.Mobileapi")
}
