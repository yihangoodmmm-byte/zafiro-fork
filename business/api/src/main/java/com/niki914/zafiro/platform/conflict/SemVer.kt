package com.niki914.zafiro.platform.conflict

data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: List<String> = emptyList()) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0)
        require(preRelease.all { IDENTIFIER.matches(it) && (!it.all(Char::isDigit) || it == "0" || !it.startsWith('0')) })
    }
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty()) return if (other.preRelease.isEmpty()) 0 else 1
        if (other.preRelease.isEmpty()) return -1
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[index]
            val b = other.preRelease[index]
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            val order = when {
                an && bn -> compareValues(a.length, b.length).takeIf { it != 0 } ?: a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }
    companion object {
        private val IDENTIFIER = Regex("[0-9A-Za-z-]+")
        private val CORE = Regex("0|[1-9][0-9]*")
        fun parse(raw: String?): SemVer? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val buildParts = text.split('+')
            if (buildParts.size > 2) return null
            if (buildParts.size == 2 && !buildParts[1].split('.').all { IDENTIFIER.matches(it) }) return null
            val releaseParts = buildParts[0].split('-', limit = 2)
            val core = releaseParts[0].split('.')
            if (core.size != 3 || !core.all { CORE.matches(it) }) return null
            val major = core[0].toIntOrNull() ?: return null
            val minor = core[1].toIntOrNull() ?: return null
            val patch = core[2].toIntOrNull() ?: return null
            val pre = if (releaseParts.size == 2) releaseParts[1].split('.') else emptyList()
            if (!pre.all { IDENTIFIER.matches(it) && (!it.all(Char::isDigit) || it == "0" || !it.startsWith('0')) }) return null
            return SemVer(major, minor, patch, pre)
        }
    }
}

data class VersionRange(val minInclusive: SemVer?, val maxExclusive: SemVer?) {
    init { require(minInclusive == null || maxExclusive == null || minInclusive < maxExclusive) }
    fun contains(version: SemVer): Boolean =
        (minInclusive == null || version >= minInclusive) && (maxExclusive == null || version < maxExclusive)
}
