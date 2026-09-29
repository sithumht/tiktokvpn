package com.tiktokvpn.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val warpInWarp: Boolean = false,
    val relayEnabled: Boolean = true,
    val relayUrl: String = DEFAULT_RELAY
) {
    companion object {
        const val DEFAULT_RELAY = "https://edge-client-api.vercel.app"
    }
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            onboardingDone = preferences[ONBOARDING_DONE] ?: false,
            warpInWarp = preferences[WARP_IN_WARP] ?: false,
            relayEnabled = preferences[RELAY_ENABLED] ?: true,
            relayUrl = preferences[RELAY_URL] ?: AppSettings.DEFAULT_RELAY
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsDataStore.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun setWarpInWarp(enabled: Boolean) {
        context.settingsDataStore.edit { it[WARP_IN_WARP] = enabled }
    }

    suspend fun setRelayEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[RELAY_ENABLED] = enabled }
    }

    suspend fun setRelayUrl(value: String) {
        context.settingsDataStore.edit { it[RELAY_URL] = value.trim() }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val WARP_IN_WARP = booleanPreferencesKey("warp_in_warp")
        val RELAY_ENABLED = booleanPreferencesKey("relay_enabled")
        val RELAY_URL = stringPreferencesKey("relay_url")
    }
}
