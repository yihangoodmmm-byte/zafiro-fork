package com.niki914.zafiro.app.workbench

import java.net.URI
import java.net.URISyntaxException

internal data class ProjectLogLocation(val path: String, val line: Int)

private val compilerLocation = Regex(
    """(?:^|[\s(])(?:"([^"\r\n]+\.[A-Za-z0-9]{1,12})"|'([^'\r\n]+\.[A-Za-z0-9]{1,12})'|([^\s"':()]+\.[A-Za-z0-9]{1,12}|file://[^\s"':()]+\.[A-Za-z0-9]{1,12})):(\d{1,9})(?=[:\s)]|$)""",
)

internal fun projectLogLocation(text: String): ProjectLogLocation? {
    for (match in compilerLocation.findAll(text.take(4096))) {
        val rawPath = (1..3).firstNotNullOfOrNull { match.groups[it]?.value } ?: continue
        val path = if (rawPath.startsWith("file://")) localFileUriPath(rawPath) ?: continue else rawPath
        if (path.contains("://") || path.any(Char::isISOControl)) continue
        val line = match.groupValues[4].toIntOrNull()?.takeIf { it > 0 } ?: continue
        return ProjectLogLocation(path, line)
    }
    return null
}

private fun localFileUriPath(value: String): String? = try {
    val uri = URI(value)
    uri.path?.takeIf { uri.authority == null && uri.query == null && uri.fragment == null && it.startsWith('/') }
} catch (_: URISyntaxException) {
    null
}
