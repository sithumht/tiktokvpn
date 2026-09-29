package com.tiktokvpn.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.routeDataStore by preferencesDataStore("route")

/**
 * The last chain that answered, remembered with the exact endpoints and the
 * parameters they were found under, so reconnecting reproduces a route that
 * already worked instead of guessing.
 *
 * In the nested mode there are two endpoints and they are never the same one:
 * [outerEndpoint] is the tunnel that crosses this network, [endpoint] the
 * tunnel carried inside it, which is where traffic leaves. For a plain route
 * [outerEndpoint] is blank and [endpoint] is used directly.
 */
data class EndpointRecord(
    val endpoint: String,
    val outerEndpoint: String = "",
    val awgI1: String,
    val updatedAt: Long
) {
    val nested: Boolean
        get() = outerEndpoint.isNotBlank()

    /** How the route is written down: the whole chain when there is one. */
    val label: String
        get() = if (nested) "$outerEndpoint → $endpoint" else endpoint
}

@Singleton
class EndpointCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val record: Flow<EndpointRecord?> = context.routeDataStore.data.map { preferences ->
        val endpoint = preferences[ENDPOINT].orEmpty()
        if (endpoint.isBlank()) {
            null
        } else {
            EndpointRecord(
                endpoint = endpoint,
                outerEndpoint = preferences[OUTER_ENDPOINT].orEmpty(),
                awgI1 = preferences[I1].orEmpty(),
                updatedAt = preferences[UPDATED_AT] ?: 0L
            )
        }
    }

    suspend fun read(): EndpointRecord? = record.first()

    suspend fun save(value: EndpointRecord) {
        context.routeDataStore.edit { preferences ->
            preferences[ENDPOINT] = value.endpoint
            preferences[OUTER_ENDPOINT] = value.outerEndpoint
            preferences[I1] = value.awgI1
            preferences[UPDATED_AT] = value.updatedAt
        }
    }

    suspend fun clear() {
        context.routeDataStore.edit { it.clear() }
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val OUTER_ENDPOINT = stringPreferencesKey("outer_endpoint")
        val I1 = stringPreferencesKey("i1")
        val UPDATED_AT = longPreferencesKey("updated_at")
    }
}
