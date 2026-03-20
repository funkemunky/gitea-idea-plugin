/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.CachingGiteaUserAvatarLoader
import com.github.leondevlifelog.gitea.services.GiteaIssueFullDetails
import com.github.leondevlifelog.gitea.services.GiteaIssueService
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.gitnex.tea4j.v2.models.Issue
import org.gitnex.tea4j.v2.models.Milestone
import org.gitnex.tea4j.v2.models.TimelineComment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

class GiteaIssueDetailsDialog(
    private val project: Project,
    private val context: GiteaPullRequestRepositoryContext,
    private val repositoryDetails: GiteaPullRequestRepositoryDetails,
    initialIssue: Issue,
    private val service: GiteaIssueService,
    private val onIssueUpdated: () -> Unit
) : DialogWrapper(project, true) {

    private var currentIssue: Issue = initialIssue
    private var fullDetails: GiteaIssueFullDetails? = null

    private val avatarLoader = CachingGiteaUserAvatarLoader.getInstance()
    private val avatarIcons = mutableMapOf<String, Icon>()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Header
    private val statusLabel = JBLabel()

    // Timeline
    private val timelineModel = DefaultListModel<TimelineEntry>()
    private val timelineList = JBList(timelineModel)

    // Comment input
    private val commentsEditor = JBTextArea(4, 60)
    private val commentButton = JButton(GiteaBundle.message("pull.request.details.comment"))

    // Metadata
    private val assigneesField = JBTextField()
    private val labelsField = JBTextField()
    private val milestoneCombo = ComboBox(CollectionComboBoxModel(createMilestoneOptions(), MilestoneOption.None))
    private val dueDateField = JBTextField()
    private val saveMetadataButton = JButton(GiteaBundle.message("pull.request.details.save.metadata"))

    // Actions
    private val refreshButton = JButton(GiteaBundle.message("pull.request.details.refresh"))
    private val openBrowserButton = JButton(GiteaBundle.message("pull.request.details.open.browser"))
    private val closeOrReopenButton = JButton()

    init {
        title = GiteaBundle.message("issue.details.title", initialIssue.number ?: 0)
        isModal = false
        init()
        updateUIFromIssue(currentIssue)
        loadDetails()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = JBUI.size(900, 600)

        // Top toolbar
        val toolBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(8)
            add(refreshButton)
            add(Box.createHorizontalStrut(8))
            add(openBrowserButton)
            add(Box.createHorizontalStrut(8))
            add(closeOrReopenButton)
            add(Box.createHorizontalGlue())
            add(statusLabel)
        }
        mainPanel.add(toolBar, BorderLayout.NORTH)

        // Timeline list
        timelineList.emptyText.text = GiteaBundle.message("issue.details.empty.conversation")
        timelineList.cellRenderer = TimelineRenderer()
        timelineList.isOpaque = false

        // Comment input panel
        val commentScroll = JBScrollPane(commentsEditor.apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = GiteaBundle.message("pull.request.details.comment.label")
        })
        commentScroll.preferredSize = JBUI.size(400, 90)

        val commentPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(commentScroll, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.emptyTop(4)
                add(Box.createHorizontalGlue())
                add(commentButton)
            }, BorderLayout.SOUTH)
        }

        val leftSide = JPanel(BorderLayout()).apply {
            add(JBScrollPane(timelineList), BorderLayout.CENTER)
            add(commentPanel, BorderLayout.SOUTH)
        }

        // Right side – metadata
        val collaboratorHint = repositoryDetails.collaborators.joinToString(", ") { it.loginName.orEmpty() }
        val labelHint = repositoryDetails.labels.joinToString(", ") { "${it.id}:${it.name}" }

        val metadataPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel(GiteaBundle.message("pull.request.details.field.assignees")).apply {
                    toolTipText = collaboratorHint
                },
                assigneesField.apply { toolTipText = collaboratorHint }
            )
            .addLabeledComponent(
                JBLabel(GiteaBundle.message("issue.details.field.labels")).apply { toolTipText = labelHint },
                labelsField.apply { toolTipText = labelHint }
            )
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.milestone"), milestoneCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.details.field.due.date"), dueDateField)
            .addComponent(saveMetadataButton)
            .panel
        metadataPanel.border = JBUI.Borders.emptyLeft(8)

        val rightScroll = JBScrollPane(metadataPanel)
        rightScroll.preferredSize = JBUI.size(260, 300)
        rightScroll.border = JBUI.Borders.empty()

        val splitter = com.intellij.ui.JBSplitter(false, 0.7f).apply {
            firstComponent = leftSide
            secondComponent = rightScroll
        }
        mainPanel.add(splitter, BorderLayout.CENTER)

        // Wire up actions
        refreshButton.addActionListener { loadDetails() }
        openBrowserButton.addActionListener {
            currentIssue.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
        }
        closeOrReopenButton.addActionListener { toggleIssueState() }
        commentButton.addActionListener { submitComment() }
        saveMetadataButton.addActionListener { saveMetadata() }

        return mainPanel
    }

    override fun createActions() = arrayOf(cancelAction)

    private fun updateUIFromIssue(issue: Issue) {
        currentIssue = issue
        val isOpen = issue.state.equals("open", ignoreCase = true)
        closeOrReopenButton.text = if (isOpen) {
            GiteaBundle.message("issue.menu.close")
        } else {
            GiteaBundle.message("issue.menu.reopen")
        }
        assigneesField.text = issue.assignees?.joinToString(", ") { it.loginName.orEmpty() }.orEmpty()
        val labelIds = issue.labels?.mapNotNull { it.id }?.joinToString(", ").orEmpty()
        labelsField.text = labelIds
        milestoneCombo.model = CollectionComboBoxModel(createMilestoneOptions(), findMilestoneOption(issue.milestone))
        issue.dueDate?.let { dueDateField.text = it.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString() }
    }

    private fun findMilestoneOption(milestone: org.gitnex.tea4j.v2.models.Milestone?): MilestoneOption {
        if (milestone == null) return MilestoneOption.None
        val named = createMilestoneOptions().filterIsInstance<MilestoneOption.Named>()
            .firstOrNull { it.milestone.id == milestone.id }
        return named ?: MilestoneOption.None
    }

    private fun loadDetails() {
        val number = currentIssue.number?.toLong() ?: return
        runBackground(
            GiteaBundle.message("issue.details.loading"),
            onSuccess = { details ->
                fullDetails = details
                updateUIFromIssue(details.issue)
                populateTimeline(details)
            },
            task = { service.getIssueDetails(context, number) }
        )
    }

    private fun populateTimeline(details: GiteaIssueFullDetails) {
        val entries = mutableListOf<TimelineEntry>()
        // Issue body at top
        entries.add(
            TimelineEntry(
                createdAt = details.issue.createdAt,
                actor = details.issue.user?.loginName ?: "-",
                title = GiteaBundle.message("issue.details.opened"),
                body = details.issue.body?.takeIf { it.isNotBlank() },
                kind = TimelineEntry.Kind.OPENED,
                avatarUrl = details.issue.user?.avatarUrl
            )
        )

        for (item in details.timeline.sortedBy { it.createdAt }) {
            val entry = timelineCommentToEntry(item) ?: continue
            entries.add(entry)
        }

        timelineModel.clear()
        entries.forEach(timelineModel::addElement)
    }

    private fun timelineCommentToEntry(item: TimelineComment): TimelineEntry? {
        val actor = item.user?.loginName ?: "-"
        val avatarUrl = item.user?.avatarUrl
        return when (item.type) {
            "comment" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = GiteaBundle.message("issue.timeline.comment"),
                body = item.body?.takeIf { it.isNotBlank() },
                kind = TimelineEntry.Kind.COMMENT,
                avatarUrl = avatarUrl
            )
            "close" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = GiteaBundle.message("issue.timeline.closed"),
                body = null,
                kind = TimelineEntry.Kind.CLOSED,
                avatarUrl = avatarUrl
            )
            "reopen" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = GiteaBundle.message("issue.timeline.reopened"),
                body = null,
                kind = TimelineEntry.Kind.OPENED,
                avatarUrl = avatarUrl
            )
            "label", "unlabel" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = if (item.type == "label") {
                    GiteaBundle.message("issue.timeline.labeled")
                } else {
                    GiteaBundle.message("issue.timeline.unlabeled")
                },
                body = item.label?.name,
                kind = TimelineEntry.Kind.EVENT,
                avatarUrl = avatarUrl
            )
            "milestone" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = GiteaBundle.message("issue.timeline.milestone"),
                body = item.milestone?.title,
                kind = TimelineEntry.Kind.EVENT,
                avatarUrl = avatarUrl
            )
            "assignees" -> TimelineEntry(
                createdAt = item.createdAt,
                actor = actor,
                title = GiteaBundle.message("issue.timeline.assignee.changed"),
                body = null,
                kind = TimelineEntry.Kind.EVENT,
                avatarUrl = avatarUrl
            )
            else -> if (!item.body.isNullOrBlank()) {
                TimelineEntry(
                    createdAt = item.createdAt,
                    actor = actor,
                    title = item.type ?: "event",
                    body = item.body,
                    kind = TimelineEntry.Kind.EVENT,
                    avatarUrl = avatarUrl
                )
            } else {
                null
            }
        }
    }

    private fun toggleIssueState() {
        val isOpen = currentIssue.state.equals("open", ignoreCase = true)
        val newState = if (isOpen) "closed" else "open"
        val number = currentIssue.number?.toLong() ?: return
        runBackground(
            GiteaBundle.message("issue.updating"),
            onSuccess = { updated ->
                updateUIFromIssue(updated)
                onIssueUpdated()
                loadDetails()
            },
            task = { service.updateIssueState(context, number, newState) }
        )
    }

    private fun submitComment() {
        val text = commentsEditor.text.trim()
        if (text.isBlank()) return
        val number = currentIssue.number?.toLong() ?: return
        runBackground(
            GiteaBundle.message("pull.request.details.submitting"),
            onSuccess = { _ ->
                commentsEditor.text = ""
                loadDetails()
            },
            task = { service.addComment(context, number, text) }
        )
    }

    private fun saveMetadata() {
        val number = currentIssue.number?.toLong() ?: return
        val assignees = assigneesField.text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val labelIds = labelsField.text.split(",").mapNotNull { it.trim().toLongOrNull() }
            .takeIf { labelsField.text.isNotBlank() }
        val milestoneId = (milestoneCombo.selectedItem as? MilestoneOption.Named)?.milestone?.id
        val dueDate = dueDateField.text.trim().takeIf { it.isNotBlank() }?.let { text ->
            try {
                val localDate = LocalDate.parse(text)
                Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
            } catch (_: DateTimeParseException) {
                null
            }
        }
        runBackground(
            GiteaBundle.message("pull.request.details.saving"),
            onSuccess = { updated ->
                updateUIFromIssue(updated)
                onIssueUpdated()
            },
            task = {
                service.updateIssueMetadata(
                    context = context,
                    issueNumber = number,
                    title = null,
                    body = null,
                    assignees = assignees,
                    milestoneId = milestoneId,
                    dueDate = dueDate,
                    labelIds = labelIds
                )
            }
        )
    }

    private fun createMilestoneOptions(): List<MilestoneOption> {
        return listOf(MilestoneOption.None) + repositoryDetails.milestones.map { MilestoneOption.Named(it) }
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

    private fun <T> runBackground(title: String, onSuccess: (T) -> Unit, task: () -> T) {
        setBusy(true, title)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
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
                        GiteaNotifications.showError(project, null, GiteaBundle.message("issue.error.title"), t)
                    }
                }
            }
        })
    }

    private fun setBusy(busy: Boolean, text: String) {
        statusLabel.text = text
        listOf(refreshButton, openBrowserButton, closeOrReopenButton, commentButton, saveMetadataButton)
            .forEach { it.isEnabled = !busy }
        assigneesField.isEnabled = !busy
        labelsField.isEnabled = !busy
        milestoneCombo.isEnabled = !busy
        dueDateField.isEnabled = !busy
        commentsEditor.isEnabled = !busy
        timelineList.isEnabled = !busy
    }

    private sealed interface MilestoneOption {
        data object None : MilestoneOption {
            override fun toString() = GiteaBundle.message("pull.request.form.none")
        }
        data class Named(val milestone: Milestone) : MilestoneOption {
            override fun toString() = milestone.title.orEmpty()
        }
    }

    private data class TimelineEntry(
        val createdAt: Date?,
        val actor: String,
        val title: String,
        val body: String?,
        val kind: Kind,
        val avatarUrl: String? = null
    ) {
        enum class Kind(val accent: JBColor, val cardBackground: JBColor) {
            OPENED(
                accent = JBColor(Color(74, 120, 193), Color(102, 153, 255)),
                cardBackground = JBColor(Color(250, 252, 255), Color(35, 39, 45))
            ),
            COMMENT(
                accent = JBColor(Color(80, 125, 191), Color(100, 170, 255)),
                cardBackground = JBColor(Color(251, 253, 255), Color(34, 38, 43))
            ),
            EVENT(
                accent = JBColor(Color(120, 127, 137), Color(145, 153, 165)),
                cardBackground = JBColor(Color(250, 250, 251), Color(34, 36, 39))
            ),
            CLOSED(
                accent = JBColor(Color(146, 87, 87), Color(209, 118, 118)),
                cardBackground = JBColor(Color(253, 250, 250), Color(38, 33, 33))
            )
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
                border = JBUI.Borders.empty(4, 0)
            }

            row.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyRight(10)
                add(createAvatarLabel(value.actor, value.avatarUrl), BorderLayout.NORTH)
            }, BorderLayout.WEST)

            val content = JPanel(BorderLayout()).apply {
                isOpaque = false
            }

            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyBottom(2)
            }
            header.add(JLabel(value.actor).apply {
                font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
                foreground = UIUtil.getLabelForeground()
            })
            header.add(Box.createHorizontalStrut(8))
            header.add(JLabel(value.title).apply {
                foreground = value.kind.accent
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })
            value.createdAt?.let { date ->
                header.add(Box.createHorizontalStrut(8))
                header.add(JLabel(formatter.format(date)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                })
            }
            content.add(header, BorderLayout.NORTH)

            value.body?.trim()?.takeIf { it.isNotBlank() }?.let { body ->
                val bodyPanel = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = value.kind.cardBackground
                    border = JBUI.Borders.compound(
                        RoundedLineBorder(JBColor.border(), 6, 1),
                        JBUI.Borders.empty(4, 8)
                    )
                    add(JBLabel(UIUtil.toHtml(body.replace("\n", "<br/>"), 0)).apply {
                        foreground = UIUtil.getLabelForeground()
                    }, BorderLayout.CENTER)
                }
                content.add(bodyPanel, BorderLayout.CENTER)
            }
            row.add(content, BorderLayout.CENTER)
            return row
        }
    }
}
