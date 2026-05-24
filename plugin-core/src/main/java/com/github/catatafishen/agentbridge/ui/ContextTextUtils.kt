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
     */
    fun replaceOrcsWithTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText.replace(ORC.toString(), "").trim()
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                val item = items[idx++]
                val fileUrl = java.io.File(item.path).toURI().toString()
                sb.append('[').append(item.name).append("](").append(fileUrl).append(')')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Replace each markdown file link back with an ORC character
     * corresponding to the context item at that index.
     */
    fun restoreOrcsFromTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText
        var text = rawText
        for (item in items) {
            val fileUrl = java.io.File(item.path).toURI().toString()
            val target = "[${item.name}]($fileUrl)"
            val idx = text.indexOf(target)
            if (idx >= 0) {
                text = text.substring(0, idx) + ORC + text.substring(idx + target.length)
            } else {
                // Fallback for older entries using backticks or file URL variations
                val legacyTarget = "`${item.name}`"
                val legacyIdx = text.indexOf(legacyTarget)
                if (legacyIdx >= 0) {
                    text = text.substring(0, legacyIdx) + ORC + text.substring(legacyIdx + legacyTarget.length)
                }
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

            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(finalPath)

            if (virtualFile != null) {
                val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByFileName(virtualFile.name)

                val lineCount = try {
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(virtualFile)
                    doc?.lineCount ?: 0
                } catch (_: Exception) {
                    0
                }

                items.add(
                    ContextItemData(
                        path = finalPath,
                        name = name,
                        startLine = 1,
                        endLine = lineCount,
                        fileTypeName = fileType.name,
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
