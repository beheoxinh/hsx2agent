package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.McpServerSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Resolves semantic tool-kind colors, honoring per-project user overrides stored in
 * [McpServerSettings]. Falls back to the defaults that match the chat panel CSS palette
 * in [ChatTheme].
 *
 * These colors are used in:
 * - Quick Permissions combo-box tints in [PermissionsPanel]
 * - Tool kind color accents in [com.github.catatafishen.agentbridge.settings.ToolsConfigurable]
 * - CSS `--kind-*` variables in [ChatTheme.buildCssVars]
 */
object ToolKindColors {

    // Defaults match ChatTheme.KIND_*_COLOR values.
    @JvmField
    val DEFAULT_READ: JBColor = JBColor(Color(0x96, 0x96, 0x96), Color(200, 200, 200))

    @JvmField
    val DEFAULT_SEARCH: JBColor = JBColor(Color(0x1E, 0x96, 0x96), Color(50, 200, 200))

    @JvmField
    val DEFAULT_EDIT: JBColor = JBColor(Color(0xA0, 0x7A, 0x3A), Color(205, 155, 95))

    @JvmField
    val DEFAULT_WRITE: JBColor = JBColor(Color(0x96, 0x96, 0x1E), Color(200, 200, 50))

    @JvmField
    val DEFAULT_EXECUTE: JBColor = JBColor(Color(0xC8, 0x64, 0x0F), Color(225, 125, 25))

    @JvmField
    val DEFAULT_DESTRUCTIVE: JBColor = JBColor(Color(0xC8, 0x14, 0x28), Color(225, 25, 50))

    @JvmField
    val DEFAULT_THINK: JBColor = JBColor(Color(0x96, 0x46, 0x96), Color(200, 100, 200))

    @JvmStatic
    fun readColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindReadColorKey)?.color ?: DEFAULT_READ

    @JvmStatic
    fun searchColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindSearchColorKey)?.color ?: DEFAULT_SEARCH

    @JvmStatic
    fun editColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindEditColorKey)?.color ?: DEFAULT_EDIT

    @JvmStatic
    fun writeColor(settings: McpServerSettings?): JBColor = DEFAULT_WRITE

    @JvmStatic
    fun executeColor(settings: McpServerSettings?): JBColor =
        ThemeColor.fromKey(settings?.kindExecuteColorKey)?.color ?: DEFAULT_EXECUTE

    @JvmStatic
    fun destructiveColor(settings: McpServerSettings?): JBColor = DEFAULT_DESTRUCTIVE

    @JvmStatic
    fun thinkColor(settings: McpServerSettings?): JBColor = DEFAULT_THINK

    /**
     * Returns a tinted background by blending [alpha] proportion of [color] into the
     * panel background. Alpha 0.22 gives a clear but not overpowering tint.
     */
    @JvmStatic
    @JvmOverloads
    fun tintedBackground(color: Color, alpha: Double = 0.22): Color {
        val base = UIUtil.getPanelBackground()
        return Color(
            ((color.red * alpha + base.red * (1 - alpha)).toInt()).coerceIn(0, 255),
            ((color.green * alpha + base.green * (1 - alpha)).toInt()).coerceIn(0, 255),
            ((color.blue * alpha + base.blue * (1 - alpha)).toInt()).coerceIn(0, 255),
        )
    }

    /** Encodes a [Color] to a lowercase hex string (e.g. `"#3a9595"`). */
    @JvmStatic
    fun toHex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)
}
