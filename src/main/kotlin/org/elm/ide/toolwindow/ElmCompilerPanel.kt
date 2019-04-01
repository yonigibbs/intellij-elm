package org.elm.ide.toolwindow

import com.intellij.ide.CommonActionsManager
import com.intellij.ide.OccurenceNavigator
import com.intellij.ide.OccurenceNavigator.OccurenceInfo
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentManager
import com.intellij.ui.table.JBTable
import org.elm.openapiext.checkIsEventDispatchThread
import org.elm.openapiext.findFileByPathTestAware
import org.elm.openapiext.toPsiFile
import org.elm.workspace.compiler.ElmBuildAction
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.Region
import org.elm.workspace.compiler.Start
import java.awt.*
import java.awt.font.TextAttribute
import java.nio.file.Path
import javax.swing.*
import javax.swing.ScrollPaneConstants.*
import javax.swing.border.BevelBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.SoftBevelBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import kotlin.math.sign


class ElmCompilerPanel(
        private val project: Project,
        private val contentManager: ContentManager
) : SimpleToolWindowPanel(true, false), Disposable, OccurenceNavigator {

    var baseDirPath: Path? = null


    // UI

    override fun dispose() {}

    private val backgroundColorUI = Color(0x23, 0x31, 0x42)

    private val emptyErrorTable = DefaultTableModel(arrayOf<Array<String>>(arrayOf()), arrayOf())

    private val errorTableUI = JBTable().apply {
        setShowGrid(false)
        intercellSpacing = Dimension(2, 2)
        border = emptyBorder
        background = backgroundColorUI
        selectionBackground = Color(0x11, 0x51, 0x73)
        emptyText.text = ""
        model = emptyErrorTable
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = true
            override fun setAutoScrollMode(state: Boolean) {}
        }.install(this)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_INTERVAL_SELECTION
        selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && selectedRow >= 0) {
                val cellRect = getCellRect(selectedRow, 0, true)
                scrollRectToVisible(cellRect)
                if (compilerMessages.isNotEmpty()) {
                    indexCompilerMessages = selectedRow
                    messageUI.text = compilerMessages[indexCompilerMessages].html
                }
            }
        }
    }

    private val messageUI = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        background = backgroundColorUI
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }

    private var indexCompilerMessages: Int = 0

    private val noErrorContent = JBLabel()

    var compilerMessages: List<ElmError> = emptyList()
        set(value) {
            checkIsEventDispatchThread()
            field = value
            if (compilerMessages.isEmpty()) {
                setContent(noErrorContent)

                errorTableUI.model = emptyErrorTable
                messageUI.text = ""
            } else {
                setContent(errorContent)

                indexCompilerMessages = 0
                messageUI.text = compilerMessages[0].html

                val columnNames = arrayOf("Module", "Location", "Type")
                val cellValues = compilerMessages.map {
                    arrayOf(it.location?.moduleName ?: "n/a",
                            it.location?.region?.pretty() ?: "n/a",
                            toNiceName(it.title))
                }.toTypedArray()
                errorTableUI.model = object : DefaultTableModel(cellValues, columnNames) {
                    override fun isCellEditable(row: Int, column: Int) = false
                }
                errorTableUI.tableHeader.defaultRenderer = errorTableHeaderRenderer
                errorTableUI.setDefaultRenderer(errorTableUI.getColumnClass(0), errorTableCellRenderer)
                errorTableUI.setDefaultRenderer(errorTableUI.getColumnClass(1), errorTableCellRenderer)
                errorTableUI.setDefaultRenderer(errorTableUI.getColumnClass(2), errorTableCellRenderer)
            }
        }

    private val errorTableHeaderRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component =
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                        .apply { foreground = Color.WHITE }
    }

    private val errorTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            border = EmptyBorder(2, 2, 2, 2)
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    .apply {
                        foreground = Color.LIGHT_GRAY
                        if (column == 2) {
                            font = font.deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_BOLD))
                        }
                    }
        }
    }

    private fun toNiceName(title: String) =
            title.split(" ").joinToString(" ") { it.first() + it.substring(1).toLowerCase() }

    private fun Region.pretty() = "line ${start.line}, column ${start.column}"

    private var errorContent: JBSplitter

    private val emptyBorder = EmptyBorder(10, 10, 10, 10)

    private var mainText: JBTextField

    init {
        setToolbar(createToolbar())

        errorContent = JBSplitter("ElmCompilerErrorPanel", 0.4F)
        mainText = JBTextField("")
        mainText.alignmentX = Component.LEFT_ALIGNMENT
        mainText.isEditable = false
        mainText.border = SoftBevelBorder(BevelBorder.LOWERED)
        mainText.background = backgroundColorUI
        mainText.foreground = Color.LIGHT_GRAY

        val leftPane = JPanel()
        leftPane.layout = BoxLayout(leftPane, BoxLayout.Y_AXIS)

        val textPane = JPanel()
        textPane.layout = GridBagLayout()

        val gbc = GridBagConstraints()
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        textPane.add(mainText, gbc)
        textPane.alignmentX = Component.LEFT_ALIGNMENT
        leftPane.add(textPane)

        val jbScrollPaneErrors = JBScrollPane(errorTableUI, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER)

        jbScrollPaneErrors.alignmentX = Component.LEFT_ALIGNMENT
        leftPane.add(jbScrollPaneErrors)

        errorContent.firstComponent = leftPane
        errorContent.firstComponent.border = emptyBorder

        val jbScrollPaneMessage = JBScrollPane(messageUI, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED)
        errorContent.secondComponent = jbScrollPaneMessage
        errorContent.secondComponent.border = emptyBorder

        setContent(noErrorContent)

        with(project.messageBus.connect()) {
            subscribe(ElmBuildAction.ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, pathToCompile: String?) {
                    this@ElmCompilerPanel.baseDirPath = baseDirPath
                    compilerMessages = messages
                    contentManager.getContent(0)?.displayName = "${compilerMessages.size} errors"
                    errorTableUI.setRowSelectionInterval(0, 0)
                    indexCompilerMessages = 0
                    mainText.text = pathToCompile
                }
            })
        }
    }

    private fun createToolbar(): JComponent {
        val compilerPanel = this
        val toolbar = with(ActionManager.getInstance()) {
            val buttonGroup = DefaultActionGroup().apply {
                add(getAction("Elm.Build"))
                addSeparator()
                add(CommonActionsManager.getInstance().createNextOccurenceAction(compilerPanel))
                add(CommonActionsManager.getInstance().createPrevOccurenceAction(compilerPanel))
            }
            createActionToolbar("Elm Compiler Toolbar", buttonGroup, true)
        }
        toolbar.setTargetComponent(this)
        return toolbar.component
    }

    override fun getData(dataId: String): Any? {
        return when {
            CommonDataKeys.NAVIGATABLE.`is`(dataId) -> {
                val (virtualFile, _, start) = startFromErrorMessage() ?: return null
                return OpenFileDescriptor(project, virtualFile, start.line - 1, start.column - 1)
            }
            else ->
                super.getData(dataId)
        }
    }

    private fun startFromErrorMessage(): Triple<VirtualFile, Document, Start>? {
        val elmError = compilerMessages.getOrNull(indexCompilerMessages) ?: return null
        val elmLocation = elmError.location ?: return null
        val virtualFile = baseDirPath?.resolve(elmLocation.path)?.let { findFileByPathTestAware(it) } ?: return null
        val psiFile = virtualFile.toPsiFile(project) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val start = elmLocation.region?.start ?: return null
        return Triple(virtualFile, document, start)
    }


    // OCCURRENCE NAVIGATOR


    private fun calcNextOccurrence(direction: Int, go: Boolean = false): OccurenceInfo? {
        if (compilerMessages.isEmpty()) return null

        val nextIndex = indexCompilerMessages + direction.sign
        val elmError = compilerMessages.getOrNull(nextIndex) ?: return null

        if (go) {
            // update selection
            indexCompilerMessages = nextIndex
            messageUI.text = elmError.html
            errorTableUI.setRowSelectionInterval(indexCompilerMessages, indexCompilerMessages)
        }

        // create occurrence info
        val (virtualFile, document, start) = startFromErrorMessage() ?: return null
        val offset = document.getLineStartOffset(start.line - 1) + start.column - 1
        val navigatable = PsiNavigationSupport.getInstance().createNavigatable(project, virtualFile, offset)
        return OccurenceInfo(navigatable, -1, -1)
    }

    override fun getNextOccurenceActionName() = "Next Error"
    override fun hasNextOccurence() = calcNextOccurrence(1) != null
    override fun goNextOccurence(): OccurenceInfo? = calcNextOccurrence(1, go = true)

    override fun getPreviousOccurenceActionName() = "Previous Error"
    override fun hasPreviousOccurence() = calcNextOccurrence(-1) != null
    override fun goPreviousOccurence(): OccurenceInfo? = calcNextOccurrence(-1, go = true)
}