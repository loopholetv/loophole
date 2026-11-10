package io.github.loopholetv.data.settings

import kotlin.math.abs

private const val MIB = 1024 * 1024

/** Player buffer sizes: durations in milliseconds and the byte cap the buffer never exceeds. */
enum class BufferPreset(
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val afterRebufferMs: Int,
    val targetBytes: Int,
) {
    SMALL(4_000, 12_000, 800, 2_000, 16 * MIB),
    NORMAL(12_000, 30_000, 1_500, 3_000, 32 * MIB),
    LARGE(30_000, 60_000, 2_500, 6_000, 64 * MIB),
}

/** Which decoders a stream may use. Software falls back to hardware when it can't decode. */
enum class DecoderChoice { HARDWARE, SOFTWARE }

/** AUTO sends compressed surround audio to the TV or receiver when it supports it; OFF decodes it here. */
enum class Passthrough { AUTO, OFF }

/** How the picture fills the screen. */
enum class AspectMode(val label: String) {
    FIT("Fit"),
    STRETCH("Stretch"),
    ZOOM("Zoom"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
}

enum class SubtitleSize(val label: String, val scale: Float) {
    SMALL("Small", 0.8f),
    NORMAL("Normal", 1f),
    LARGE("Large", 1.25f),
    EXTRA_LARGE("Extra large", 1.5f),
}

enum class SubtitleColour(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    YELLOW("Yellow", 0xFFFFE14D.toInt()),
}

enum class SubtitleBackground(val label: String, val argb: Int) {
    NONE("None", 0x00000000),
    SEE_THROUGH("See-through box", 0x99000000.toInt()),
    BLACK("Black box", 0xFF000000.toInt()),
}

enum class SubtitleEdge(val label: String) {
    OUTLINE("Outline"),
    SHADOW("Shadow"),
    NONE("None"),
}

/** The user's subtitle style; it replaces the stream's own subtitle colours and sizes. */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.NORMAL,
    val colour: SubtitleColour = SubtitleColour.WHITE,
    val background: SubtitleBackground = SubtitleBackground.NONE,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
)

/** Player settings. Language codes are ISO 639-1 from [PLAYBACK_LANGUAGES]; null means none chosen. */
data class PlaybackSettings(
    val bufferPreset: BufferPreset = BufferPreset.NORMAL,
    val videoDecoder: DecoderChoice = DecoderChoice.HARDWARE,
    val audioDecoder: DecoderChoice = DecoderChoice.HARDWARE,
    val autoFrameRate: Boolean = false,
    val preferSurround: Boolean = true,
    val passthrough: Passthrough = Passthrough.AUTO,
    val decodeDtsToPcm: Boolean = false,
    val tunneling: Boolean = false,
    /** Preferred audio language; null follows the stream's default track. */
    val audioLanguage: String? = null,
    /** Subtitle language turned on automatically when present; null means subtitles off. */
    val subtitleLanguage: String? = null,
    /** Audio delay in milliseconds; always a value [normaliseAudioDelay] returns. */
    val audioDelayMs: Int = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Whether the Home button opens picture-in-picture from the player. */
    val pipOnHome: Boolean = false,
)

const val AUDIO_DELAY_STEP_MS = 50

val AUDIO_DELAY_RANGE_MS: IntRange = -1000..1000

/** Clamps [ms] to [AUDIO_DELAY_RANGE_MS], then rounds to the nearest step (halves away from zero). */
fun normaliseAudioDelay(ms: Int): Int {
    val clamped = ms.coerceIn(AUDIO_DELAY_RANGE_MS)
    val steps = (abs(clamped) + AUDIO_DELAY_STEP_MS / 2) / AUDIO_DELAY_STEP_MS
    val rounded = steps * AUDIO_DELAY_STEP_MS
    return if (clamped < 0) -rounded else rounded
}

/** "Off" for 0, otherwise a signed value such as "+150 ms" or "−50 ms" (with a real minus sign). */
fun audioDelayText(ms: Int): String = when {
    ms == 0 -> "Off"
    ms > 0 -> "+$ms ms"
    else -> "−${-ms} ms"
}

data class Language(val code: String, val name: String)

/** Languages offered for audio and subtitle preferences, in display order. */
val PLAYBACK_LANGUAGES: List<Language> = listOf(
    Language("en", "English"),
    Language("es", "Spanish"),
    Language("fr", "French"),
    Language("de", "German"),
    Language("it", "Italian"),
    Language("pt", "Portuguese"),
    Language("nl", "Dutch"),
    Language("sv", "Swedish"),
    Language("da", "Danish"),
    Language("no", "Norwegian"),
    Language("fi", "Finnish"),
    Language("pl", "Polish"),
    Language("el", "Greek"),
    Language("tr", "Turkish"),
    Language("ru", "Russian"),
    Language("ar", "Arabic"),
    Language("hi", "Hindi"),
    Language("zh", "Chinese"),
    Language("ja", "Japanese"),
    Language("ko", "Korean"),
)
