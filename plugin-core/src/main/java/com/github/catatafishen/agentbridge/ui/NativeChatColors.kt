package com.github.catatafishen.agentbridge.ui

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Color palette for the native Swing chat panel.
 *
 * Values are derived from the JCEF chat.css design tokens. Accent colors use
 * alpha compositing so they blend naturally with both light and dark IDE themes.
 */
object NativeChatColors {
    val THINK: JBColor = JBColor(Gray._100, Gray._176)

    val USER_BUBBLE_BG = JBColor(Color(86, 156, 214, 25), Color(86, 156, 214, 31))
    val AGENT_BUBBLE_BG = JBColor(Color(150, 200, 150, 12), Color(150, 200, 150, 15))

    val THINK_BG = JBColor(Color(128, 128, 128, 15), Color(128, 128, 128, 20))
    val THINK_BORDER = JBColor(Color(128, 128, 128, 30), Color(128, 128, 128, 41))

    val ERROR = JBColor(Color(204, 0, 0), Color(255, 107, 107))
    val ERROR_BG = JBColor(Color(199, 34, 34, 15), Color(199, 34, 34, 20))

    private val KIND_READ = JBColor(Color(150, 150, 150), Color(200, 200, 200))
    private val KIND_EDIT = JBColor(Color(175, 125, 65), Color(205, 155, 95))
    private val KIND_WRITE = JBColor(Color(150, 150, 30), Color(200, 200, 50))
    private val KIND_EXECUTE = JBColor(Color(180, 100, 15), Color(225, 125, 25))
    private val KIND_DESTRUCTIVE = JBColor(Color(180, 20, 40), Color(225, 25, 50))
    private val KIND_SEARCH = JBColor(Color(35, 150, 150), Color(50, 200, 200))
    private val KIND_THINK = JBColor(Color(150, 75, 150), Color(200, 100, 200))
    private val KIND_OTHER = JBColor(Color(130, 135, 140), Color(160, 165, 170))

    val RING_RUNNING = JBColor(Color(180, 100, 100), Color(225, 125, 125))
    val RING_PENDING = JBColor(Color(160, 160, 40), Color(200, 200, 50))
    val RING_COMPLETE = JBColor(Color(50, 160, 50), Color(80, 200, 80))
    val RING_FAILED = JBColor(Color(180, 40, 40), Color(225, 50, 50))
    val RING_DENIED = JBColor(Color(110, 110, 110), Color(140, 140, 140))
    val RING_THINKING = JBColor(Color(70, 110, 160), Color(100, 150, 200))

    fun kindColor(kind: String?): Color = when (kind?.lowercase()) {
        "read" -> KIND_READ
        "edit", "move" -> KIND_EDIT
        "write" -> KIND_WRITE
        "execute" -> KIND_EXECUTE
        "delete" -> KIND_DESTRUCTIVE
        "search" -> KIND_SEARCH
        "think" -> KIND_THINK
        else -> KIND_OTHER
    }

    fun ringColor(status: String?): Color = when (status?.lowercase()) {
        "running" -> RING_RUNNING
        "pending" -> RING_PENDING
        "complete", "completed", "success", "done" -> RING_COMPLETE
        "failed", "error" -> RING_FAILED
        "denied" -> RING_DENIED
        "thinking" -> RING_THINKING
        else -> RING_RUNNING
    }

    /** 10% alpha background derived from the kind's accent color. */
    fun kindBg(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 26), Color(base.red, base.green, base.blue, 26))
    }

    /** 22% alpha border derived from the kind's accent color. */
    fun kindBorder(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 56), Color(base.red, base.green, base.blue, 56))
    }

    /** 18% alpha hover background derived from the kind's accent color. */
    fun kindBgHover(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 46), Color(base.red, base.green, base.blue, 46))
    }

    /** 32% alpha hover border derived from the kind's accent color. */
    fun kindBorderHover(kind: String?): JBColor {
        val base = kindColor(kind)
        return JBColor(Color(base.red, base.green, base.blue, 82), Color(base.red, base.green, base.blue, 82))
    }

    val NUDGE_BG: JBColor = JBColor(Color(0xF0, 0xF0, 0xFF), Color(0x2A, 0x2A, 0x3A))
    val NUDGE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xE8), Color(0x40, 0x40, 0x60))
    val NUDGE_FG: JBColor = JBColor(Color(0x50, 0x50, 0x70), Color(0xB0, 0xB0, 0xD0))
    val QUEUED_BG: JBColor = JBColor(Color(0xF0, 0xFF, 0xF0), Color(0x2A, 0x30, 0x2A))
    val QUEUED_BORDER: JBColor = JBColor(Color(0xD0, 0xE8, 0xD0), Color(0x40, 0x60, 0x40))
    val QUEUED_FG: JBColor = JBColor(Color(0x50, 0x70, 0x50), Color(0xB0, 0xD0, 0xB0))

    val CODE_BG: JBColor = JBColor(Color(0xEB, 0xEB, 0xEB), Color(0x33, 0x33, 0x33))
    val TABLE_BORDER: JBColor = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x44, 0x44, 0x44))
    val LINK: JBColor = JBColor(Color(0x24, 0x70, 0xB3), Color(0x58, 0x9D, 0xF6))
}
