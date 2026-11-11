package com.share64.app.core

/** Semantic version ("v1.2.3", "1.2.3-beta.1"). Pure Kotlin. */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1 // a release is newer than any of its pre-releases
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val pattern =
            Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String?): Version? {
            val m = pattern.matchEntire(text?.trim().orEmpty()) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = if (m.groupValues[3].isEmpty()) 0 else m.groupValues[3].toIntOrNull() ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            return Version(major, minor, patch, pre)
        }

        /** True when [candidate] is a strictly newer version than [current]. Unparsable input is never "newer". */
        fun isNewer(candidate: String?, current: String?): Boolean {
            val c = parse(candidate) ?: return false
            val cur = parse(current) ?: return true // unknown installed version: offer the update
            return c > cur
        }
    }
}
