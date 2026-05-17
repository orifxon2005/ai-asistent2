package com.example.aiasistent2

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null

        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun clickText(text: String): Boolean {
        val node = findByText(text) ?: return false
        return clickNode(node)
    }

    fun typeIntoFocused(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(rootInActiveWindow) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun appendIntoFocused(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(rootInActiveWindow) ?: return false
        val current = focused.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressImeAction(): Boolean {
        return clickText("Send")
            || clickText("Yuborish")
            || clickText("Jo'natish")
            || appendIntoFocused("\n")
    }

    fun clickFirstEditable(): Boolean {
        val node = findEditable(rootInActiveWindow) ?: return false
        return clickNode(node) || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    fun scrollForward(): Boolean {
        val node = findScrollable(rootInActiveWindow) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollBackward(): Boolean {
        val node = findScrollable(rootInActiveWindow) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun screenText(limit: Int = 3500): String {
        val out = StringBuilder()
        collectText(rootInActiveWindow, out, limit)
        return out.toString().trim()
    }

    private fun findByText(text: String): AccessibilityNodeInfo? {
        val query = text.trim()
        if (query.isBlank()) return null

        val root = rootInActiveWindow ?: return null
        val exact = root.findAccessibilityNodeInfosByText(query)
            .firstOrNull { it.isVisibleToUser && textMatches(it, query) }
        if (exact != null) return exact

        return root.findAccessibilityNodeInfosByText(query)
            .firstOrNull { it.isVisibleToUser }
    }

    private fun textMatches(node: AccessibilityNodeInfo, query: String): Boolean {
        val haystack = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .lowercase()
        return haystack.contains(query.lowercase())
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, limit: Int) {
        if (node == null || out.length >= limit) return
        val value = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .trim()
        if (value.isNotBlank()) {
            out.append(value).append('\n')
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, limit)
            if (out.length >= limit) return
        }
    }
}
