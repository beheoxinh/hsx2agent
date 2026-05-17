package com.github.catatafishen.agentbridge.settings

enum class SidePanelPosition {
    LEFT, RIGHT, TOP, BOTTOM;

    fun isVertical(): Boolean = this == TOP || this == BOTTOM
}
