@file:OptIn(UnstableApi::class)

package io.github.loopholetv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.TrackNameProvider
import java.util.Locale

/** The track types the player lets the user choose between, mapped to Media3's `C.TRACK_TYPE_*`. */
enum class TrackType(val c: Int) {
    VIDEO(C.TRACK_TYPE_VIDEO),
    AUDIO(C.TRACK_TYPE_AUDIO),
    TEXT(C.TRACK_TYPE_TEXT),
}

/**
 * Identifies one track by its group's [androidx.media3.common.TrackGroup.id] and its index inside
 * that group. Using the id instead of the group's position keeps a choice valid when new groups
 * arrive later (MPEG-TS announces audio and subtitle PIDs as their packets show up).
 *
 * Group ids can be derived from stream URLs, so never log a [TrackId].
 */
data class TrackId(val groupId: String, val trackIndex: Int)

/** One entry of a track list: its [label] for the user and whether the player is playing it. */
data class TrackOption(val id: TrackId, val label: String, val selected: Boolean)

/** What the user chose for one track type. */
sealed interface TrackChoice {
    /** Let the track selector decide (adaptive quality, preferred languages). */
    data object Auto : TrackChoice

    /** Turn the type off. Only meaningful for subtitles. */
    data object Off : TrackChoice

    /** Play exactly this track. */
    data class Pick(val id: TrackId) : TrackChoice
}

/**
 * The current stream's choosable tracks.
 *
 * [video] lists qualities only for an adaptive group with at least two playable tracks (HLS
 * variants); a single-track stream lists none. [videoAuto] is true while no video track is forced.
 * While [videoAuto] is true no quality row is marked selected: Media3 reports every track of an
 * adaptive selection as selected, so the "Auto" entry is the current one.
 * [textOff] is true when subtitles are disabled or none is playing. [frameRate] is the frame rate
 * of the first selected video track, or null when unknown. In an adaptive selection that is the
 * first variant of the ladder, not necessarily the one playing; prefer the player's current video
 * format when one is available.
 */
data class PlayerTracks(
    val video: List<TrackOption>,
    val videoAuto: Boolean,
    val audio: List<TrackOption>,
    val text: List<TrackOption>,
    val textOff: Boolean,
    val frameRate: Float?,
) {
    companion object {
        val NONE = PlayerTracks(
            video = emptyList(),
            videoAuto = true,
            audio = emptyList(),
            text = emptyList(),
            textOff = true,
            frameRate = null,
        )
    }
}

/** Builds the track lists from the player's [tracks] and current [params]. Unsupported tracks are left out. */
fun playerTracks(tracks: Tracks, params: TrackSelectionParameters, names: TrackNameProvider): PlayerTracks {
    val groups = tracks.groups
    val textSelected = groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
    val videoAuto = params.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }
    return PlayerTracks(
        video = videoOptions(groups, names, videoAuto),
        videoAuto = videoAuto,
        audio = namedOptions(groups, C.TRACK_TYPE_AUDIO, names),
        text = namedOptions(groups, C.TRACK_TYPE_TEXT, names),
        textOff = C.TRACK_TYPE_TEXT in params.disabledTrackTypes || !textSelected,
        frameRate = selectedVideoFormat(groups)?.frameRate?.takeIf { it > 0f },
    )
}

private class Candidate(val option: TrackOption, val format: Format)

private fun supportedCandidates(group: Tracks.Group, label: (Format) -> String): List<Candidate> {
    val id = group.mediaTrackGroup.id
    return (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
        val format = group.getTrackFormat(i)
        Candidate(TrackOption(TrackId(id, i), label(format), group.isTrackSelected(i)), format)
    }
}

private fun namedOptions(groups: List<Tracks.Group>, type: Int, names: TrackNameProvider): List<TrackOption> =
    groups.filter { it.type == type }
        .flatMap { group -> supportedCandidates(group) { names.getTrackName(it) } }
        .map { it.option }

private fun videoOptions(groups: List<Tracks.Group>, names: TrackNameProvider, auto: Boolean): List<TrackOption> =
    groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isAdaptiveSupported }
        .map { group -> supportedCandidates(group) { "" } }
        .filter { it.size >= 2 }
        .flatMap { candidates -> labelVideo(candidates, names) }
        .map { if (auto && it.selected) it.copy(selected = false) else it }

