package io.github.loopholetv.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * What the update check remembers between starts. [offer] is the newest release seen by the last
 * successful check (null when that check found nothing newer); [later] means the user chose Later
 * for it, which holds until the next successful check.
 */
data class UpdateRecord(
    val lastCheckMs: Long? = null,
    val offer: Release? = null,
    val later: Boolean = false,
)

/** Update-check state in the app's settings DataStore. Key names are persisted: never rename. */
class UpdatePrefs(private val store: DataStore<Preferences>) {

    suspend fun read(): UpdateRecord {
        val p = store.data.first()
        val version = p[OFFER_VERSION]?.let(SemVer::parse)
        val url = p[OFFER_URL]
        val size = p[OFFER_SIZE]
        val offer = if (version != null && url != null && size != null) Release(version, url, size) else null
        return UpdateRecord(lastCheckMs = p[LAST_CHECK], offer = offer, later = p[LATER] ?: false)
    }

    /** A successful check at [nowMs]: replaces the offer and clears Later. */
    suspend fun recordCheck(nowMs: Long, offer: Release?) {
        store.edit { p ->
            p[LAST_CHECK] = nowMs
            p[LATER] = false
            if (offer == null) {
                p.remove(OFFER_VERSION)
                p.remove(OFFER_URL)
                p.remove(OFFER_SIZE)
            } else {
                p[OFFER_VERSION] = offer.version.display
                p[OFFER_URL] = offer.apkUrl
                p[OFFER_SIZE] = offer.size
            }
        }
    }

    suspend fun setLater(later: Boolean) {
        store.edit { it[LATER] = later }
    }

    private companion object {
        val LAST_CHECK = longPreferencesKey("update_last_check_ms")
        val OFFER_VERSION = stringPreferencesKey("update_offer_version")
        val OFFER_URL = stringPreferencesKey("update_offer_url")
        val OFFER_SIZE = longPreferencesKey("update_offer_size")
        val LATER = booleanPreferencesKey("update_later")
    }
}
