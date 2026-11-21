package de.moritzf.opencodewebpanel.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import de.moritzf.opencodewebpanel.server.SbxCli
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.EventObject
import java.util.concurrent.ConcurrentHashMap
import javax.swing.AbstractCellEditor
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellEditor

/**
 * A path cell that can be typed or picked. [browse] gets the cell's text and answers the text to
 * store, or null when the chooser was cancelled; a picked path is committed at once.
 */
internal class SbxBrowsePathCellEditor(
    placeholder: String,
    private val browse: (current: String) -> String?,
) : AbstractCellEditor(), TableCellEditor {
    private val pathField =
        TextFieldWithBrowseButton().apply {
            (textField as? JBTextField)?.emptyText?.text = placeholder
            addActionListener {
                val chosen = browse(text) ?: return@addActionListener
                text = chosen
                stopCellEditing()
            }
        }

    /** The text field, so a new row can be typed into right away. */
    val focusTarget: JComponent
        get() = pathField.textField

    // One click selects the row; editing takes a double click, F2 or typing.
    override fun isCellEditable(event: EventObject?): Boolean =
        event !is MouseEvent || event.clickCount >= 2

    override fun getTableCellEditorComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        row: Int,
        column: Int,
    ): Component {
        pathField.text = value as? String ?: ""
        return pathField
    }

    override fun getCellEditorValue(): Any = SbxCli.posixPath(pathField.text)
}

/** What a look at the disk said about a path cell. */
internal data class SbxPathState(val problem: String? = null, val file: Boolean = false)

/**
 * Looks at a path cell's target without touching the disk on the EDT: renderers run on every paint,
 * and a check of a network or automounted path can stall. Each text is checked once off the EDT,
 * then the table repaints.
 */
internal class SbxPathChecks(private val check: (text: String, workspace: String) -> SbxPathState) {
    private val results = ConcurrentHashMap<Pair<String, String>, SbxPathState>()
    private val running: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    /** Null until the check of this text has finished. */
    fun state(raw: String?, workspace: String?, repaint: JComponent): SbxPathState? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || workspace == null) return null
        val key = workspace to text
        results[key]?.let {
            return it
        }
        if (running.add(key)) {
            val application = ApplicationManager.getApplication()
            application.executeOnPooledThread {
                results[key] =
                    runCatching { check(text, workspace) }
                        .getOrElse { SbxPathState(it.message ?: "Cannot check this path.") }
                running.remove(key)
                application.invokeLater({ repaint.repaint() }, ModalityState.any())
            }
        }
        return null
    }

    /** After the disk changed under the form: a created kit, a saved descriptor. */
    fun clear() = results.clear()
}

/**
 * A path with what it is (icon) and, when a start would trip over it, why (inline and tooltip). A
 * path too long for its column loses its middle, which says less than either end.
 */
internal class SbxPathCellRenderer(
    private val state: (text: String, table: JTable) -> SbxPathState?,
    private val icon: (text: String, state: SbxPathState?) -> Icon?,
    private val placeholder: String? = null,
) : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ) {
        val text = (value as? String).orEmpty()
        if (text.isBlank()) {
            placeholder?.let { append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            return
        }
        val found = state(text, table)
        val trouble = found?.problem
        val shown = if (trouble != null) AllIcons.General.Warning else icon(text, found)
        setIcon(shown)
        // The head of the problem fits the cell; the tooltip has all of it.
        val aside = trouble?.substringBefore(": ")?.substringBefore(';')?.let { "  $it" }.orEmpty()
        val metrics = getFontMetrics(font)
        val room =
            table.columnModel.getColumn(column).width -
                (shown?.let { it.iconWidth + iconTextGap } ?: 0) -
                metrics.stringWidth(aside) -
                ipad.left -
                ipad.right -
                JBUI.scale(4)
        append(fitMiddle(text, room, metrics::stringWidth))
        if (aside.isNotEmpty()) append(aside, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = trouble ?: text
    }

    internal companion object {
        /** [text] with its middle replaced by an ellipsis until [width] says it fits [room]. */
        fun fitMiddle(text: String, room: Int, width: (String) -> Int): String {
            if (width(text) <= room) return text
            var keep = text.length - 1
            while (keep > 4) {
                val head = keep / 2
                val candidate = text.take(head) + "…" + text.takeLast(keep - head)
                if (width(candidate) <= room) return candidate
                keep--
            }
            return text.take(2) + "…" + text.takeLast(2)
        }
    }
}
