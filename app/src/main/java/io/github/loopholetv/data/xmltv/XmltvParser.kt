package io.github.loopholetv.data.xmltv

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Streaming XMLTV parser. Channels and programmes are handed to the callbacks one by one and never
 * accumulated. Gzip input is detected from the `1F 8B` magic bytes, not from any file name.
 *
 * Resource caps: text content is truncated at 64 KiB per element, at most 16 display names are kept
 * per channel, and input inflating past 1 GiB fails with [XmltvTooLargeException].
 *
 * Stream ownership: [parse] closes [input] (and any gzip wrapper it creates, releasing the Inflater)
 * before returning or throwing.
 *
 * Throws only on malformed XML or I/O failure; a single bad programme (unparsable/missing times,
 * stop <= start, blank title) is dropped silently.
 */
object XmltvParser {
    private const val MAX_INFLATED_BYTES = 1L shl 30
    private const val MAX_TEXT_CHARS = 64 * 1024
    private const val MAX_DISPLAY_NAMES = 16
    private const val GZIP_BUFFER = 64 * 1024
    private val TIME = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?\s*([+-]\d{2}(?::?\d{2})?|Z|UTC|GMT)?$""", RegexOption.IGNORE_CASE)

    fun parse(
        input: InputStream,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (XmltvProgramme) -> Unit,
    ) = parse(input, onChannel, onProgramme, MAX_INFLATED_BYTES)

    internal fun parse(
        input: InputStream,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (XmltvProgramme) -> Unit,
        maxInflatedBytes: Long,
    ) {
        val parser = newParser()
        CountingInputStream(maybeGunzip(input), maxInflatedBytes).use { stream ->
            parser.setInput(stream, null) // null: auto-detect encoding from the XML prolog
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser)?.let(onChannel)
                        "programme" -> readProgramme(parser)?.let(onProgramme)
                        // "tv" (and anything unexpected at the top level): descend into children
                    }
                }
                event = parser.next()
            }
        }
    }

    /** Fails with [XmltvTooLargeException] once more than [limit] bytes have been read. */
    private class CountingInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Int) {
            if (n > 0) {
                count += n
                if (count > limit) throw XmltvTooLargeException(limit)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also(::add)
        override fun skip(n: Long): Long {
            val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(0))
            var left = n
            while (left > 0) {
                val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }
        override fun markSupported() = false
    }

    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        // No DTD processing: XXE-safe, and <!DOCTYPE tv SYSTEM "xmltv.dtd"> is simply skipped.
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (_: Exception) {
            // Parser does not support toggling it; default for kxml is already off.
        }
        return parser
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(buffered, GZIP_BUFFER) else buffered
    }

    private fun readChannel(p: XmlPullParser): XmltvChannel? {
        val id = p.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = mutableListOf<String>()
        var icon: String? = null
        forEachChild(p) { name ->
            when (name) {
                "display-name" -> {
                    val text = readText(p).trim()
                    if (text.isNotEmpty() && names.size < MAX_DISPLAY_NAMES) names += text
                }
                "icon" -> {
                    if (icon == null) icon = p.getAttributeValue(null, "src")?.trim()?.ifEmpty { null }
                    skip(p)
                }
                else -> skip(p)
            }
        }
        return if (id.isEmpty()) null else XmltvChannel(id, names, icon)
    }

    private fun readProgramme(p: XmlPullParser): XmltvProgramme? {
        val channel = p.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var subTitle: String? = null
        var desc: String? = null
        var category: String? = null
        forEachChild(p) { name ->
            when (name) {
                "title" -> if (title == null) title = readText(p).trim().ifEmpty { null } else skip(p)
                "sub-title" -> if (subTitle == null) subTitle = readText(p).trim().ifEmpty { null } else skip(p)
                "desc" -> if (desc == null) desc = readText(p).trim().ifEmpty { null } else skip(p)
                "category" -> if (category == null) category = readText(p).trim().ifEmpty { null } else skip(p)
                else -> skip(p)
            }
        }
        val t = title
        if (channel.isEmpty() || start == null || stop == null || !stop.isAfter(start) || t == null) return null
        return XmltvProgramme(channel, start, stop, t, subTitle, desc, category)
    }

    /** Calls [block] on each direct child START_TAG; [block] must consume the child (readText/skip). */
    private inline fun forEachChild(p: XmlPullParser, block: (String) -> Unit) {
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> block(p.name)
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Positioned on a START_TAG; consumes through its matching END_TAG. */
    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Text/CDATA of the current element (nested text included), truncated at 64 KiB; consumes its END_TAG. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val room = MAX_TEXT_CHARS - sb.length
                    if (room > 0) sb.append(p.text, 0, minOf(room, p.text.length))
                }
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /** `yyyyMMdd[HH[mm[ss]]][ ±HH[[:]mm] | Z | UTC | GMT]` (suffix case-insensitive); no offset means UTC. Returns null when unparsable. */
    internal fun parseTime(raw: String?): Instant? {
        val m = TIME.matchEntire(raw?.trim() ?: return null) ?: return null
        val g = m.groupValues
        return try {
            val z = g[7]
            val offset = if (z.isEmpty() || z[0].isLetter()) {
                ZoneOffset.UTC // none, Z, UTC, GMT
            } else {
                val digits = z.substring(1).replace(":", "")
                ZoneOffset.of(z[0] + digits.substring(0, 2) + ":" + digits.substring(2).ifEmpty { "00" })
            }
            LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
            ).toInstant(offset)
        } catch (_: DateTimeException) {
            null
        }
    }
}
