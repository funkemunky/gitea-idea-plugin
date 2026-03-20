/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.GiteaPullRequestFullDetails
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.github.leondevlifelog.gitea.services.GiteaReviewDraftComment
import com.github.leondevlifelog.gitea.services.GiteaPullRequestService
import com.github.leondevlifelog.gitea.services.CachingGiteaUserAvatarLoader
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsBranchComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsDescriptionComponentFactory
import com.intellij.collaboration.ui.codereview.details.CodeReviewDetailsTitleComponentFactory
import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranches
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranchesViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewDetailsViewModel
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.branch.GitBrancher
import org.gitnex.tea4j.v2.models.ChangedFile
import org.gitnex.tea4j.v2.models.Commit
import org.gitnex.tea4j.v2.models.Label
import org.gitnex.tea4j.v2.models.Milestone
import org.gitnex.tea4j.v2.models.PullRequest
import org.gitnex.tea4j.v2.models.PullReview
import org.gitnex.tea4j.v2.models.PullReviewComment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JEditorPane
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class GiteaPullRequestDetailsDialog(
    private val context: GiteaPullRequestRepositoryContext,
    private val repositoryDetails: GiteaPullRequestRepositoryDetails,
    initialPullRequest: PullRequest,
    private val service: GiteaPullRequestService,
    private val onPullRequestUpdated: () -> Unit
) : DialogWrapper(context.gitRepository.project, true) {

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val uiScope = MainScope().also { scope ->
        Disposer.register(disposable) { scope.cancel() }
    }
    private val avatarLoader = CachingGiteaUserAvatarLoader.getInstance()
    private val avatarIcons = mutableMapOf<String, Icon>()
    private val detailsVm = DialogCodeReviewDetailsViewModel()
    private val branchesVm = DialogCodeReviewBranchesViewModel()
    private val statusLabel = JBLabel()
    private val titleComponent = CodeReviewDetailsTitleComponentFactory.create(
        uiScope,
        detailsVm,
        "Open pull request in browser",
        DefaultActionGroup()
    ) { createHtmlPane() }
    private val descriptionComponent = CodeReviewDetailsDescriptionComponentFactory.create(
        uiScope,
        detailsVm,
        DefaultActionGroup(),
        {
            tabs.selectedIndex = 0
        }
    ) { createHtmlPane() }
    private val branchComponent = CodeReviewDetailsBranchComponentFactory.create(uiScope, branchesVm)
    private val timelineModel = DefaultListModel<TimelineEntry>()
    private val timelineList = JBList(timelineModel)
    private val reviewsArea = JBTextArea()
    private val commentsEditor = JBTextArea(5, 60)
    private val assigneesField = JBTextField()
    private val labelsField = JBTextField()
    private val reviewersField = JBTextField()
    private val teamReviewersField = JBTextField()
    private val dueDateField = JBTextField()
    private val milestoneCombo = ComboBox(CollectionComboBoxModel(createMilestoneOptions(), MilestoneOption.None))

    private val commitsModel = DefaultListModel<Commit>()
    private val commitsList = JBList(commitsModel)
    private val filesModel = DefaultListModel<ChangedFile>()
    private val filesList = JBList(filesModel)
    private val reviewDraftModel = DefaultListModel<PendingLineComment>()
    private val reviewDraftList = JBList(reviewDraftModel)
    private val lineStartField = JBTextField(5)
    private val lineEndField = JBTextField(5)
    private val lineCommentEditor = JBTextArea(3, 30)
    private val lineSideCombo = JComboBox(LineSide.values())
    private val tabs = JBTabbedPane()
    private val diffRequestPanel = DiffManager.getInstance().createRequestPanel(context.gitRepository.project, disposable, null)
    private val diffVersion = AtomicInteger()
    @Volatile
    private var currentDetails: GiteaPullRequestFullDetails? = null
    @Volatile
    private var currentPullRequest: PullRequest = initialPullRequest

    private val refreshButton = JButton(GiteaBundle.message("pull.request.details.refresh"))
    private val openBrowserButton = JButton(GiteaBundle.message("pull.request.details.open.browser"))
    private val openNativeDiffButton = JButton(GiteaBundle.message("pull.request.details.open.native.diff"))
    private val timelineOpenButton = JButton("Open Thread")
    private val timelineResolveButton = JButton("Resolve")
    private val saveMetadataButton = JButton(GiteaBundle.message("pull.request.details.save.metadata"))
    private val clearReviewersButton = JButton(GiteaBundle.message("pull.request.details.clear.reviewers"))
    private val commentButton = JButton(GiteaBundle.message("pull.request.details.comment"))
    private val approveButton = JButton(GiteaBundle.message("pull.request.details.approve"))
    private val requestChangesButton = JButton(GiteaBundle.message("pull.request.details.request.changes"))
    private val addLineCommentButton = JButton(GiteaBundle.message("pull.request.details.line.comment.add"))
    private val removeLineCommentButton = JButton(GiteaBundle.message("pull.request.details.line.comment.remove"))
    private val closeOrReopenButton = JButton()
    private val mergeButton = JButton(GiteaBundle.message("pull.request.details.merge"))

    init {
        title = GiteaBundle.message("pull.request.details.title", initialPullRequest.number ?: 0L)
        setModal(false)
        init()
        configureUi()
        refreshDetails()
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(1260, 840)
        root.border = JBUI.Borders.empty(8)
        root.add(buildHeader(), BorderLayout.NORTH)
        root.add(buildBody(), BorderLayout.CENTER)
        root.add(buildFooter(), BorderLayout.SOUTH)
        return root
    }

    private fun buildHeader(): JComponent {
        val controls = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
            add(refreshButton)
            add(openBrowserButton)
            add(openNativeDiffButton)
        }
        return FormBuilder.createFormBuilder()
            .addComponent(titleComponent)
            .addComponent(descriptionComponent)
            .addComponent(branchComponent)
            .addComponent(statusLabel)
            .addComponent(controls)
            .panel
    }

    private fun buildBody(): JComponent {
        val conversationPanel = JPanel(BorderLayout()).apply {
            timelineList.cellRenderer = TimelineRenderer()
            add(
                JPanel().apply {
                    layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4)
                    border = JBUI.Borders.customLineBottom(JBColor.border())
                    add(JBLabel("Conversation"))
                    add(timelineOpenButton)
                    add(timelineResolveButton)
                },
                BorderLayout.NORTH
            )
            add(JBScrollPane(timelineList), BorderLayout.CENTER)
        }
        val commitsPanel = JPanel(BorderLayout()).apply {
            commitsList.cellRenderer = CommitRenderer()
            add(JBScrollPane(commitsList), BorderLayout.CENTER)
        }
        val filesPanel = JPanel(BorderLayout()).apply {
            filesList.cellRenderer = FileRenderer()
            add(
                JPanel(BorderLayout()).apply {
                    add(JBScrollPane(filesList), BorderLayout.WEST)
                    add(diffRequestPanel.component, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
            add(buildReviewDraftPanel(), BorderLayout.SOUTH)
        }

        tabs.addTab(GiteaBundle.message("pull.request.details.tab.conversation"), conversationPanel)
        tabs.addTab(GiteaBundle.message("pull.request.details.tab.commits"), commitsPanel)
        tabs.addTab(GiteaBundle.message("pull.request.details.tab.files"), filesPanel)

        val left = JPanel(BorderLayout()).apply {
            add(tabs, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.customLineTop(com.intellij.ui.JBColor.border())
                    commentsEditor.lineWrap = true
                    commentsEditor.wrapStyleWord = true
                    add(JBLabel(GiteaBundle.message("pull.request.details.comment.label")), BorderLayout.NORTH)
                    add(JBScrollPane(commentsEditor), BorderLayout.CENTER)
                },
                BorderLayout.SOUTH
            )
        }

        val right = buildMetadataPanel()
        return JBSplitter(false, 0.72f).apply {
            firstComponent = left
            secondComponent = right
        }
    }

    private fun buildReviewDraftPanel(): JComponent {
        lineCommentEditor.lineWrap = true
        lineCommentEditor.wrapStyleWord = true
        reviewDraftList.cellRenderer = PendingLineCommentRenderer()
        reviewDraftList.visibleRowCount = 4
        lineSideCombo.selectedItem = LineSide.NEW

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel(GiteaBundle.message("pull.request.details.line.comment.side")))
            add(Box.createHorizontalStrut(6))
            add(lineSideCombo)
            add(Box.createHorizontalStrut(12))
            add(JBLabel(GiteaBundle.message("pull.request.details.line.comment.start")))
            add(Box.createHorizontalStrut(6))
            add(lineStartField)
            add(Box.createHorizontalStrut(12))
            add(JBLabel(GiteaBundle.message("pull.request.details.line.comment.end")))
            add(Box.createHorizontalStrut(6))
            add(lineEndField)
            add(Box.createHorizontalStrut(12))
            add(addLineCommentButton)
            add(Box.createHorizontalStrut(6))
            add(removeLineCommentButton)
            add(Box.createHorizontalGlue())
        }

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(8, 8, 0, 8)
            )
            add(controls, BorderLayout.NORTH)
            add(JBScrollPane(lineCommentEditor), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyTop(6)
                    add(JBLabel(GiteaBundle.message("pull.request.details.line.comment.pending")), BorderLayout.NORTH)
                    add(JBScrollPane(reviewDraftList), BorderLayout.CENTER)
                },
                BorderLayout.SOUTH
            )
        }
        return panel
    }

    private fun buildMetadataPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(350, 760)
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.assignees"), assigneesField)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.labels"), labelsField)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.reviewers"), reviewersField)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.team.reviewers"), teamReviewersField)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.milestone"), milestoneCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.due.date"), dueDateField)
            .addComponent(JBLabel(GiteaBundle.message("pull.request.details.hint.assignees", availableCollaborators())))
            .addComponent(JBLabel(GiteaBundle.message("pull.request.details.hint.labels", availableLabels())))
            .addComponent(JBLabel(GiteaBundle.message("pull.request.details.hint.reviewers", availableCollaborators())))
            .addComponent(JBLabel(GiteaBundle.message("pull.request.details.hint.teams", availableTeams())))
            .addComponent(saveMetadataButton)
            .addComponent(clearReviewersButton)
            .addComponent(JBLabel(GiteaBundle.message("pull.request.details.reviews")))
            .addComponent(JBScrollPane(reviewsArea))
            .panel
        reviewsArea.isEditable = false
        reviewsArea.lineWrap = true
        reviewsArea.wrapStyleWord = true
        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun buildFooter(): JComponent {
        return JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)
            add(commentButton)
            add(approveButton)
            add(requestChangesButton)
            add(closeOrReopenButton)
            add(mergeButton)
        }
    }

    private fun configureUi() {
        configureTimelineInteractions()
        timelineOpenButton.addActionListener {
            selectedTimelineEntry()?.let(::openTimelineEntry)
        }
        timelineResolveButton.addActionListener {
            val entry = selectedTimelineEntry() ?: return@addActionListener
            val commentId = entry.reviewCommentId ?: return@addActionListener
            if (entry.resolved) unresolveReviewComment(commentId) else resolveReviewComment(commentId)
        }
        filesList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                showSelectedFileDiff()
            }
        }
        refreshButton.addActionListener { refreshDetails() }
        openBrowserButton.addActionListener {
            currentPullRequest.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
        }
        openNativeDiffButton.addActionListener { openNativeBranchCompare() }
        saveMetadataButton.addActionListener { saveMetadataAndReviewers() }
        clearReviewersButton.addActionListener { clearReviewers() }
        addLineCommentButton.addActionListener { addPendingLineComment() }
        removeLineCommentButton.addActionListener { removeSelectedPendingLineComment() }
        commentButton.addActionListener { submitComment() }
        approveButton.addActionListener { submitReview("APPROVE") }
        requestChangesButton.addActionListener { submitReview("REQUEST_CHANGES") }
        closeOrReopenButton.addActionListener { toggleState() }
        mergeButton.addActionListener { mergePullRequest() }
        updateTimelineActionState()
        showDiffMessage(GiteaBundle.message("pull.request.details.diff.select"))
    }

    private fun configureTimelineInteractions() {
        timelineList.addListSelectionListener {
            if (!it.valueIsAdjusting) updateTimelineActionState()
        }
        timelineList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                    selectedTimelineEntry()?.let(::openTimelineEntry)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowTimelinePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowTimelinePopup(e)
            }
        })
    }

    private fun updateTimelineActionState() {
        val entry = selectedTimelineEntry()
        timelineOpenButton.isEnabled = entry != null && (!entry.htmlUrl.isNullOrBlank() || !entry.filePath.isNullOrBlank())
        val canResolve = entry?.reviewCommentId != null
        val resolved = entry?.resolved ?: false
        timelineResolveButton.isEnabled = canResolve
        timelineResolveButton.text = when {
            !canResolve -> "Resolve"
            resolved -> "Unresolve"
            else -> "Resolve"
        }
    }

    private fun maybeShowTimelinePopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val row = timelineList.locationToIndex(event.point)
        if (row < 0) return
        timelineList.selectedIndex = row
        val entry = selectedTimelineEntry() ?: return

        val menu = JPopupMenu()
        entry.htmlUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val openItem = JMenuItem(GiteaBundle.message("pull.request.menu.view.browser"))
            openItem.addActionListener { BrowserUtil.browse(url) }
            menu.add(openItem)
        }
        entry.reviewCommentId?.let { commentId ->
            val resolveLabel = if (entry.resolved) "Unresolve" else "Resolve"
            val resolveItem = JMenuItem(resolveLabel)
            resolveItem.addActionListener {
                if (entry.resolved) {
                    unresolveReviewComment(commentId)
                } else {
                    resolveReviewComment(commentId)
                }
            }
            menu.add(resolveItem)
        }
        if (menu.componentCount > 0) {
            menu.show(timelineList, event.x, event.y)
        }
    }

    private fun selectedTimelineEntry(): TimelineEntry? = timelineList.selectedValue

    private fun openTimelineEntry(entry: TimelineEntry) {
        entry.filePath?.takeIf { it.isNotBlank() }?.let { filePath ->
            tabs.selectedIndex = 2
            val fileIndex = (0 until filesModel.size()).firstOrNull { index ->
                filesModel.getElementAt(index).filename == filePath
            }
            if (fileIndex != null) {
                filesList.selectedIndex = fileIndex
                filesList.ensureIndexIsVisible(fileIndex)
                return
            }
        }
        entry.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
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
        val state = pr.state.orEmpty()
        detailsVm.update(pr)
        branchesVm.update(pr)
        statusLabel.text = GiteaBundle.message(
            "pull.request.details.summary",
            details.commits.size,
            details.files.size,
            details.comments.size + details.reviewComments.size
        )
        closeOrReopenButton.text = if (state.equals("open", ignoreCase = true)) {
            GiteaBundle.message("pull.request.menu.close")
        } else {
            GiteaBundle.message("pull.request.menu.reopen")
        }
        mergeButton.isEnabled = state.equals("open", ignoreCase = true) && pr.isMerged() != true

        assigneesField.text = pr.assignees.orEmpty().mapNotNull { it.loginName }.joinToString(", ")
        labelsField.text = pr.labels.orEmpty().mapNotNull { it.id?.toString() }.joinToString(", ")
        reviewersField.text = pr.requestedReviewers.orEmpty().mapNotNull { it.loginName }.joinToString(", ")
        teamReviewersField.text = pr.requestedReviewersTeams.orEmpty().mapNotNull { it.name }.joinToString(", ")
        dueDateField.text = pr.dueDate?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }.orEmpty()
        milestoneCombo.selectedItem = milestoneOptionById(pr.milestone?.id)

        commitsModel.clear()
        details.commits.forEach(commitsModel::addElement)
        filesModel.clear()
        details.files.forEach(filesModel::addElement)
        if (!filesModel.isEmpty) {
            filesList.selectedIndex = 0
        }

        populateTimeline(details)
        reviewsArea.text = buildReviewsText(details.reviews)
    }

    private fun populateTimeline(details: GiteaPullRequestFullDetails) {
        val entries = mutableListOf<TimelineEntry>()
        val pr = details.pullRequest
        val reviewsById = details.reviews.associateBy { it.id }
        entries += TimelineEntry(
            createdAt = pr.createdAt,
            actor = pr.user?.loginName ?: "-",
            title = "opened this pull request",
            body = pr.body?.takeIf { it.isNotBlank() } ?: GiteaBundle.message("pull.request.details.no.description"),
            kind = TimelineEntry.Kind.OPENED,
            avatarUrl = pr.user?.avatarUrl,
            htmlUrl = pr.htmlUrl
        )
        details.timeline.forEach { event ->
            entries += TimelineEntry(
                createdAt = event.createdAt,
                actor = event.user?.loginName ?: event.assignee?.loginName ?: "-",
                title = formatTimelineEventTitle(event.type, event.refAction),
                body = event.body
                    ?: event.newTitle
                    ?: event.newRef
                    ?: event.oldTitle
                    ?: event.refCommitSha?.take(8)
                    ?: event.refIssue?.title,
                kind = timelineKindForEvent(event.type),
                badge = if (event.resolveDoer != null) "RESOLVED" else null,
                details = event.newRef ?: event.oldRef,
                avatarUrl = event.user?.avatarUrl ?: event.assignee?.avatarUrl,
                htmlUrl = event.htmlUrl
            )
        }
        details.comments.forEach { comment ->
            entries += TimelineEntry(
                createdAt = comment.createdAt,
                actor = comment.user?.loginName ?: "-",
                title = "commented",
                body = comment.body,
                kind = TimelineEntry.Kind.COMMENT,
                avatarUrl = comment.user?.avatarUrl,
                htmlUrl = comment.htmlUrl
            )
        }
        details.reviews.forEach { review ->
            entries += TimelineEntry(
                createdAt = review.updatedAt ?: review.submittedAt,
                actor = review.user?.loginName ?: review.team?.name ?: "-",
                title = reviewTitle(review),
                body = review.body,
                kind = timelineKindForReview(review),
                badge = buildReviewBadge(review),
                details = review.commitId?.take(8),
                avatarUrl = review.user?.avatarUrl,
                htmlUrl = review.htmlUrl
            )
        }
        details.reviewComments.forEach { comment ->
            val review = reviewsById[comment.pullRequestReviewId]
            val isResolved = comment.resolver != null
            val isOutdated = comment.originalPosition != null && comment.position != null && comment.originalPosition != comment.position
            entries += TimelineEntry(
                createdAt = comment.updatedAt ?: comment.createdAt,
                actor = comment.user?.loginName ?: review?.user?.loginName ?: "-",
                title = review?.let(::reviewTitle) ?: "reviewed the changes",
                body = comment.body ?: "",
                kind = if (review?.state?.equals("REQUEST_CHANGES", ignoreCase = true) == true ||
                    review?.state?.equals("CHANGES_REQUESTED", ignoreCase = true) == true
                ) TimelineEntry.Kind.CHANGES_REQUESTED else TimelineEntry.Kind.REVIEW_THREAD,
                badge = when {
                    isResolved -> "RESOLVED"
                    isOutdated -> "OUTDATED"
                    else -> null
                },
                details = buildReviewCommentDetails(comment),
                filePath = comment.path,
                diffHunk = comment.diffHunk,
                avatarUrl = comment.user?.avatarUrl ?: review?.user?.avatarUrl,
                reviewCommentId = comment.id,
                resolved = isResolved,
                htmlUrl = comment.htmlUrl
            )
        }
        details.commits.forEach { commit ->
            entries += TimelineEntry(
                createdAt = commit.created,
                actor = commit.author?.loginName ?: commit.commit?.author?.name ?: "-",
                title = "added a commit",
                body = commit.commit?.message?.lineSequence()?.firstOrNull().orEmpty(),
                kind = TimelineEntry.Kind.COMMIT,
                details = commit.sha?.take(8) ?: "-",
                avatarUrl = commit.author?.avatarUrl,
                htmlUrl = commit.htmlUrl
            )
        }

        timelineModel.clear()
        entries
            .distinctBy { "${it.reviewCommentId}|${it.actor}|${it.createdAt?.time}|${it.title}|${it.body}|${it.badge}" }
            .sortedBy { it.createdAt ?: Date(0) }
            .forEach(timelineModel::addElement)
    }

    private fun reviewTitle(review: PullReview): String {
        return when (review.state?.uppercase(Locale.getDefault())) {
            "APPROVED" -> "approved these changes"
            "REQUEST_CHANGES", "CHANGES_REQUESTED" -> "requested changes"
            "COMMENT" -> "reviewed the changes"
            "PENDING" -> "started a review"
            else -> (review.state ?: "reviewed").lowercase(Locale.getDefault()).replace('_', ' ')
        }
    }

    private fun timelineKindForReview(review: PullReview): TimelineEntry.Kind {
        return when (review.state?.uppercase(Locale.getDefault())) {
            "APPROVED" -> TimelineEntry.Kind.APPROVED
            "REQUEST_CHANGES", "CHANGES_REQUESTED" -> TimelineEntry.Kind.CHANGES_REQUESTED
            else -> TimelineEntry.Kind.COMMENT
        }
    }

    private fun buildReviewBadge(review: PullReview): String? {
        return when {
            review.isDismissed() == true -> "DISMISSED"
            review.isStale() == true -> "OUTDATED"
            else -> null
        }
    }

    private fun formatTimelineEventTitle(type: String?, refAction: String?): String {
        val normalized = type?.lowercase(Locale.getDefault()).orEmpty()
        return when {
            normalized.contains("merge") -> "merged this pull request"
            normalized.contains("close") -> "closed this pull request"
            normalized.contains("reopen") -> "reopened this pull request"
            normalized.contains("assign") -> "updated assignees"
            normalized.contains("label") -> "updated labels"
            normalized.contains("review") -> "review activity"
            normalized.contains("comment") -> "commented"
            normalized.contains("title") -> "updated the title"
            normalized.contains("ref") -> refAction?.replace('_', ' ') ?: "updated a reference"
            normalized.isBlank() -> "activity"
            else -> normalized.replace('_', ' ')
        }
    }

    private fun timelineKindForEvent(type: String?): TimelineEntry.Kind {
        val normalized = type?.lowercase(Locale.getDefault()).orEmpty()
        return when {
            normalized.contains("merge") -> TimelineEntry.Kind.MERGED
            normalized.contains("close") -> TimelineEntry.Kind.CLOSED
            normalized.contains("comment") -> TimelineEntry.Kind.COMMENT
            else -> TimelineEntry.Kind.EVENT
        }
    }

    private fun buildReviewCommentDetails(comment: PullReviewComment): String {
        val path = comment.path ?: "-"
        val position = comment.position ?: comment.originalPosition
        val linePart = position?.let { "L$it" } ?: ""
        return if (linePart.isBlank()) path else "$path  $linePart"
    }

    private fun buildReviewsText(reviews: List<PullReview>): String {
        if (reviews.isEmpty()) return GiteaBundle.message("pull.request.details.no.reviews")
        return reviews.sortedByDescending { it.updatedAt ?: Date(0) }.joinToString("\n\n") { review ->
            val whenAt = review.updatedAt?.let(formatter::format) ?: "-"
            val who = review.user?.loginName ?: review.team?.name ?: "-"
            val state = review.state ?: "COMMENT"
            "[$whenAt] $who - $state\n${review.body.orEmpty()}"
        }
    }

    private fun saveMetadataAndReviewers() {
        val number = currentPullRequest.number ?: return
        val assignees = parseCsv(assigneesField.text)
        val reviewers = parseCsv(reviewersField.text)
        val teamReviewers = parseCsv(teamReviewersField.text)
        val labels = parseLabelIds(labelsField.text)
        val milestone = (milestoneCombo.item as? MilestoneOption.Selected)?.id
        val dueDate = try {
            parseDueDate(dueDateField.text)
        } catch (_: Exception) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.validation.due.date")
            )
            return
        }
        runBackground(
            GiteaBundle.message("pull.request.details.saving"),
            onSuccess = {
                commentsEditor.text = ""
                onPullRequestUpdated()
                refreshDetails()
            },
            task = {
                service.updatePullRequestMetadata(context, number, assignees, labels, milestone, dueDate)
                service.requestReviewers(context, number, reviewers, teamReviewers)
            }
        )
    }

    private fun clearReviewers() {
        val number = currentPullRequest.number ?: return
        runBackground(
            GiteaBundle.message("pull.request.details.saving"),
            onSuccess = {
                reviewersField.text = ""
                teamReviewersField.text = ""
                onPullRequestUpdated()
                refreshDetails()
            },
            task = { service.clearRequestedReviewers(context, number) }
        )
    }

    private fun addPendingLineComment() {
        val filePath = filesList.selectedValue?.filename
        if (filePath.isNullOrBlank()) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.details.line.comment.file.required")
            )
            return
        }
        val start = lineStartField.text.trim().toIntOrNull()
        val end = lineEndField.text.trim().toIntOrNull()
        if (start == null || end == null || start <= 0 || end <= 0 || end < start) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.details.line.comment.range.invalid")
            )
            return
        }
        if (end - start > 50) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.details.line.comment.range.too.large")
            )
            return
        }
        val body = lineCommentEditor.text.trim()
        if (body.isBlank()) {
            GiteaNotifications.showWarning(
                context.gitRepository.project,
                null,
                GiteaBundle.message("pull.request.error.title"),
                GiteaBundle.message("pull.request.details.line.comment.body.required")
            )
            return
        }
        val side = (lineSideCombo.selectedItem as? LineSide) ?: LineSide.NEW
        reviewDraftModel.addElement(PendingLineComment(filePath, start, end, side, body))
        lineCommentEditor.text = ""
    }

    private fun removeSelectedPendingLineComment() {
        val selectedIndex = reviewDraftList.selectedIndex
        if (selectedIndex >= 0) {
            reviewDraftModel.remove(selectedIndex)
        }
    }

    private fun submitComment() {
        val number = currentPullRequest.number ?: return
        val text = commentsEditor.text.trim()
        if (text.isBlank()) return
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = {
                commentsEditor.text = ""
                refreshDetails()
            },
            task = { service.addPullRequestComment(context, number, text) }
        )
    }

    private fun submitReview(event: String) {
        val number = currentPullRequest.number ?: return
        val text = commentsEditor.text.trim()
        val pendingDrafts = collectPendingLineComments()
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = {
                commentsEditor.text = ""
                reviewDraftModel.clear()
                refreshDetails()
            },
            task = { service.submitReview(context, number, event, text, pendingDrafts) }
        )
    }

    private fun collectPendingLineComments(): List<GiteaReviewDraftComment> {
        val result = mutableListOf<GiteaReviewDraftComment>()
        for (index in 0 until reviewDraftModel.size()) {
            val draft = reviewDraftModel.get(index)
            for (line in draft.startLine..draft.endLine) {
                result += GiteaReviewDraftComment(
                    path = draft.filePath,
                    position = line,
                    side = if (draft.side == LineSide.NEW) {
                        GiteaReviewDraftComment.Side.NEW
                    } else {
                        GiteaReviewDraftComment.Side.OLD
                    },
                    body = draft.body
                )
            }
        }
        return result
    }

    private fun mergePullRequest() {
        val number = currentPullRequest.number ?: return
        runBackground(
            GiteaBundle.message("pull.request.details.merging"),
            onSuccess = {
                onPullRequestUpdated()
                refreshDetails()
            },
            task = { service.mergePullRequest(context, number, commentsEditor.text.takeIf { it.isNotBlank() }) }
        )
    }

    private fun resolveReviewComment(commentId: Long) {
        runBackground(
            GiteaBundle.message("pull.request.details.saving"),
            onSuccess = { refreshDetails() },
            task = {
                service.resolveReviewComment(context, commentId)
                Unit
            }
        )
    }

    private fun unresolveReviewComment(commentId: Long) {
        runBackground(
            GiteaBundle.message("pull.request.details.saving"),
            onSuccess = { refreshDetails() },
            task = {
                service.unresolveReviewComment(context, commentId)
                Unit
            }
        )
    }

    private fun toggleState() {
        val number = currentPullRequest.number ?: return
        val open = currentPullRequest.state.equals("open", ignoreCase = true)
        runBackground(
            GiteaBundle.message("pull.request.updating"),
            onSuccess = {
                onPullRequestUpdated()
                refreshDetails()
            },
            task = {
                service.updatePullRequestState(
                    context,
                    currentPullRequest,
                    if (open) "closed" else "open"
                )
            }
        )
    }

    private fun openNativeBranchCompare() {
        val details = currentDetails ?: return
        val head = details.pullRequest.head?.ref ?: return
        val base = details.pullRequest.base?.ref ?: return
        GitBrancher.getInstance(context.gitRepository.project).showDiff(base, head, listOf(context.gitRepository))
    }

    private fun showSelectedFileDiff() {
        val details = currentDetails ?: return
        val file = filesList.selectedValue?.filename ?: run {
            showDiffMessage(GiteaBundle.message("pull.request.details.diff.select"))
            return
        }
        val baseRef = details.pullRequest.base?.ref
        val headRef = details.pullRequest.head?.ref
        if (baseRef.isNullOrBlank() || headRef.isNullOrBlank()) {
            showDiffMessage(GiteaBundle.message("pull.request.details.diff.unavailable"))
            return
        }

        val version = diffVersion.incrementAndGet()
        showDiffMessage(GiteaBundle.message("pull.request.diff.loading.file", file))
        AppExecutorUtil.getAppExecutorService().submit {
            val left = loadFileAtRef(listOf("origin/$baseRef", baseRef), file).orEmpty()
            val right = loadFileAtRef(listOf(headRef, "origin/$headRef"), file).orEmpty()
            SwingUtilities.invokeLater {
                if (version != diffVersion.get()) return@invokeLater
                val request = SimpleDiffRequest(
                    GiteaBundle.message("pull.request.diff.request.title", baseRef, headRef),
                    DiffContentFactory.getInstance().create(left),
                    DiffContentFactory.getInstance().create(right),
                    GiteaBundle.message("pull.request.diff.left.title", baseRef, file),
                    GiteaBundle.message("pull.request.diff.right.title", headRef, file)
                )
                diffRequestPanel.setRequest(request)
            }
        }
    }

    private fun loadFileAtRef(refCandidates: List<String>, filePath: String): String? {
        val repoDir = context.gitRepository.root.path
        for (ref in refCandidates) {
            val result = runGit(repoDir, listOf("show", "$ref:$filePath"))
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

    private fun parseCsv(text: String): List<String> =
        text.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun parseLabelIds(text: String): List<Long> =
        parseCsv(text).mapNotNull { it.toLongOrNull() }

    private fun parseDueDate(text: String): Date? {
        if (text.isBlank()) return null
        val localDate = LocalDate.parse(text.trim())
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    private fun showDiffMessage(message: String) {
        diffRequestPanel.setRequest(MessageDiffRequest(message))
    }

    private fun milestoneOptionById(id: Long?): MilestoneOption {
        if (id == null) return MilestoneOption.None
        return createMilestoneOptions().firstOrNull { it is MilestoneOption.Selected && it.id == id } ?: MilestoneOption.None
    }

    private fun createMilestoneOptions(): List<MilestoneOption> {
        return listOf(MilestoneOption.None) + repositoryDetails.milestones.mapNotNull { milestone ->
            val id = milestone.id ?: return@mapNotNull null
            MilestoneOption.Selected(id, milestone.title ?: id.toString())
        }
    }

    private fun availableCollaborators(): String =
        repositoryDetails.collaborators.mapNotNull { it.loginName }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun availableLabels(): String =
        repositoryDetails.labels.mapNotNull { label: Label ->
            val id = label.id ?: return@mapNotNull null
            "$id:${label.name ?: id.toString()}"
        }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun availableTeams(): String =
        repositoryDetails.teams.mapNotNull { it.name }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun createHtmlPane(): JEditorPane {
        return JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            border = null
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            addHyperlinkListener { event ->
                if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    event.url?.let(BrowserUtil::browse)
                }
            }
        }
    }

    private fun createAvatarLabel(name: String, avatarUrl: String?): JComponent {
        val label = JLabel(name.take(1).uppercase(Locale.getDefault())).apply {
            horizontalAlignment = JLabel.CENTER
            verticalAlignment = JLabel.CENTER
            foreground = UIUtil.getLabelForeground()
            background = JBColor(Color(232, 235, 241), Color(66, 70, 77))
            isOpaque = true
            preferredSize = JBUI.size(24, 24)
            minimumSize = preferredSize
            maximumSize = preferredSize
            border = RoundedLineBorder(JBColor.border(), 12, 1)
        }
        avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
            avatarIcons[url]?.let { icon ->
                label.text = ""
                label.icon = icon
            } ?: avatarLoader.requestAvatar(url).thenAccept { image ->
                if (image != null) {
                    avatarIcons[url] = ImageIcon(image)
                    SwingUtilities.invokeLater { timelineList.repaint() }
                }
            }
        }
        return label
    }

    private fun <T> runBackground(
        title: String,
        onSuccess: (T) -> Unit,
        task: () -> T
    ) {
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

    private fun setBusy(busy: Boolean, text: String) {
        statusLabel.text = text
        listOf(
            refreshButton,
            openBrowserButton,
            openNativeDiffButton,
            timelineOpenButton,
            timelineResolveButton,
            saveMetadataButton,
            clearReviewersButton,
            addLineCommentButton,
            removeLineCommentButton,
            commentButton,
            approveButton,
            requestChangesButton,
            closeOrReopenButton,
            mergeButton
        ).forEach { it.isEnabled = !busy }
        assigneesField.isEnabled = !busy
        labelsField.isEnabled = !busy
        reviewersField.isEnabled = !busy
        teamReviewersField.isEnabled = !busy
        milestoneCombo.isEnabled = !busy
        dueDateField.isEnabled = !busy
        commentsEditor.isEnabled = !busy
        lineCommentEditor.isEnabled = !busy
        lineStartField.isEnabled = !busy
        lineEndField.isEnabled = !busy
        lineSideCombo.isEnabled = !busy
        reviewDraftList.isEnabled = !busy
        timelineList.isEnabled = !busy
        commitsList.isEnabled = !busy
        filesList.isEnabled = !busy
        if (!busy) {
            updateTimelineActionState()
        }
    }

    private data class GitCommandOutput(val exitCode: Int, val output: String)

    private inner class DialogCodeReviewDetailsViewModel : CodeReviewDetailsViewModel {
        private val titleState = MutableStateFlow("")
        private val descriptionState = MutableStateFlow("")
        override val reviewRequestState: Flow<ReviewRequestState> = MutableStateFlow(ReviewRequestState.OPENED)
        override var number: String = ""
            private set
        override var url: String = ""
            private set
        override val title: Flow<String> = titleState
        override val description: Flow<String>? = descriptionState

        fun update(pr: PullRequest) {
            number = (pr.number ?: 0L).toString()
            url = pr.htmlUrl.orEmpty()
            titleState.value = pr.title.orEmpty()
            descriptionState.value = pr.body?.takeIf { it.isNotBlank() }
                ?: GiteaBundle.message("pull.request.details.no.description")
            (reviewRequestState as MutableStateFlow).value = when {
                pr.isDraft() == true -> ReviewRequestState.DRAFT
                pr.isMerged() == true -> ReviewRequestState.MERGED
                pr.state.equals("closed", ignoreCase = true) -> ReviewRequestState.CLOSED
                else -> ReviewRequestState.OPENED
            }
        }
    }

    private inner class DialogCodeReviewBranchesViewModel : CodeReviewBranchesViewModel {
        override val sourceBranch: StateFlow<String> = MutableStateFlow("")
        override val isCheckedOut: SharedFlow<Boolean> = MutableSharedFlow<Boolean>(replay = 1)
        override val showBranchesRequests: SharedFlow<CodeReviewBranches> = MutableSharedFlow<CodeReviewBranches>(replay = 1)
        private var targetBranch = ""

        override fun fetchAndCheckoutRemoteBranch() {
            val source = sourceBranch.value
            if (source.isBlank()) return
            GitBrancher.getInstance(context.gitRepository.project)
                .checkoutNewBranchStartingFrom(source, "origin/$source", listOf(context.gitRepository), null)
        }

        override fun showBranches() {
            val source = sourceBranch.value
            if (source.isBlank() || targetBranch.isBlank()) return
            openNativeBranchCompare()
        }

        fun update(pr: PullRequest) {
            val source = pr.head?.ref ?: ""
            targetBranch = pr.base?.ref ?: ""
            (sourceBranch as MutableStateFlow).value = source
            (isCheckedOut as MutableSharedFlow).tryEmit(context.currentBranch == source)
            (showBranchesRequests as MutableSharedFlow).tryEmit(CodeReviewBranches(source, targetBranch, true))
        }
    }

    private data class TimelineEntry(
        val createdAt: Date?,
        val actor: String,
        val title: String,
        val body: String?,
        val kind: Kind,
        val badge: String? = null,
        val details: String? = null,
        val filePath: String? = null,
        val diffHunk: String? = null,
        val avatarUrl: String? = null,
        val reviewCommentId: Long? = null,
        val resolved: Boolean = false,
        val htmlUrl: String? = null
    ) {
        enum class Kind(
            val accent: JBColor,
            val accentSoft: JBColor,
            val cardBackground: JBColor
        ) {
            OPENED(
                accent = JBColor(Color(74, 120, 193), Color(102, 153, 255)),
                accentSoft = JBColor(Color(223, 233, 248), Color(41, 60, 89)),
                cardBackground = JBColor(Color(250, 252, 255), Color(35, 39, 45))
            ),
            COMMENT(
                accent = JBColor(Color(80, 125, 191), Color(100, 170, 255)),
                accentSoft = JBColor(Color(225, 236, 250), Color(40, 55, 77)),
                cardBackground = JBColor(Color(251, 253, 255), Color(34, 38, 43))
            ),
            APPROVED(
                accent = JBColor(Color(62, 132, 77), Color(111, 190, 118)),
                accentSoft = JBColor(Color(222, 244, 228), Color(37, 69, 44)),
                cardBackground = JBColor(Color(249, 253, 249), Color(33, 40, 34))
            ),
            CHANGES_REQUESTED(
                accent = JBColor(Color(176, 94, 32), Color(240, 164, 86)),
                accentSoft = JBColor(Color(249, 234, 221), Color(80, 54, 30)),
                cardBackground = JBColor(Color(255, 251, 247), Color(42, 36, 31))
            ),
            REVIEW_THREAD(
                accent = JBColor(Color(66, 134, 175), Color(98, 172, 227)),
                accentSoft = JBColor(Color(220, 239, 250), Color(43, 63, 79)),
                cardBackground = JBColor(Color(248, 252, 255), Color(32, 38, 45))
            ),
            COMMIT(
                accent = JBColor(Color(130, 88, 186), Color(173, 129, 233)),
                accentSoft = JBColor(Color(236, 229, 248), Color(67, 52, 91)),
                cardBackground = JBColor(Color(252, 250, 255), Color(35, 34, 42))
            ),
            EVENT(
                accent = JBColor(Color(120, 127, 137), Color(145, 153, 165)),
                accentSoft = JBColor(Color(235, 238, 242), Color(53, 58, 64)),
                cardBackground = JBColor(Color(250, 250, 251), Color(34, 36, 39))
            ),
            MERGED(
                accent = JBColor(Color(116, 72, 185), Color(158, 116, 232)),
                accentSoft = JBColor(Color(235, 229, 248), Color(61, 48, 84)),
                cardBackground = JBColor(Color(251, 249, 255), Color(36, 34, 41))
            ),
            CLOSED(
                accent = JBColor(Color(146, 87, 87), Color(209, 118, 118)),
                accentSoft = JBColor(Color(246, 230, 230), Color(74, 48, 48)),
                cardBackground = JBColor(Color(253, 250, 250), Color(38, 33, 33))
            )
        }
    }

    private enum class LineSide {
        NEW,
        OLD;

        override fun toString(): String = name.lowercase(Locale.getDefault())
    }

    private data class PendingLineComment(
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val side: LineSide,
        val body: String
    )

    private sealed interface MilestoneOption {
        data object None : MilestoneOption {
            override fun toString(): String = GiteaBundle.message("pull.request.form.none")
        }

        data class Selected(val id: Long, private val title: String) : MilestoneOption {
            override fun toString(): String = title
        }
    }

    private class CommitRenderer : ColoredListCellRenderer<Commit>() {
        override fun customizeCellRenderer(
            list: JList<out Commit>,
            value: Commit?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            val sha = value.sha?.take(8) ?: "-"
            val message = value.commit?.message?.lineSequence()?.firstOrNull().orEmpty()
            append("$sha  $message", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("\n")
            append(
                value.author?.loginName ?: value.commit?.author?.name ?: "-",
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            )
        }
    }

    private class FileRenderer : ColoredListCellRenderer<ChangedFile>() {
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

    private class PendingLineCommentRenderer : ColoredListCellRenderer<PendingLineComment>() {
        override fun customizeCellRenderer(
            list: JList<out PendingLineComment>,
            value: PendingLineComment?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            val lineRange = if (value.startLine == value.endLine) {
                value.startLine.toString()
            } else {
                "${value.startLine}-${value.endLine}"
            }
            append("${value.filePath} [${value.side}] L$lineRange", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            val preview = value.body.lineSequence().firstOrNull().orEmpty()
            if (preview.isNotBlank()) {
                append("  $preview", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private inner class TimelineRenderer : ListCellRenderer<TimelineEntry> {
        override fun getListCellRendererComponent(
            list: JList<out TimelineEntry>,
            value: TimelineEntry?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) return JPanel()

            val row = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = list.background
                border = JBUI.Borders.empty(6, 0)
            }
            row.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyRight(10)
                add(createAvatarLabel(value.actor, value.avatarUrl))
                add(Box.createVerticalStrut(6))
                add(JPanel().apply {
                    preferredSize = JBUI.size(4, 1)
                    maximumSize = JBUI.size(4, Int.MAX_VALUE)
                    background = value.kind.accent
                })
            }, BorderLayout.WEST)

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyLeft(12)
            }

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(CodeReviewTimelineUIUtil.createTitleTextPane(value.actor, null, value.createdAt), BorderLayout.WEST)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            createBadgeLabel(value.badge)?.let { header.add(it, BorderLayout.EAST) }
            content.add(header)
            content.add(Box.createVerticalStrut(6))

            val summary = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            summary.add(JPanel().apply {
                preferredSize = JBUI.size(3, 18)
                maximumSize = JBUI.size(3, 18)
                background = value.kind.accent
            })
            summary.add(Box.createHorizontalStrut(8))
            summary.add(JLabel(value.title).apply {
                foreground = value.kind.accent
                font = UIUtil.getLabelFont()
            })
            value.details?.takeIf { it.isNotBlank() }?.let {
                summary.add(Box.createHorizontalStrut(8))
                summary.add(JLabel(it).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                })
            }
            content.add(summary)

            if (!value.diffHunk.isNullOrBlank() || !value.body.isNullOrBlank()) {
                content.add(Box.createVerticalStrut(8))
                content.add(createTimelineBody(value))
            }

            row.add(content, BorderLayout.CENTER)
            return row
        }
    }

    private fun createBadgeLabel(@NlsSafe text: String?): JComponent? {
        if (text.isNullOrBlank()) return null
        return JLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            border = JBUI.Borders.compound(
                RoundedLineBorder(JBColor.border(), 6, 1),
                JBUI.Borders.empty(2, 6)
            )
            background = JBColor(Color(242, 244, 247), Color(56, 59, 64))
            isOpaque = true
        }
    }

    private fun createTimelineBody(entry: TimelineEntry): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = entry.kind.cardBackground
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                RoundedLineBorder(entry.kind.accentSoft, 10, 1),
                JBUI.Borders.empty(10, 12)
            )
        }
        entry.filePath?.takeIf { it.isNotBlank() }?.let { path ->
            panel.add(JLabel(path).apply {
                foreground = entry.kind.accent
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })
        }
        entry.diffHunk?.takeIf { it.isNotBlank() }?.let { hunk ->
            if (!entry.filePath.isNullOrBlank()) {
                panel.add(Box.createVerticalStrut(6))
            }
            panel.add(JBLabel(UIUtil.toHtml(formatDiffHunk(hunk), 0)).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })
        }
        entry.body?.trim()?.takeIf { it.isNotBlank() }?.let { body ->
            if (!entry.diffHunk.isNullOrBlank() || !entry.filePath.isNullOrBlank()) {
                panel.add(Box.createVerticalStrut(8))
            }
            panel.add(JBLabel(UIUtil.toHtml(body.replace("\n", "<br/>"), 0)).apply {
                foreground = UIUtil.getLabelForeground()
            })
        }
        return panel
    }

    private fun formatDiffHunk(diffHunk: String): String {
        return diffHunk
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString("<br/>") { line ->
                val escaped = UIUtil.toHtml(line, 0).removePrefix("<html>").removeSuffix("</html>")
                when {
                    line.startsWith("+") -> "<span style='color:#4f9d69'>$escaped</span>"
                    line.startsWith("-") -> "<span style='color:#c66b6b'>$escaped</span>"
                    else -> escaped
                }
            }
    }
}
