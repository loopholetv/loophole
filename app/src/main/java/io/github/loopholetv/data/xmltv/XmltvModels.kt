package io.github.loopholetv.data.xmltv

import java.time.Instant

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?,
)

data class XmltvProgramme(
    val channelId: String,
    val start: Instant,
    val stop: Instant,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val category: String?,
)
