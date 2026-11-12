package io.github.loopholetv.util

/** The first [max] code points, so a surrogate pair (an emoji, for example) is never split. */
fun String.takeCodePoints(max: Int): String {
    require(max >= 0) { "max < 0" }
    if (length <= max) return this
    return substring(0, offsetByCodePoints(0, minOf(max, codePointCount(0, length))))
}
