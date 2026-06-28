package com.reelkill.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reelkill.common.AppIds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.reelKillDataStore by preferencesDataStore(name = "reelkill_preferences")

@Singleton
class ReelKillPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val onboardingComplete: Flow<Boolean> = context.reelKillDataStore.data.map { preferences ->
        preferences[ONBOARDING_COMPLETE] ?: false
    }

    val selectedAppId: Flow<String> = context.reelKillDataStore.data.map { preferences ->
        preferences[SELECTED_APP_ID] ?: AppIds.INSTAGRAM
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.reelKillDataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETE] = complete
        }
    }

    suspend fun setSelectedAppId(appId: String) {
        context.reelKillDataStore.edit { preferences ->
            preferences[SELECTED_APP_ID] = appId
        }
    }

    private companion object {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val SELECTED_APP_ID = stringPreferencesKey("selected_app_id")
    }
}