private fun labelVideo(candidates: List<Candidate>, names: TrackNameProvider): List<TrackOption> {
    val heightCounts = candidates.groupingBy { it.format.height }.eachCount()
    val labelled = candidates
        .sortedWith(compareByDescending<Candidate> { it.format.height }.thenByDescending { bitrateOf(it.format) })
        .map { c ->
            val height = c.format.height
            val bitrate = bitrateOf(c.format)
            val label = when {
                height <= 0 -> names.getTrackName(c.format)
                (heightCounts[height] ?: 0) > 1 && bitrate > 0 ->
                    "${height}p (${String.format(Locale.US, "%.1f", bitrate / 1_000_000.0)} Mbit/s)"
                else -> "${height}p"
            }
            c.option.copy(label = label)
        }
    return distinctLabels(labelled)
}

/** Numbers repeated labels ("720p", "720p (2)") so every row can be told apart. */
private fun distinctLabels(options: List<TrackOption>): List<TrackOption> {
    val seen = mutableMapOf<String, Int>()
    return options.map { o ->
        val n = (seen[o.label] ?: 0) + 1
        seen[o.label] = n
        if (n == 1) o else o.copy(label = "${o.label} ($n)")
    }
}

private fun bitrateOf(format: Format): Int =
    if (format.peakBitrate > 0) format.peakBitrate else format.bitrate

private fun selectedVideoFormat(groups: List<Tracks.Group>): Format? =
    groups.asSequence()
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).asSequence().filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
        .firstOrNull()

/**
 * Applies the user's [choice] for [type]. [TrackChoice.Pick] looks the group up by id in the current [tracks],
 * so a list built before more groups arrived still selects the right track; when the group or
 * track is gone the parameters are returned unchanged. [TrackChoice.Off] only applies to
 * subtitles and is ignored for video and audio. [TrackChoice.Auto] only clears forced tracks; it
 * does not re-enable a type that [TrackChoice.Off] disabled, so use [TrackChoice.Pick] (or
 * [forNewChannel]) to turn subtitles back on.
 */
fun TrackSelectionParameters.withChoice(
    tracks: Tracks,
    type: TrackType,
    choice: TrackChoice,
): TrackSelectionParameters = when (choice) {
    TrackChoice.Auto -> buildUpon().clearOverridesOfType(type.c).build()
    TrackChoice.Off ->
        if (type != TrackType.TEXT) {
            this
        } else {
            buildUpon().clearOverridesOfType(type.c).setTrackTypeDisabled(type.c, true).build()
        }
    is TrackChoice.Pick -> {
        val group = tracks.groups.firstOrNull { it.type == type.c && it.mediaTrackGroup.id == choice.id.groupId }
        if (group == null || choice.id.trackIndex !in 0 until group.length) {
            this
        } else {
            buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, choice.id.trackIndex))
                .setTrackTypeDisabled(type.c, false)
                .build()
        }
    }
}

/**
 * Resets the parameters for a newly started channel: every forced track is cleared and the lasting
 * preferences apply. A null [audioLanguage] leaves the stream's default audio. A null
 * [subtitleLanguage] turns subtitles off; otherwise subtitles turn on only for a track in that
 * language (or a forced one), not for a track the stream merely flags as default.
 * [preferSurround] false limits audio to stereo.
 *
 * The receiver's concrete type is kept, so player-level flags such as tunneling survive.
 */
fun TrackSelectionParameters.forNewChannel(
    audioLanguage: String?,
    subtitleLanguage: String?,
    preferSurround: Boolean,
): TrackSelectionParameters {
    val builder = buildUpon()
        .clearOverrides()
        .setPreferredAudioLanguage(audioLanguage)
        .setMaxAudioChannelCount(if (preferSurround) Int.MAX_VALUE else 2)
    if (subtitleLanguage == null) {
        builder
            .setPreferredTextLanguage(null)
            .setIgnoredTextSelectionFlags(0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder
            .setPreferredTextLanguage(subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    }
    return builder.build()
}

/**
 * Turns tunneled playback on or off. Tunneling is a [DefaultTrackSelector.Parameters] flag, so any
 * other parameters type is returned unchanged.
 */
fun TrackSelectionParameters.withTunneling(enabled: Boolean): TrackSelectionParameters =
    if (this is DefaultTrackSelector.Parameters) buildUpon().setTunnelingEnabled(enabled).build() else this
