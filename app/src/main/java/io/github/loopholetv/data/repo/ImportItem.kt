package io.github.loopholetv.data.repo

import io.github.loopholetv.data.m3u.M3uEntry
import io.github.loopholetv.data.vod.VodKind
import io.github.loopholetv.data.vod.vodKey
import io.github.loopholetv.data.vod.vodKindOf

/** One entry delivered by a source: a live channel or an on-demand film or episode. */
internal sealed interface ImportItem

/** A film or episode entry. [key] is the stream URL without its query, so a changed token keeps its identity. */
internal data class ImportedVod(
    val kind: VodKind,
    val key: String,
    val name: String,
    val streamUrl: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
) : ImportItem

/** Entries typed `movie` or `series` become [ImportedVod]; everything else is a channel (or null when unusable). */
internal fun M3uEntry.toImportItem(): ImportItem? =
    vodKindOf(type)?.let { kind ->
        ImportedVod(
            kind = kind,
            key = vodKey(url),
            name = name.blankToNull() ?: tvgName.blankToNull() ?: return null,
            streamUrl = url,
            logo = logo.blankToNull(),
            group = group.blankToNull() ?: UNCATEGORISED,
            tvgId = tvgId.blankToNull(),
            userAgent = userAgent.blankToNull(),
            referrer = referrer.blankToNull(),
        )
    } ?: toImported()
