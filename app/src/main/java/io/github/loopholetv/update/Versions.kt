package io.github.loopholetv.update

/**
 * A `MAJOR.MINOR.PATCH[-pre][+build]` version. A pre-release sorts before its release; build
 * metadata is ignored. Pre-release labels follow SemVer precedence (`rc.2` < `rc.10` < `rc.x`).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    /** SemVer precedence: dot-separated, numeric parts as numbers and below alphanumeric ones. */
    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val pn = p.toBigIntegerOrNull()
            val qn = q.toBigIntegerOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    /** "0.4.2" or "0.4.2-test", without a `v` prefix. */
    val display: String get() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN =
            Regex("""^[vV]?(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Null for anything that is not a three-part version (with an optional `v`). */
        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (a, b, c, pre) = m.destructured
            return SemVer(a.toInt(), b.toInt(), c.toInt(), pre.ifEmpty { null })
        }
    }
}

/** Whether release [tag] is strictly newer than [current]; false when either can't be read. */
fun isNewerVersion(tag: String, current: String): Boolean {
    val t = SemVer.parse(tag) ?: return false
    val c = SemVer.parse(current) ?: return false
    return t > c
}
