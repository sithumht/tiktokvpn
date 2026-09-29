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
 * The last endpoint that answered, remembered with the exact parameters it
 * was found under, so reconnecting reproduces a route that already worked
 * instead of guessing.
 */
data class EndpointRecord(
    val endpoint: String,
    val awgI1: String,
    val updatedAt: Long
)

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
                awgI1 = preferences[I1].orEmpty(),
                updatedAt = preferences[UPDATED_AT] ?: 0L
            )
        }
    }

    suspend fun read(): EndpointRecord? = record.first()

    suspend fun save(value: EndpointRecord) {
        context.routeDataStore.edit { preferences ->
            preferences[ENDPOINT] = value.endpoint
            preferences[I1] = value.awgI1
            preferences[UPDATED_AT] = value.updatedAt
        }
    }

    suspend fun clear() {
        context.routeDataStore.edit { it.clear() }
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val I1 = stringPreferencesKey("i1")
        val UPDATED_AT = longPreferencesKey("updated_at")
    }
}
