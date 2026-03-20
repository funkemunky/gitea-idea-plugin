/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.GiteaPullRequestFullDetails
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.github.leondevlifelog.gitea.services.GiteaPullRequestService
import com.github.leondevlifelog.gitea.services.GiteaReviewDraftComment
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.branch.GitBrancher
import org.gitnex.tea4j.v2.models.ChangedFile
import org.gitnex.tea4j.v2.models.PullRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

class GiteaPullRequestReviewDialog(
    private val context: GiteaPullRequestRepositoryContext,
    private val repositoryDetails: GiteaPullRequestRepositoryDetails,
    initialPullRequest: PullRequest,
    private val service: GiteaPullRequestService,
    private val onPullRequestUpdated: () -> Unit
) : DialogWrapper(context.gitRepository.project, true) {

    private val titleLabel = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D + 2f)
    }
    private val subtitleLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val descriptionPane = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        border = null
    }
    private val statusLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val refreshButton = JButton(GiteaBundle.message("pull.request.details.refresh"))
    private val openBrowserButton = JButton(GiteaBundle.message("pull.request.details.open.browser"))
    private val openNativeDiffButton = JButton(GiteaBundle.message("pull.request.details.open.native.diff"))
    private val reviewSelectionButton = JButton(GiteaBundle.message("pull.request.review.selection.review"))

    private val filesModel = DefaultListModel<ChangedFile>()
    private val filesList = JBList(filesModel)

    private val diffDocument = EditorFactory.getInstance().createDocument("")
    private val diffEditor = EditorFactory.getInstance()
        .createViewer(diffDocument, context.gitRepository.project) as EditorEx

    @Volatile
    private var currentPullRequest: PullRequest = initialPullRequest
    @Volatile
    private var currentDetails: GiteaPullRequestFullDetails? = null
    @Volatile
    private var currentDiff: UnifiedDiffData? = null

    private var reviewPopup: JBPopup? = null
    private val diffVersion = AtomicInteger()

    private val addedAttrs = TextAttributes().apply {
        backgroundColor = JBColor(Color(230, 244, 233), Color(45, 70, 49))
    }
    private val removedAttrs = TextAttributes().apply {
        backgroundColor = JBColor(Color(248, 230, 230), Color(84, 48, 48))
    }
    private val hunkAttrs = TextAttributes().apply {
        foregroundColor = JBColor(Color(86, 94, 105), Color(152, 160, 174))
        backgroundColor = JBColor(Color(237, 242, 248), Color(48, 53, 61))
        fontType = Font.BOLD
    }

    init {
        title = GiteaBundle.message("pull.request.details.title", initialPullRequest.number ?: 0L)
        setModal(false)
        initDiffEditor()
        init()
        configureUi()
        refreshDetails()
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(1420, 860)
            border = JBUI.Borders.empty(8)
            add(buildHeader(), BorderLayout.NORTH)
            add(buildBody(), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        reviewPopup?.cancel()
        if (!diffEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(diffEditor)
        }
        super.dispose()
    }

    private fun initDiffEditor() {
        diffEditor.settings.isLineNumbersShown = true
        diffEditor.settings.isFoldingOutlineShown = false
        diffEditor.settings.isIndentGuidesShown = false
        diffEditor.settings.additionalColumnsCount = 2
        diffEditor.settings.additionalLinesCount = 1
        diffDocument.setReadOnly(true)
    }

    private fun buildHeader(): JComponent {
        val buttons = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
            isOpaque = false
            add(refreshButton)
            add(openBrowserButton)
            add(openNativeDiffButton)
            add(reviewSelectionButton)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel)
            add(subtitleLabel)
            add(descriptionPane)
            add(buttons)
        }
    }

    private fun buildBody(): JComponent {
        val left = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineRight(JBColor.border())
            add(JBLabel(GiteaBundle.message("pull.request.details.tab.files")).apply {
                border = JBUI.Borders.empty(0, 4, 4, 4)
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            }, BorderLayout.NORTH)
            add(JBScrollPane(filesList), BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout()).apply {
            add(JBLabel(GiteaBundle.message("pull.request.review.diff.instructions")).apply {
                border = JBUI.Borders.empty(0, 8, 6, 0)
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.NORTH)
            add(diffEditor.component, BorderLayout.CENTER)
        }
        return JBSplitter(false, 0.28f).apply {
            firstComponent = left
            secondComponent = right
        }
    }

    private fun configureUi() {
        filesList.cellRenderer = FileRenderer()
        filesList.addListSelectionListener {
            if (!it.valueIsAdjusting) showSelectedFileDiff()
        }
        refreshButton.addActionListener { refreshDetails() }
        openBrowserButton.addActionListener {
            currentPullRequest.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
        }
        openNativeDiffButton.addActionListener { openNativeBranchCompare() }
        reviewSelectionButton.addActionListener { openSelectionReviewPopup() }
        diffEditor.selectionModel.addSelectionListener(object : com.intellij.openapi.editor.event.SelectionListener {
            override fun selectionChanged(e: com.intellij.openapi.editor.event.SelectionEvent) {
                updateSelectionState()
            }
        })
        diffEditor.contentComponent.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowDiffPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowDiffPopup(e)
        })
        updateSelectionState()
        showDiffPlaceholder(GiteaBundle.message("pull.request.details.diff.select"))
    }

    private fun refreshDetails() {
        val number = currentPullRequest.number ?: return
        runBackground(
            GiteaBundle.message("pull.request.details.loading"),
            onSuccess = { details ->
                currentDetails = details
                currentPullRequest = details.pullRequest
                renderDetails(details)
            },
            task = { service.getPullRequestDetails(context, number) }
        )
    }

    private fun renderDetails(details: GiteaPullRequestFullDetails) {
        val pr = details.pullRequest
        titleLabel.text = "#${pr.number ?: 0L} ${pr.title.orEmpty()}"
        subtitleLabel.text = "${(pr.state ?: "open").uppercase(Locale.getDefault())}   ${pr.head?.ref ?: "-"} -> ${pr.base?.ref ?: "-"}   ${details.files.size} files"
        descriptionPane.text = toHtml(pr.body?.takeIf { it.isNotBlank() } ?: GiteaBundle.message("pull.request.details.no.description"))
        filesModel.clear()
        details.files.forEach(filesModel::addElement)
        if (filesModel.size() > 0) filesList.selectedIndex = 0
    }

    private fun showSelectedFileDiff() {
        val details = currentDetails ?: return
        val filePath = filesList.selectedValue?.filename ?: return showDiffPlaceholder(GiteaBundle.message("pull.request.details.diff.select"))
        val baseRef = details.pullRequest.base?.ref ?: return showDiffPlaceholder(GiteaBundle.message("pull.request.details.diff.unavailable"))
        val headRef = details.pullRequest.head?.ref ?: return showDiffPlaceholder(GiteaBundle.message("pull.request.details.diff.unavailable"))
        val version = diffVersion.incrementAndGet()
        showDiffPlaceholder(GiteaBundle.message("pull.request.diff.loading.file", filePath))
        AppExecutorUtil.getAppExecutorService().submit {
            val text = loadUnifiedDiff(filePath, baseRef, headRef)
            val parsed = parseUnifiedDiff(filePath, text)
            SwingUtilities.invokeLater {
                if (version != diffVersion.get()) return@invokeLater
                renderDiff(parsed)
            }
        }
    }

    private fun renderDiff(data: UnifiedDiffData) {
        currentDiff = data
        ApplicationManager.getApplication().runWriteAction {
            diffDocument.setReadOnly(false)
            diffDocument.setText(data.text)
            diffDocument.setReadOnly(true)
        }
        val markup = diffEditor.markupModel
        markup.removeAllHighlighters()
        data.lines.forEachIndexed { idx, line ->
            val attrs = when (line.kind) {
                DiffLineKind.ADDED -> addedAttrs
                DiffLineKind.REMOVED -> removedAttrs
                DiffLineKind.HUNK -> hunkAttrs
                else -> null
            }
            if (attrs != null) markup.addLineHighlighter(idx, HighlighterLayer.CARET_ROW - 1, attrs)
        }
        diffEditor.selectionModel.removeSelection()
        updateSelectionState()
    }

    private fun showDiffPlaceholder(text: String) {
        renderDiff(UnifiedDiffData(text, listOf(DiffLine(DiffLineKind.OTHER, null, null, null))))
    }

    private fun updateSelectionState() {
        val selection = currentSelection()
        reviewSelectionButton.isEnabled = selection != null
        statusLabel.text = if (selection == null) {
            GiteaBundle.message("pull.request.review.selection.none")
        } else {
            val range = when {
                selection.newStartLine != null && selection.newEndLine != null ->
                    "new L${selection.newStartLine}-${selection.newEndLine}"
                selection.oldStartLine != null && selection.oldEndLine != null ->
                    "old L${selection.oldStartLine}-${selection.oldEndLine}"
                else -> "selection"
            }
            GiteaBundle.message("pull.request.review.selection.ready", selection.path, range)
        }
    }

    private fun maybeShowDiffPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val selection = currentSelection()
        val menu = JPopupMenu()
        val item = JMenuItem(GiteaBundle.message("pull.request.review.selection.review"))
        item.isEnabled = selection != null
        item.addActionListener { if (selection != null) openSelectionReviewPopup(selection) }
        menu.add(item)
        menu.show(diffEditor.contentComponent, event.x, event.y)
    }

    private fun openSelectionReviewPopup() {
        val selection = currentSelection()
        if (selection == null) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.review.selection.none")
            )
            return
        }
        openSelectionReviewPopup(selection)
    }

    private fun openSelectionReviewPopup(selection: DiffSelection) {
        reviewPopup?.cancel()
        val input = JBTextArea(6, 70).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
        }
        val selectedDiffPreview = JBTextArea(8, 70).apply {
            text = selection.selectedDiffPreview
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            border = JBUI.Borders.empty(6)
            font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
            background = JBColor(Color(248, 250, 253), Color(40, 43, 48))
        }
        val lineInfo = when {
            selection.newStartLine != null && selection.newEndLine != null ->
                "new L${selection.newStartLine}-${selection.newEndLine}"
            selection.oldStartLine != null && selection.oldEndLine != null ->
                "old L${selection.oldStartLine}-${selection.oldEndLine}"
            else -> "-"
        }
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(780, 430)
            border = JBUI.Borders.empty(8)
            add(JBLabel(GiteaBundle.message("pull.request.review.popup.title", selection.path, lineInfo)).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                border = JBUI.Borders.emptyBottom(6)
            }, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JPanel(BorderLayout()).apply {
                            border = JBUI.Borders.emptyBottom(8)
                            add(
                                JBLabel(GiteaBundle.message("pull.request.review.popup.selected.diff")).apply {
                                    border = JBUI.Borders.emptyBottom(4)
                                    foreground = UIUtil.getContextHelpForeground()
                                },
                                BorderLayout.NORTH
                            )
                            add(JBScrollPane(selectedDiffPreview).apply {
                                preferredSize = Dimension(740, 160)
                            }, BorderLayout.CENTER)
                        },
                        BorderLayout.NORTH
                    )
                    add(
                        JPanel(BorderLayout()).apply {
                            add(
                                JBLabel(GiteaBundle.message("pull.request.review.popup.comment.prompt")).apply {
                                    border = JBUI.Borders.emptyBottom(4)
                                    foreground = UIUtil.getContextHelpForeground()
                                },
                                BorderLayout.NORTH
                            )
                            add(JBScrollPane(input), BorderLayout.CENTER)
                        },
                        BorderLayout.CENTER
                    )
                },
                BorderLayout.CENTER
            )
            add(JPanel().apply {
                layout = java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)
                val addCommentButton = JButton(GiteaBundle.message("pull.request.review.popup.add.comment"))
                val requestChangesButton = JButton(GiteaBundle.message("pull.request.review.popup.request.changes"))
                val cancelButton = JButton("Cancel")
                addCommentButton.addActionListener {
                    val text = input.text.trim()
                    if (text.isBlank()) {
                        GiteaNotifications.showWarning(
                            context.gitRepository.project,
                            null,
                            GiteaBundle.message("pull.request.error.title"),
                            GiteaBundle.message("pull.request.review.comment.required")
                        )
                        return@addActionListener
                    }
                    submitSelectionComment(selection, text)
                }
                requestChangesButton.addActionListener {
                    submitSelectionRequestChanges(selection, input.text.trim())
                }
                cancelButton.addActionListener { reviewPopup?.cancel() }
                add(addCommentButton)
                add(requestChangesButton)
                add(cancelButton)
            }, BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, input)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        reviewPopup = popup
        val endOffset = diffEditor.selectionModel.selectionEnd
        val visualPos = diffEditor.offsetToVisualPosition(endOffset)
        val xy = diffEditor.visualPositionToXY(visualPos)
        popup.show(RelativePoint(diffEditor.contentComponent, Point((xy.x + 18).coerceAtLeast(12), xy.y + diffEditor.lineHeight + 8)))
    }

    private fun submitSelectionComment(selection: DiffSelection, text: String) {
        val number = currentPullRequest.number ?: return
        reviewPopup?.cancel()
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = {
                onPullRequestUpdated()
                refreshDetails()
            },
            task = {
                service.submitReview(
                    context = context,
                    number = number,
                    event = "COMMENT",
                    bodyText = "",
                    lineComments = listOf(
                        GiteaReviewDraftComment(
                            path = selection.path,
                            position = selection.anchorLine,
                            side = selection.anchorSide,
                            body = text
                        )
                    )
                )
            }
        )
    }

    private fun submitSelectionRequestChanges(selection: DiffSelection, introText: String) {
        val details = currentDetails ?: return
        val number = currentPullRequest.number ?: return
        val headRef = details.pullRequest.head?.ref ?: return
        val start = selection.newStartLine
        val end = selection.newEndLine
        if (start == null || end == null) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.review.request.changes.new.lines.required")
            )
            return
        }
        val original = loadFileAtRef(listOf(headRef, "origin/$headRef"), selection.path)
        if (original == null) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.review.request.changes.file.unavailable")
            )
            return
        }

        val dialog = SuggestedChangeDialog(
            project = context.gitRepository.project,
            filePath = selection.path,
            initialEditableContent = extractLineRange(original, start, end),
            startLine = start,
            endLine = end,
            selectedDiffPreview = selection.selectedDiffPreview,
            surroundingContextPreview = buildFileContextPreview(original, start, end)
        )
        if (!dialog.showAndGet()) return
        val originalSelected = extractLineRange(original, start, end).replace("\r\n", "\n")
        val suggestion = dialog.editedText.replace("\r\n", "\n")
        if (suggestion.isBlank()) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.review.request.changes.empty.suggestion")
            )
            return
        }
        if (suggestion.trimEnd('\n', '\r') == originalSelected.trimEnd('\n', '\r')) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.review.request.changes.noop")
            )
            return
        }

        val intro = introText.ifBlank { GiteaBundle.message("pull.request.review.request.changes.default.message") }
        val body = "$intro\n\n```suggestion\n$suggestion\n```"
        reviewPopup?.cancel()
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = {
                onPullRequestUpdated()
                refreshDetails()
            },
            task = {
                service.submitReview(
                    context = context,
                    number = number,
                    event = "REQUEST_CHANGES",
                    bodyText = "",
                    lineComments = listOf(
                        GiteaReviewDraftComment(
                            path = selection.path,
                            position = end,
                            side = GiteaReviewDraftComment.Side.NEW,
                            body = body
                        )
                    )
                )
            }
        )
    }

    private fun currentSelection(): DiffSelection? {
        val data = currentDiff ?: return null
        val selectionModel = diffEditor.selectionModel
        if (!selectionModel.hasSelection()) return null
        val startOffset = minOf(selectionModel.selectionStart, selectionModel.selectionEnd)
        val endOffset = maxOf(selectionModel.selectionStart, selectionModel.selectionEnd)
        if (startOffset == endOffset) return null

        val startLine = diffDocument.getLineNumber(startOffset)
        val endLine = diffDocument.getLineNumber((endOffset - 1).coerceAtLeast(startOffset))
        val selectedLines = (startLine..endLine).mapNotNull { index ->
            data.lines.getOrNull(index)
        }.filter { line ->
            line.kind == DiffLineKind.ADDED || line.kind == DiffLineKind.REMOVED || line.kind == DiffLineKind.CONTEXT
        }
        if (selectedLines.isEmpty()) return null
        val selectedDiffPreview = buildSelectedDiffPreview(startLine, endLine)

        val path = selectedLines.first().path ?: filesList.selectedValue?.filename ?: return null
        val newLines = selectedLines.mapNotNull { it.newLine }
        val oldLines = selectedLines.mapNotNull { it.oldLine }
        val side: GiteaReviewDraftComment.Side
        val anchor: Int
        if (newLines.isNotEmpty()) {
            side = GiteaReviewDraftComment.Side.NEW
            anchor = newLines.maxOrNull() ?: return null
        } else {
            side = GiteaReviewDraftComment.Side.OLD
            anchor = oldLines.maxOrNull() ?: return null
        }
        return DiffSelection(
            path = path,
            anchorSide = side,
            anchorLine = anchor,
            newStartLine = newLines.minOrNull(),
            newEndLine = newLines.maxOrNull(),
            oldStartLine = oldLines.minOrNull(),
            oldEndLine = oldLines.maxOrNull(),
            selectedDiffPreview = selectedDiffPreview
        )
    }

    private fun buildSelectedDiffPreview(startLine: Int, endLine: Int): String {
        if (startLine > endLine) return ""
        val maxLines = 140
        val lastLine = diffDocument.lineCount - 1
        val boundedStart = startLine.coerceIn(0, lastLine)
        val boundedEnd = endLine.coerceIn(boundedStart, lastLine)
        val rendered = mutableListOf<String>()
        for (line in boundedStart..boundedEnd) {
            if (rendered.size >= maxLines) break
            val lineStart = diffDocument.getLineStartOffset(line)
            val lineEnd = diffDocument.getLineEndOffset(line)
            rendered += diffDocument.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
        }
        if (boundedEnd - boundedStart + 1 > maxLines) {
            rendered += "..."
        }
        return rendered.joinToString("\n")
    }

    private fun parseUnifiedDiff(path: String, text: String): UnifiedDiffData {
        val normalized = text.replace("\r\n", "\n")
        if (normalized.isBlank()) {
            return UnifiedDiffData(
                GiteaBundle.message("pull.request.review.diff.empty"),
                listOf(DiffLine(DiffLineKind.OTHER, null, null, path))
            )
        }
        val hunkRegex = Regex("^@@\\s+-(\\d+)(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@.*$")
        var oldLine: Int? = null
        var newLine: Int? = null
        var inHunk = false
        val parsed = mutableListOf<DiffLine>()
        normalized.split('\n').forEach { line ->
            when {
                line.startsWith("@@") -> {
                    val m = hunkRegex.matchEntire(line)
                    if (m != null) {
                        oldLine = m.groupValues[1].toIntOrNull()
                        newLine = m.groupValues[2].toIntOrNull()
                        inHunk = true
                    }
                    parsed += DiffLine(DiffLineKind.HUNK, null, null, path)
                }
                inHunk && line.startsWith("+") && !line.startsWith("+++") -> {
                    parsed += DiffLine(DiffLineKind.ADDED, null, newLine, path)
                    newLine = newLine?.plus(1)
                }
                inHunk && line.startsWith("-") && !line.startsWith("---") -> {
                    parsed += DiffLine(DiffLineKind.REMOVED, oldLine, null, path)
                    oldLine = oldLine?.plus(1)
                }
                inHunk && line.startsWith(" ") -> {
                    parsed += DiffLine(DiffLineKind.CONTEXT, oldLine, newLine, path)
                    oldLine = oldLine?.plus(1)
                    newLine = newLine?.plus(1)
                }
                else -> parsed += DiffLine(DiffLineKind.OTHER, null, null, path)
            }
        }
        return UnifiedDiffData(normalized, parsed)
    }

    private fun extractLineRange(text: String, startLine: Int, endLine: Int): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.isEmpty()) return ""
        val start = (startLine - 1).coerceIn(0, lines.lastIndex)
        val end = (endLine - 1).coerceIn(start, lines.lastIndex)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun buildFileContextPreview(fileText: String, startLine: Int, endLine: Int, contextRadius: Int = 6): String {
        val lines = fileText.replace("\r\n", "\n").split('\n')
        if (lines.isEmpty()) return ""
        val selectedStart = (startLine - 1).coerceIn(0, lines.lastIndex)
        val selectedEnd = (endLine - 1).coerceIn(selectedStart, lines.lastIndex)
        val previewStart = (selectedStart - contextRadius).coerceAtLeast(0)
        val previewEnd = (selectedEnd + contextRadius).coerceAtMost(lines.lastIndex)
        val lineNumberWidth = (previewEnd + 1).toString().length.coerceAtLeast(3)
        val rendered = mutableListOf<String>()
        if (previewStart > 0) rendered += "..."
        for (index in previewStart..previewEnd) {
            val lineNumber = (index + 1).toString().padStart(lineNumberWidth, ' ')
            val marker = if (index in selectedStart..selectedEnd) ">" else " "
            rendered += "$marker $lineNumber | ${lines[index]}"
        }
        if (previewEnd < lines.lastIndex) rendered += "..."
        return rendered.joinToString("\n")
    }

    private fun toHtml(text: String): String {
        val escaped = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")
        return "<html><body style='font-family:${UIUtil.getLabelFont().family};'>$escaped</body></html>"
    }

    private fun openNativeBranchCompare() {
        val details = currentDetails ?: return
        val head = details.pullRequest.head?.ref ?: return
        val base = details.pullRequest.base?.ref ?: return
        GitBrancher.getInstance(context.gitRepository.project).showDiff(base, head, listOf(context.gitRepository))
    }

    private fun loadUnifiedDiff(filePath: String, baseRef: String, headRef: String): String {
        val repo = context.gitRepository.root.path
        val baseCandidates = listOf("origin/$baseRef", baseRef).distinct()
        val headCandidates = listOf(headRef, "origin/$headRef").distinct()
        for (base in baseCandidates) {
            for (head in headCandidates) {
                val result = runGit(repo, listOf("diff", "--no-color", "--unified=3", "$base..$head", "--", filePath))
                if (result.exitCode == 0 && result.output.isNotBlank()) return result.output
            }
        }
        return ""
    }

    private fun loadFileAtRef(refCandidates: List<String>, filePath: String): String? {
        val repo = context.gitRepository.root.path
        for (ref in refCandidates.distinct()) {
            val result = runGit(repo, listOf("show", "$ref:$filePath"))
            if (result.exitCode == 0) return result.output
        }
        return null
    }

    private fun runGit(repoPath: String, args: List<String>): GitCommandOutput {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(java.io.File(repoPath))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return GitCommandOutput(exitCode, output)
    }

    private fun <T> runBackground(title: String, onSuccess: (T) -> Unit, task: () -> T) {
        setBusy(true, title)
        ProgressManager.getInstance().run(object : Task.Backgroundable(context.gitRepository.project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = task()
                    ApplicationManager.getApplication().invokeLater {
                        setBusy(false, "")
                        onSuccess(result)
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        setBusy(false, "")
                        GiteaNotifications.showError(
                            context.gitRepository.project,
                            null,
                            GiteaBundle.message("pull.request.error.title"),
                            t
                        )
                    }
                }
            }
        })
    }

    private fun setBusy(busy: Boolean, message: String) {
        statusLabel.text = message
        listOf(refreshButton, openBrowserButton, openNativeDiffButton, reviewSelectionButton).forEach {
            it.isEnabled = !busy
        }
        filesList.isEnabled = !busy
        if (!busy) updateSelectionState()
    }

    private data class GitCommandOutput(val exitCode: Int, val output: String)

    private enum class DiffLineKind { ADDED, REMOVED, CONTEXT, HUNK, OTHER }

    private data class DiffLine(
        val kind: DiffLineKind,
        val oldLine: Int?,
        val newLine: Int?,
        val path: String?
    )

    private data class UnifiedDiffData(
        val text: String,
        val lines: List<DiffLine>
    )

    private data class DiffSelection(
        val path: String,
        val anchorSide: GiteaReviewDraftComment.Side,
        val anchorLine: Int,
        val newStartLine: Int?,
        val newEndLine: Int?,
        val oldStartLine: Int?,
        val oldEndLine: Int?,
        val selectedDiffPreview: String
    )

    private class FileRenderer : com.intellij.ui.ColoredListCellRenderer<ChangedFile>() {
        override fun customizeCellRenderer(
            list: JList<out ChangedFile>,
            value: ChangedFile?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append(value.filename.orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${value.status.orEmpty()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  +${value.additions ?: 0}/-${value.deletions ?: 0}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private class SuggestedChangeDialog(
        project: com.intellij.openapi.project.Project,
        private val filePath: String,
        initialEditableContent: String,
        private val startLine: Int,
        private val endLine: Int,
        private val selectedDiffPreview: String,
        private val surroundingContextPreview: String
    ) : DialogWrapper(project, true) {
        private val draft: DraftFile = createDraftFile(project, filePath, initialEditableContent)
        private val textEditor: TextEditor = TextEditorProvider.getInstance().createEditor(project, draft.virtualFile) as TextEditor
        private var editedSnapshot: String = initialEditableContent

        val editedText: String
            get() = editedSnapshot

        init {
            title = GiteaBundle.message("pull.request.review.request.changes.editor.title", filePath)
            init()
        }

        override fun doOKAction() {
            editedSnapshot = currentText()
            super.doOKAction()
        }

        override fun doCancelAction() {
            editedSnapshot = currentText()
            super.doCancelAction()
        }

        override fun dispose() {
            runCatching {
                TextEditorProvider.getInstance().disposeEditor(textEditor)
            }
            cleanupDraft(draft.root)
            super.dispose()
        }

        override fun createCenterPanel(): JComponent {
            val diffPreviewArea = JBTextArea(8, 80).apply {
                text = selectedDiffPreview
                isEditable = false
                lineWrap = false
                wrapStyleWord = false
                border = JBUI.Borders.empty(6)
                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                background = JBColor(Color(248, 250, 253), Color(40, 43, 48))
            }
            val contextPreviewArea = JBTextArea(8, 80).apply {
                text = surroundingContextPreview
                isEditable = false
                lineWrap = false
                wrapStyleWord = false
                border = JBUI.Borders.empty(6)
                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                background = JBColor(Color(250, 251, 252), Color(36, 39, 44))
                foreground = JBColor(Color(145, 150, 158), Color(120, 125, 133))
            }
            return JPanel(BorderLayout()).apply {
                preferredSize = Dimension(1120, 760)
                border = JBUI.Borders.empty(8)
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(
                            JLabel(GiteaBundle.message("pull.request.review.request.changes.editor.hint.range", filePath, startLine, endLine)).apply {
                                foreground = UIUtil.getContextHelpForeground()
                                border = JBUI.Borders.emptyBottom(4)
                            }
                        )
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.emptyBottom(8)
                                add(
                                    JLabel(GiteaBundle.message("pull.request.review.request.changes.editor.diff")).apply {
                                        foreground = UIUtil.getContextHelpForeground()
                                        border = JBUI.Borders.emptyBottom(4)
                                    },
                                    BorderLayout.NORTH
                                )
                                add(JBScrollPane(diffPreviewArea).apply {
                                    preferredSize = Dimension(1040, 180)
                                }, BorderLayout.CENTER)
                            }
                        )
                        add(
                            JPanel(BorderLayout()).apply {
                                border = JBUI.Borders.emptyBottom(8)
                                add(
                                    JLabel(GiteaBundle.message("pull.request.review.request.changes.editor.context")).apply {
                                        foreground = UIUtil.getContextHelpForeground()
                                        border = JBUI.Borders.emptyBottom(4)
                                    },
                                    BorderLayout.NORTH
                                )
                                add(JBScrollPane(contextPreviewArea).apply {
                                    preferredSize = Dimension(1040, 140)
                                }, BorderLayout.CENTER)
                            }
                        )
                    },
                    BorderLayout.NORTH
                )
                add(textEditor.component, BorderLayout.CENTER)
            }
        }

        private fun currentText(): String {
            val document = FileDocumentManager.getInstance().getDocument(draft.virtualFile)
            if (document != null) {
                runCatching { FileDocumentManager.getInstance().saveDocument(document) }
                return document.text
            }
            return runCatching { VfsUtil.loadText(draft.virtualFile) }.getOrElse { editedSnapshot }
        }

        private data class DraftFile(
            val root: Path,
            val path: Path,
            val virtualFile: VirtualFile
        )

        companion object {
            private fun createDraftFile(
                project: com.intellij.openapi.project.Project,
                originalPath: String,
                content: String
            ): DraftFile {
                val baseRoot = project.basePath?.let(Paths::get) ?: Paths.get(System.getProperty("java.io.tmpdir"))
                val root = baseRoot.resolve(".gitea-review-drafts")
                    .resolve("review-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
                val normalizedRelative = originalPath
                    .replace('\\', java.io.File.separatorChar)
                    .replace('/', java.io.File.separatorChar)
                val filePath = root.resolve(normalizedRelative)
                Files.createDirectories(filePath.parent)
                Files.write(filePath, content.toByteArray(StandardCharsets.UTF_8))
                val virtualFile = VfsUtil.findFile(filePath, true)
                    ?: error("Unable to create temporary review draft file: $filePath")
                return DraftFile(root, filePath, virtualFile)
            }

            private fun cleanupDraft(root: Path) {
                runCatching {
                    if (!Files.exists(root)) return@runCatching
                    Files.walk(root).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { path ->
                            Files.deleteIfExists(path)
                        }
                    }
                }
            }
        }
    }
}
