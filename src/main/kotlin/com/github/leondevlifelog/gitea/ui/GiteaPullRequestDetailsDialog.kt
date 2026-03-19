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
import com.github.leondevlifelog.gitea.util.GiteaNotifications
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
import git4idea.branch.GitBrancher
import org.gitnex.tea4j.v2.models.ChangedFile
import org.gitnex.tea4j.v2.models.Commit
import org.gitnex.tea4j.v2.models.Label
import org.gitnex.tea4j.v2.models.Milestone
import org.gitnex.tea4j.v2.models.PullRequest
import org.gitnex.tea4j.v2.models.PullReview
import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

class GiteaPullRequestDetailsDialog(
    private val context: GiteaPullRequestRepositoryContext,
    private val repositoryDetails: GiteaPullRequestRepositoryDetails,
    initialPullRequest: PullRequest,
    private val service: GiteaPullRequestService,
    private val onPullRequestUpdated: () -> Unit
) : DialogWrapper(context.gitRepository.project, true) {

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val statusLabel = JBLabel()
    private val headerLabel = JBLabel()
    private val subtitleLabel = JBLabel()
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
    private val saveMetadataButton = JButton(GiteaBundle.message("pull.request.details.save.metadata"))
    private val clearReviewersButton = JButton(GiteaBundle.message("pull.request.details.clear.reviewers"))
    private val commentButton = JButton(GiteaBundle.message("pull.request.details.comment"))
    private val approveButton = JButton(GiteaBundle.message("pull.request.details.approve"))
    private val requestChangesButton = JButton(GiteaBundle.message("pull.request.details.request.changes"))
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
            .addComponent(headerLabel)
            .addComponent(subtitleLabel)
            .addComponent(statusLabel)
            .addComponent(controls)
            .panel
    }

    private fun buildBody(): JComponent {
        val conversationPanel = JPanel(BorderLayout()).apply {
            timelineList.cellRenderer = TimelineRenderer()
            add(JBScrollPane(timelineList), BorderLayout.CENTER)
        }
        val commitsPanel = JPanel(BorderLayout()).apply {
            commitsList.cellRenderer = CommitRenderer()
            add(JBScrollPane(commitsList), BorderLayout.CENTER)
        }
        val filesPanel = JPanel(BorderLayout()).apply {
            filesList.cellRenderer = FileRenderer()
            add(JBScrollPane(filesList), BorderLayout.WEST)
            add(diffRequestPanel.component, BorderLayout.CENTER)
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
        commentButton.addActionListener { submitComment() }
        approveButton.addActionListener { submitReview("APPROVE") }
        requestChangesButton.addActionListener { submitReview("REQUEST_CHANGES") }
        closeOrReopenButton.addActionListener { toggleState() }
        mergeButton.addActionListener { mergePullRequest() }
        showDiffMessage(GiteaBundle.message("pull.request.details.diff.select"))
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
        val number = pr.number ?: 0L
        headerLabel.text = "#$number ${pr.title.orEmpty()}"
        val state = pr.state.orEmpty()
        val author = pr.user?.loginName ?: "-"
        val source = pr.head?.label ?: pr.head?.ref ?: "-"
        val target = pr.base?.label ?: pr.base?.ref ?: "-"
        subtitleLabel.text = "$state  $author  $source -> $target"
        statusLabel.text = GiteaBundle.message(
            "pull.request.details.summary",
            details.commits.size,
            details.files.size,
            details.comments.size
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
        entries += TimelineEntry(
            createdAt = pr.createdAt,
            actor = pr.user?.loginName ?: "-",
            action = "opened this pull request",
            body = pr.body?.takeIf { it.isNotBlank() } ?: GiteaBundle.message("pull.request.details.no.description")
        )
        details.timeline.forEach { event ->
            val action = buildString {
                append(event.type ?: "event")
                event.refAction?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
            }
            val body = event.body
                ?: event.newTitle
                ?: event.newRef
                ?: event.oldTitle
                ?: event.refCommitSha
                ?: event.refIssue?.title
            entries += TimelineEntry(
                createdAt = event.createdAt,
                actor = event.user?.loginName ?: event.assignee?.loginName ?: "-",
                action = action,
                body = body
            )
        }
        details.comments.forEach { comment ->
            entries += TimelineEntry(
                createdAt = comment.createdAt,
                actor = comment.user?.loginName ?: "-",
                action = "commented",
                body = comment.body
            )
        }
        details.reviews.forEach { review ->
            entries += TimelineEntry(
                createdAt = review.updatedAt ?: review.submittedAt,
                actor = review.user?.loginName ?: review.team?.name ?: "-",
                action = (review.state ?: "comment").lowercase().replace('_', ' '),
                body = review.body
            )
        }
        details.commits.forEach { commit ->
            entries += TimelineEntry(
                createdAt = commit.created,
                actor = commit.author?.loginName ?: commit.commit?.author?.name ?: "-",
                action = "added a commit",
                body = "${commit.sha?.take(8) ?: "-"} ${commit.commit?.message?.lineSequence()?.firstOrNull().orEmpty()}"
            )
        }

        timelineModel.clear()
        entries.sortedBy { it.createdAt ?: Date(0) }.forEach(timelineModel::addElement)
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
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = {
                commentsEditor.text = ""
                refreshDetails()
            },
            task = { service.submitReview(context, number, event, text) }
        )
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
            saveMetadataButton,
            clearReviewersButton,
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
        commitsList.isEnabled = !busy
        filesList.isEnabled = !busy
    }

    private data class GitCommandOutput(val exitCode: Int, val output: String)
    private data class TimelineEntry(
        val createdAt: Date?,
        val actor: String,
        val action: String,
        val body: String?
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

    private inner class TimelineRenderer : ColoredListCellRenderer<TimelineEntry>() {
        override fun customizeCellRenderer(
            list: JList<out TimelineEntry>,
            value: TimelineEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append(value.actor, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(" ${value.createdAt?.let(formatter::format) ?: "-"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("\n")
            append(value.action, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.body?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            border = JBUI.Borders.empty(8, 8, 8, 8)
        }
    }
}
