package de.moritzf.opencodewebpanel.features

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** Read plain text, never the HTML/RTF that rich clipboard owners often advertise first. */
internal object OpenCodeClipboardText {
    const val MAX_CHARS = 2_000_000

    fun supports(transferable: Transferable): Boolean = flavors(transferable).isNotEmpty()

    fun read(transferable: Transferable): String? {
        for (flavor in flavors(transferable)) {
            val text = runCatching {
                flavor.getReaderForText(transferable).use { reader ->
                    val result = StringBuilder()
                    val chunk = CharArray(8_192)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        if (result.length + count > MAX_CHARS) return null
                        result.append(chunk, 0, count)
                    }
                    result.toString()
                }
            }.getOrNull()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun flavors(transferable: Transferable): List<DataFlavor> = runCatching {
        transferable.transferDataFlavors.filter {
            it == DataFlavor.stringFlavor || (it.isFlavorTextType && it.isMimeTypeEqual("text/plain"))
        }.sortedBy { if (it == DataFlavor.stringFlavor) 0 else 1 }
    }.getOrDefault(emptyList())
}
