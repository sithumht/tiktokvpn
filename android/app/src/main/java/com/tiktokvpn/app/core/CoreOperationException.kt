package com.tiktokvpn.app.core

/** A core failure with a code the interface knows how to phrase. */
class CoreOperationException(
    val code: String,
    val detail: String? = null
) : Exception(detail ?: code)
