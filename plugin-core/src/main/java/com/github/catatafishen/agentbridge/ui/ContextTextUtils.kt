package com.github.catatafishen.agentbridge.ui

/**
 * Pure text-utility functions extracted from [PromptContextManager].
 *
 * All functions are stateless and free of IDE dependencies, making them
 * easy to unit-test independently.
 */
object ContextTextUtils {

    /** Unicode Object Replacement Character — placeholder for inline context chips. */
    private const val ORC = '￼'

    /**
     * Replace each ORC in [rawText] with a markdown link to the corresponding context item,
     * e.g. `[AuthLoginService.kt:116-170](file:///path/to/AuthLoginService.kt)`.
     *
     * Each file reference is placed on its own line: a newline is added before it
     * unless the preceding character is already `\n` or the ref is at the start,
     * and a newline is added after it unless the following character is already `\n`
     * or the ref is at the end. This ensures the reference is always isolated even
     * when the user edits away the newlines that [PromptContextManager.insertInlineChip]
     * inserted.
     */
    fun replaceOrcsWithTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText.replace(ORC.toString(), "").trim()
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                val item = items[idx++]
                val fileUrl = java.io.File(item.path).toURI().toString()
                val ref = "[${item.name}]($fileUrl)"
                val needNlBefore = sb.isNotEmpty() && sb.last() != '\n'
                // Don't append trailing newline yet — peek at the next raw char instead.
                // We compute needNlAfter lazily after building the full string.
                if (needNlBefore) sb.append('\n')
                sb.append(ref)
            } else {
                sb.append(ch)
            }
        }

        // Second pass: ensure each file reference is followed by a newline unless
        // already at end-of-string or followed by \n.
        // We match markdown links to find ref boundaries within the builder.
        val result = sb.toString()
        val refRegex = Regex("\\[[^]]+]\\([^)]+\\)")
        return refRegex.replace(result) { match ->
            val ref = match.value
            val afterIdx = match.range.last + 1
            val needNlAfter = afterIdx < result.length && result[afterIdx] != '\n'
            if (needNlAfter) "$ref\n" else ref
        }.trim()
    }

    /**
     * Replace each markdown file link back with an ORC character
     * corresponding to the context item at that index.
     */
    fun restoreOrcsFromTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText
        var text = rawText

        // 1. Regex match for markdown file links
        val regex = Regex("\\[([^\\]]+)\\]\\(file:/*([^\\)]+)\\)")
        val matches = regex.findAll(text).toList()

        val sb = StringBuilder()
        var lastIdx = 0
        for (match in matches) {
            sb.append(text.substring(lastIdx, match.range.first))
            val name = match.groupValues[1]
            val decodedPath = try {
                java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
            } catch (_: Exception) {
                match.groupValues[2]
            }

            var finalPath = decodedPath
            if (com.intellij.openapi.util.SystemInfo.isWindows && finalPath.startsWith("/")) {
                finalPath = finalPath.drop(1)
            }
            if (!finalPath.startsWith("/") && !finalPath.contains(":/")) {
                finalPath = "/$finalPath"
            }

            // Match item from items list
            val matchedItem = items.find { item ->
                val normItemPath = item.path.replace("\\", "/").trimEnd('/')
                val normFoundPath = finalPath.replace("\\", "/").trimEnd('/')
                normItemPath.equals(normFoundPath, ignoreCase = true) || item.name == name
            }

            if (matchedItem != null) {
                sb.append(ORC)
            } else {
                sb.append(match.value)
            }
            lastIdx = match.range.last + 1
        }
        sb.append(text.substring(lastIdx))
        text = sb.toString()

        // 2. Fallback for older entries using backticks
        for (item in items) {
            val legacyTarget = "`${item.name}`"
            val legacyIdx = text.indexOf(legacyTarget)
            if (legacyIdx >= 0) {
                text = text.substring(0, legacyIdx) + ORC + text.substring(legacyIdx + legacyTarget.length)
            }
        }

        return text
    }

    /**
     * Convert backtick-wrapped text references like `` `name` `` in [text]
     * into markdown links like `[name](file://path)`.
     */
    fun convertTextRefsToMarkdownLinks(text: String, contextFiles: List<ContextFileRef>): String {
        var result = text
        for (ref in contextFiles) {
            val target = "`${ref.name}`"
            val fileUrl = java.io.File(ref.path).toURI().toString()
            val markdownLink = "[${ref.name}]($fileUrl)"
            result = result.replace(target, markdownLink)
        }
        return result
    }

    /**
     * Parses markdown file links like `[name](file:/path)` from [text] and returns
     * a pair of:
     * 1. The parsed text where each markdown link is replaced by an ORC character.
     * 2. The list of [ContextItemData] corresponding to the parsed links.
     */
    fun parseMarkdownFileLinks(text: String): Pair<String, List<ContextItemData>> {
        val regex = Regex("\\[([^\\]]+)\\]\\(file:/*([^\\)]+)\\)")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val sb = StringBuilder()
        val items = mutableListOf<ContextItemData>()
        var lastIdx = 0

        for (match in matches) {
            sb.append(text.substring(lastIdx, match.range.first))
            val name = match.groupValues[1]
            val decodedPath = try {
                java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
            } catch (_: Exception) {
                match.groupValues[2]
            }

            // Clean path formatting for Windows/Linux
            var finalPath = decodedPath
            if (com.intellij.openapi.util.SystemInfo.isWindows && finalPath.startsWith("/")) {
                finalPath = finalPath.drop(1)
            }

            // Normalize UNIX path: if it doesn't start with "/" and is not Windows path, prepend "/"
            if (!finalPath.startsWith("/") && !finalPath.contains(":/")) {
                finalPath = "/$finalPath"
            }

            val virtualFile = com.intellij.openapi.application.runReadAction {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(finalPath)
            }

            if (virtualFile != null) {
                val (fileTypeName, lineCount) = com.intellij.openapi.application.runReadAction {
                    val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                        .getFileTypeByFileName(virtualFile.name)

                    val count = try {
                        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                            .getDocument(virtualFile)
                        doc?.lineCount ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    fileType.name to count
                }

                items.add(
                    ContextItemData(
                        path = finalPath,
                        name = name,
                        startLine = 1,
                        endLine = lineCount,
                        fileTypeName = fileTypeName,
                        isSelection = false
                    )
                )
                sb.append(ORC)
            } else {
                sb.append(match.value)
            }
            lastIdx = match.range.last + 1
        }
        sb.append(text.substring(lastIdx))
        return sb.toString() to items
    }

    /**
     * Compare two text snippets after normalizing tabs to spaces and
     * stripping trailing whitespace per line, so minor indentation
     * mismatches (partial first-line selection, mixed tabs/spaces) still match.
     */
    fun normalizedEquals(a: String, b: String, tabSize: Int): Boolean {
        if (a == b) return true
        val spaces = " ".repeat(tabSize.coerceAtLeast(1))
        val normA = a.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        val normB = b.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        return normA == normB
    }

    /**
     * Map an IntelliJ file-type name (lowercased) to the corresponding MIME type string.
     */
    fun getMimeTypeForFileType(fileTypeName: String?): String {
        return when (fileTypeName) {
            "java" -> "text/x-java"
            "kotlin" -> "text/x-kotlin"
            "python" -> "text/x-python"
            "javascript" -> "text/javascript"
            "typescript" -> "text/typescript"
            "xml", "html" -> "text/$fileTypeName"
            else -> "text/plain"
        }
    }
}
