package org.astrasec.launcher.data

import android.content.Context

class LauncherPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun favoriteComponents(): List<String> =
        preferences.getString(KEY_FAVORITES, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    fun ensureInitialFavorites(componentNames: List<String>) {
        if (preferences.getBoolean(KEY_FAVORITES_INITIALIZED, false)) return
        saveFavorites(componentNames.take(DEFAULT_FAVORITE_COUNT))
        preferences.edit().putBoolean(KEY_FAVORITES_INITIALIZED, true).apply()
    }

    fun isFavorite(componentName: String): Boolean =
        componentName in favoriteComponents()

    fun toggleFavorite(componentName: String): Boolean {
        val favorites = favoriteComponents().toMutableList()
        val isNowFavorite = if (componentName in favorites) {
            favorites.remove(componentName)
            false
        } else {
            favorites.add(componentName)
            true
        }
        saveFavorites(favorites)
        return isNowFavorite
    }

    private fun saveFavorites(componentNames: List<String>) {
        preferences.edit().putString(KEY_FAVORITES, componentNames.joinToString("\n")).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "launcher_preferences"
        const val KEY_FAVORITES = "favorite_components"
        const val KEY_FAVORITES_INITIALIZED = "favorites_initialized"
        const val DEFAULT_FAVORITE_COUNT = 8
    }
}
