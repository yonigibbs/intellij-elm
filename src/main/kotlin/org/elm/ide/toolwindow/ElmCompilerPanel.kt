package org.elm.ide.toolwindow

import com.intellij.ide.CommonActionsManager
import com.intellij.ide.OccurenceNavigator
import com.intellij.ide.OccurenceNavigator.OccurenceInfo
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.ActionLink
import com.intellij.ui.content.ContentManager
import com.intellij.ui.table.JBTable
import org.elm.openapiext.checkIsEventDispatchThread
import org.elm.openapiext.findFileByPath
import org.elm.openapiext.toPsiFile
import org.elm.workspace.compiler.ElmBuildAction
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.Region
import org.elm.workspace.compiler.Start
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.font.TextAttribute
import java.nio.file.Path
import javax.swing.*
import javax.swing.ScrollPaneConstants.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel


class ElmCompilerPanel(
        private val project: Project,
        private val contentManager: ContentManager
) : SimpleToolWindowPanel(true, false), Disposable, OccurenceNavigator {

    override fun dispose() {}

    private var baseDirPath: Path? = null

    private var selectedCompilerMessage: Int = 0

    var compilerMessages: List<ElmError> = emptyList()
        set(value) {
            checkIsEventDispatchThread()
            field = value
            selectedCompilerMessage = 0

            // update UI
            if (compilerMessages.isEmpty()) {
                setContent(emptyUI)
                errorTableUI.model = emptyErrorTable
                messageUI.text = ""
            } else {
                setContent(errorUI)
                messageUI.text = compilerMessages[0].html
                val cellValues = compilerMessages.map {
                    arrayOf(it.location?.moduleName ?: "n/a",
                            it.location?.region?.pretty() ?: "n/a",
                            toNiceName(it.title))
                }.toTypedArray()
                errorTableUI.model = object : DefaultTableModel(cellValues, errorTableColumnNames) {
                    override fun isCellEditable(row: Int, column: Int) = false
                }
                errorTableUI.setRowSelectionInterval(0, 0)
            }
        }

    private fun toNiceName(title: String) =
            title.split(" ").joinToString(" ") { it.first() + it.substring(1).toLowerCase() }

    private fun Region.pretty() = "${start.line} : ${start.column}"

    // LEFT PANEL
    private fun createCompilerTargetUI(baseDirPath: Path, targetPath: String?, offset: Int): ActionLink {
        return ActionLink("", object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                e.project?.let {
                    val targetFile = VfsUtil.findFile(baseDirPath.resolve(targetPath), true) ?: return
                    val descriptor = OpenFileDescriptor(it, targetFile, offset)
                    descriptor.navigate(true)
                }
            }
        }).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            setNormalColor(Color.BLACK)
            activeColor = Color.BLACK
            text = "Compiler Target  $targetPath"
        }
    }

    private val errorTableUI = JBTable().apply {
        setShowGrid(false)
        intercellSpacing = Dimension(2, 2)
        border = EmptyBorder(3, 3, 3, 3)
        background = backgroundColorUI
        selectionBackground = Color(0x11, 0x51, 0x73)
        emptyText.text = ""
        model = emptyErrorTable
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = true
            override fun setAutoScrollMode(state: Boolean) {}
        }.install(this)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tableHeader.defaultRenderer = errorTableHeaderRenderer
        setDefaultRenderer(Any::class.java, errorTableCellRenderer)
        selectionModel.addListSelectionListener { event ->
            event.let {
                if (!it.valueIsAdjusting && compilerMessages.isNotEmpty() && selectedRow >= 0) {
                    val cellRect = getCellRect(selectedRow, 0, true)
                    scrollRectToVisible(cellRect)
                    selectedCompilerMessage = selectedRow
                    messageUI.text = compilerMessages[selectedCompilerMessage].html
                }
            }
        }
    }

    // RIGHT PANEL
    private val messageUI = JTextPane().apply {
        contentType = "text/html"
        isEditable = false
        background = backgroundColorUI
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }

    // TOOLWINDOW CONTENT
    private val errorUI = JBSplitter("ElmCompilerErrorPanel", 0.4F).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(JBLabel()) // dummy-placeholder component at index 0 (gets replaced by org.elm.workspace.compiler.ElmBuildAction.ElmErrorsListener.update)
            add(JBScrollPane(errorTableUI, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
        }
        secondComponent = JBScrollPane(messageUI, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED)
    }

    init {
        setToolbar(createToolbar())
        setContent(emptyUI)

        with(project.messageBus.connect()) {
            subscribe(ElmBuildAction.ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String?, offset: Int) {
                    this@ElmCompilerPanel.baseDirPath = baseDirPath

                    compilerMessages = messages
                    selectedCompilerMessage = 0
                    errorTableUI.setRowSelectionInterval(0, 0)

                    contentManager.getContent(0)?.displayName = "${compilerMessages.size} errors"

                    val compilerTargetUI = createCompilerTargetUI(baseDirPath, targetPath, offset)
                    errorUI.firstComponent.remove(0)
                    errorUI.firstComponent.add(compilerTargetUI, BorderLayout.NORTH, 0)
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
        val elmError = compilerMessages.getOrNull(selectedCompilerMessage) ?: return null
        val elmLocation = elmError.location ?: return null
        val virtualFile = baseDirPath?.resolve(elmLocation.path)?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        } ?: return null
        val psiFile = virtualFile.toPsiFile(project) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val start = elmLocation.region?.start ?: return null
        return Triple(virtualFile, document, start)
    }


    // OCCURRENCE NAVIGATOR
    private fun calcNextOccurrence(direction: OccurenceDirection, go: Boolean = false): OccurenceInfo? {
        if (compilerMessages.isEmpty()) return null

        val nextIndex = when(direction) {
            is OccurenceDirection.Forward -> if (selectedCompilerMessage < compilerMessages.lastIndex)
                                                selectedCompilerMessage + 1
                                                else return null
            is OccurenceDirection.Back    -> if (selectedCompilerMessage > 0)
                                                selectedCompilerMessage - 1
                                                else return null
        }

        val elmError = compilerMessages.getOrNull(nextIndex) ?: return null

        if (go) {
            // update selection
            selectedCompilerMessage = nextIndex
            messageUI.text = elmError.html
            errorTableUI.setRowSelectionInterval(selectedCompilerMessage, selectedCompilerMessage)
        }

        // create occurrence info
        val (virtualFile, document, start) = startFromErrorMessage() ?: return null
        val offset = document.getLineStartOffset(start.line - 1) + start.column - 1
        val navigatable = PsiNavigationSupport.getInstance().createNavigatable(project, virtualFile, offset)
        return OccurenceInfo(navigatable, -1, -1)
    }

    override fun getNextOccurenceActionName() = "Next Error"
    override fun hasNextOccurence() = calcNextOccurrence(OccurenceDirection.Forward) != null
    override fun goNextOccurence(): OccurenceInfo? = calcNextOccurrence(OccurenceDirection.Forward, go = true)

    override fun getPreviousOccurenceActionName() = "Previous Error"
    override fun hasPreviousOccurence() = calcNextOccurrence(OccurenceDirection.Back) != null
    override fun goPreviousOccurence(): OccurenceInfo? = calcNextOccurrence(OccurenceDirection.Back, go = true)

    private companion object {

        sealed class OccurenceDirection {
            object Forward : OccurenceDirection()
            object Back : OccurenceDirection()
        }

        val backgroundColorUI = Color(0x23, 0x31, 0x42)

        val emptyErrorTable = DefaultTableModel(arrayOf<Array<String>>(emptyArray()), emptyArray())

        val errorTableColumnNames = arrayOf("Module", "Line : Column", "Type")

        val errorTableHeaderRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component =
                    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                            .apply { foreground = Color.WHITE }
        }
        val errorTableCellRenderer = object : DefaultTableCellRenderer() {
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

        val emptyUI = JBPanelWithEmptyText()
    }
}
