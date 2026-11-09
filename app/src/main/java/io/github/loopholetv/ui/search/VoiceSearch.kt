package io.github.loopholetv.ui.search

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

/** The system speech recogniser, asked for one free-form result. */
fun voiceSearchIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel or programme name")
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/**
 * True when a recogniser activity resolves (most Fire TVs have none). Needs the manifest's
 * `<queries>` entry for RECOGNIZE_SPEECH (package visibility).
 */
fun isVoiceSearchAvailable(pm: PackageManager): Boolean = voiceSearchIntent().resolveActivity(pm) != null
