package io.github.loopholetv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.R
import io.github.loopholetv.data.tmdb.KeyStatus
import io.github.loopholetv.ui.manage.TextEntryDialog
import io.github.loopholetv.ui.theme.Tokens

private const val KEY_ROW = "tmdbKey"
private const val REMOVE_ROW = "tmdbRemove"

/** A v4 read token is a few hundred characters; this leaves room without accepting a whole document. */
private const val MAX_KEY_LENGTH = 1000

/** "Film and show details": the user's own TMDB key, its check, and TMDB's credit. */
@Composable
fun DetailsPage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit) {
    val host = rememberChoiceHost()
    var editing by rememberSaveable { mutableStateOf(false) }
    var refocus by rememberSaveable { mutableStateOf<String?>(null) }
    var rechecked by rememberSaveable { mutableStateOf(false) }
    // An entry typed on this visit; a refused one says so though no key is stored.
    var entered by rememberSaveable { mutableStateOf(false) }
    val key = ui.options.tmdbKey
    val hasKey = key.isNotBlank()

    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: KEY_ROW))
        onFocusConsumed()
    }
    // Once per opening, after the settings are read.
    LaunchedEffect(ui.loaded, hasKey) {
        if (ui.loaded && hasKey && !rechecked) {
            rechecked = true
            vm.recheckTmdbKey()
        }
    }
    LaunchedEffect(editing, refocus) {
        val r = refocus
        if (!editing && r != null) { focusSoon(host.requester(r)); refocus = null }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ValueRow(
            DT_KEY,
            if (hasKey) maskedTmdbKey(key) else "$DT_NOT_SET\n$DT_KEY_HINT",
            onClick = { editing = true },
            modifier = Modifier.focusRequester(host.requester(KEY_ROW)),
        )
        // Without a key nothing is said, except about an entry just refused.
        if (hasKey || (entered && ui.tmdbStatus == KeyStatus.BAD_KEY)) {
            keyStatusText(ui.tmdbStatus)?.let { text ->
                val failed = ui.tmdbStatus == KeyStatus.BAD_KEY || ui.tmdbStatus == KeyStatus.UNREACHABLE
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) Tokens.Error else Tokens.OnBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (hasKey) {
            ActionRow(
                DT_REMOVE,
                onClick = {
                    vm.removeTmdbKey()
                    entered = false
                    refocus = KEY_ROW
                },
                modifier = Modifier.focusRequester(host.requester(REMOVE_ROW)),
            )
        }
        Spacer(Modifier.height(12.dp))
        TmdbCredit(Modifier.padding(horizontal = 16.dp))
    }

    if (editing) {
        val close: () -> Unit = { refocus = KEY_ROW; editing = false }
        TextEntryDialog(
            title = DT_KEY,
            label = DT_KEY,
            // The saved key is never put back on screen.
            initial = "",
            keyboardType = KeyboardType.Ascii,
            onSave = { entered = it.isNotBlank(); vm.saveTmdbKey(it); close() },
            onDismiss = close,
            maxLength = MAX_KEY_LENGTH,
        )
    }
}

/** TMDB's logo beside its required credit line; also shown on About. */
@Composable
internal fun TmdbCredit(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB")
        Text(TMDB_CREDIT, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, modifier = Modifier.weight(1f, fill = false))
    }
}
