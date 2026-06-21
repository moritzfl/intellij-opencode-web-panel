package de.moritzf.opencodewebpanel.toolWindow

import java.util.Collections

internal class OpenCodeServerLogBuffer(
    private val maxLines: Int = 500,
) {
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    fun text(): String {
        return synchronized(lines) {
            lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
        }
    }

    fun append(line: String) {
        synchronized(lines) {
            if (lines.size >= maxLines) {
                lines.removeAt(0)
            }
            lines.add(line)
        }
    }
}
