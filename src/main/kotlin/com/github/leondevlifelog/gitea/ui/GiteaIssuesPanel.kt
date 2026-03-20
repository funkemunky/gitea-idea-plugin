/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.CachingGiteaUserAvatarLoader
import com.github.leondevlifelog.gitea.services.GiteaIssueService
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.github.leondevlifelog.gitea.util.GiteaNotificationIdsHolder
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.gitnex.tea4j.v2.models.Issue
import org.gitnex.tea4j.v2.models.Milestone
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
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
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer

class GiteaIssuesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = project.service<GiteaIssueService>()
    private val avatarLoader = CachingGiteaUserAvatarLoader.getInstance()
    private val avatarIcons = mutableMapOf<String, Icon>()
    private val repositoryModel = CollectionComboBoxModel<GiteaPullRequestRepositoryContext>()
    private val repositoryCombo = com.intellij.openapi.ui.ComboBox(repositoryModel)
    private val refreshButton = JButton(GiteaBundle.message("issue.refresh"))
    private val createButton = JButton(GiteaBundle.message("issue.create"))
    private val searchField = SearchTextField(false)
    private val statusLabel = JBLabel()
    private val tabs = JBTabbedPane()
    private val openListModel = DefaultListModel<Issue>()
    private val closedListModel = DefaultListModel<Issue>()
    private val openList = JBList(openListModel)
    private val closedList = JBList(closedListModel)
    private val detailsCache = linkedMapOf<String, GiteaPullRequestRepositoryDetails>()
    private var allOpenIssues: List<Issue> = emptyList()
    private var allClosedIssues: List<Issue> = emptyList()

    init {
        border = JBUI.Borders.empty(8)
        add(buildToolbar(), BorderLayout.NORTH)
        configureList(openList)
        configureList(closedList)
        tabs.addTab(GiteaBundle.message("issue.tab.open"), JBScrollPane(openList))
        tabs.addTab(GiteaBundle.message("issue.tab.closed"), JBScrollPane(closedList))
        add(tabs, BorderLayout.CENTER)

        repositoryCombo.renderer = RepositoryRenderer()
        repositoryCombo.addActionListener {
            if (repositoryCombo.selectedItem != null) {
                refreshIssues()
            }
        }
        refreshButton.addActionListener { refreshIssues(forceReloadDetails = true) }
        createButton.addActionListener { createIssue() }
        searchField.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })

        reloadRepositories()
    }

    private fun buildToolbar(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(GiteaBundle.message("issue.repository"), repositoryCombo)
            .addLabeledComponent("Search", searchField)
            .addComponentToRightColumn(refreshButton)
            .addComponentToRightColumn(createButton)
            .addComponent(statusLabel)
            .panel
    }

    private fun reloadRepositories() {
        runBackground(
            GiteaBundle.message("issue.loading.repositories"),
            onSuccess = { repositories ->
                repositoryModel.removeAll()
                repositories.forEach(repositoryModel::add)
                statusLabel.text = when {
                    repositories.isEmpty() -> GiteaBundle.message("issue.no.repositories")
                    else -> GiteaBundle.message("pull.request.repositories.loaded", repositories.size)
                }
                repositoryCombo.isEnabled = repositories.isNotEmpty()
                refreshButton.isEnabled = repositories.isNotEmpty()
                createButton.isEnabled = repositories.isNotEmpty()
                if (repositories.isNotEmpty()) {
                    repositoryCombo.selectedItem = repositories.first()
                    refreshIssues()
                }
            },
            task = { service.findRepositories() }
        )
    }

    private fun refreshIssues(forceReloadDetails: Boolean = false) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        runBackground(
            GiteaBundle.message("issue.loading"),
            onSuccess = { result ->
                if (forceReloadDetails || !detailsCache.containsKey(context.gitRepository.root.path)) {
                    detailsCache[context.gitRepository.root.path] = result.details
                }
                allOpenIssues = result.openIssues
                allClosedIssues = result.closedIssues
                applyFilter()
                statusLabel.text = GiteaBundle.message(
                    "issue.loaded.split",
                    result.openIssues.size,
                    result.closedIssues.size
                )
            },
            task = {
                val prService = project.service<com.github.leondevlifelog.gitea.services.GiteaPullRequestService>()
                IssueLoadResult(
                    details = if (forceReloadDetails || !detailsCache.containsKey(context.gitRepository.root.path)) {
                        prService.loadRepositoryDetails(context)
                    } else {
                        detailsCache.getValue(context.gitRepository.root.path)
                    },
                    openIssues = service.listIssues(context, "open"),
                    closedIssues = service.listIssues(context, "closed")
                )
            }
        )
    }

    private fun createIssue() {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        val details = detailsCache[context.gitRepository.root.path]
        if (details == null) {
            refreshIssues(forceReloadDetails = true)
            return
        }
        val dialog = GiteaCreateIssueDialog(project, context, details)
        if (!dialog.showAndGet()) return

        runBackground(
            GiteaBundle.message("issue.creating"),
            onSuccess = { created ->
                GiteaNotifications.showInfoURL(
                    project,
                    GiteaNotificationIdsHolder.PULL_REQUEST_CREATED,
                    GiteaBundle.message("issue.created.title"),
                    GiteaBundle.message("issue.created.message", created.number ?: 0),
                    created.htmlUrl ?: ""
                )
                refreshIssues(forceReloadDetails = false)
            },
            task = { service.createIssue(context, dialog.createOption()) }
        )
    }

    private fun openIssueDetails(issue: Issue) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        val details = detailsCache[context.gitRepository.root.path]
        if (details == null) {
            refreshIssues(forceReloadDetails = true)
            return
        }
        GiteaIssueDetailsDialog(
            project = project,
            context = context,
            repositoryDetails = details,
            initialIssue = issue,
            service = service,
            onIssueUpdated = { refreshIssues(forceReloadDetails = false) }
        ).show()
    }

    private fun configureList(list: JBList<Issue>) {
        list.emptyText.text = GiteaBundle.message("issue.empty")
        list.cellRenderer = IssueRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    val row = list.locationToIndex(e.point)
                    if (row >= 0) {
                        list.selectedIndex = row
                        list.selectedValue?.let { openIssueDetails(it) }
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(list, e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(list, e)
        })
    }

    private fun maybeShowPopup(list: JBList<Issue>, event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val row = list.locationToIndex(event.point)
        if (row < 0) return
        list.selectedIndex = row
        val issue = list.selectedValue ?: return

        val menu = JPopupMenu()
        val viewInBrowser = JMenuItem(GiteaBundle.message("issue.menu.view.browser"))
        viewInBrowser.addActionListener {
            issue.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
        }
        menu.add(viewInBrowser)

        val isOpen = issue.state.equals("open", ignoreCase = true)
        val stateLabel = if (isOpen) GiteaBundle.message("issue.menu.close") else GiteaBundle.message("issue.menu.reopen")
        val stateItem = JMenuItem(stateLabel)
        stateItem.addActionListener {
            updateIssueState(issue, if (isOpen) "closed" else "open")
        }
        menu.add(stateItem)
        menu.show(list, event.x, event.y)
    }

    private fun updateIssueState(issue: Issue, state: String) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        val number = issue.number?.toLong() ?: return
        runBackground(
            GiteaBundle.message("issue.updating"),
            onSuccess = { updated ->
                val msgKey = if (state == "closed") "issue.updated.closed" else "issue.updated.reopened"
                GiteaNotifications.showInfo(
                    project,
                    GiteaNotificationIdsHolder.PULL_REQUEST_CREATED,
                    GiteaBundle.message("issue.created.title"),
                    GiteaBundle.message(msgKey, updated.number ?: 0)
                )
                refreshIssues(forceReloadDetails = false)
            },
            task = { service.updateIssueState(context, number, state) }
        )
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase(Locale.getDefault())
        val openFiltered = allOpenIssues.filter { matchesQuery(it, query) }
        val closedFiltered = allClosedIssues.filter { matchesQuery(it, query) }
        openListModel.clear()
        openFiltered.forEach(openListModel::addElement)
        closedListModel.clear()
        closedFiltered.forEach(closedListModel::addElement)
    }

    private fun matchesQuery(issue: Issue, query: String): Boolean {
        if (query.isBlank()) return true
        val target = buildString {
            append(issue.number ?: "")
            append(' ')
            append(issue.title.orEmpty())
            append(' ')
            append(issue.user?.loginName.orEmpty())
        }.lowercase(Locale.getDefault())
        return target.contains(query)
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

    private fun setBusy(busy: Boolean, message: String) {
        statusLabel.text = message
        repositoryCombo.isEnabled = !busy
        refreshButton.isEnabled = !busy
        createButton.isEnabled = !busy && repositoryCombo.itemCount > 0
        openList.isEnabled = !busy
        closedList.isEnabled = !busy
    }

    private fun createAvatarLabel(name: String, avatarUrl: String?, list: JList<out Issue>): JComponent {
        val label = JLabel(name.take(1).uppercase(Locale.getDefault())).apply {
            horizontalAlignment = JLabel.CENTER
            verticalAlignment = JLabel.CENTER
            foreground = UIUtil.getLabelForeground()
            background = JBColor(Color(232, 235, 241), Color(66, 70, 77))
            isOpaque = true
            preferredSize = JBUI.size(26, 26)
            minimumSize = preferredSize
            maximumSize = preferredSize
            border = RoundedLineBorder(JBColor.border(), 13, 1)
        }
        avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
            avatarIcons[url]?.let { icon ->
                label.text = ""
                label.icon = icon
            } ?: avatarLoader.requestAvatar(url).thenAccept { image ->
                if (image != null) {
                    avatarIcons[url] = ImageIcon(image)
                    javax.swing.SwingUtilities.invokeLater { list.repaint() }
                }
            }
        }
        return label
    }

    private data class IssueLoadResult(
        val details: GiteaPullRequestRepositoryDetails,
        val openIssues: List<Issue>,
        val closedIssues: List<Issue>
    )

    private inner class IssueRenderer : ListCellRenderer<Issue> {
        private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        override fun getListCellRendererComponent(
            list: JList<out Issue>,
            value: Issue?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ): Component {
            if (value == null) return JPanel()

            val row = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (selected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(8, 10)
            }
            row.add(createAvatarLabel(value.user?.loginName ?: "-", value.user?.avatarUrl, list), BorderLayout.WEST)

            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyLeft(10)
            }

            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
            }
            header.add(JLabel("#${value.number ?: "-"} ${value.title.orEmpty()}").apply {
                foreground = if (selected) list.selectionForeground else UIUtil.getLabelForeground()
                font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
            })
            header.add(Box.createHorizontalStrut(8))
            header.add(JLabel(value.state.orEmpty().uppercase(Locale.getDefault())).apply {
                foreground = if (value.state.equals("open", ignoreCase = true)) {
                    JBColor(Color(62, 132, 77), Color(111, 190, 118))
                } else {
                    if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
                }
                border = JBUI.Borders.compound(
                    RoundedLineBorder(JBColor.border(), 6, 1),
                    JBUI.Borders.empty(2, 6)
                )
            })
            content.add(header)
            content.add(Box.createVerticalStrut(4))

            // Labels row
            val labelNames = value.labels?.mapNotNull { it.name }?.joinToString(", ")
            if (!labelNames.isNullOrBlank()) {
                content.add(JLabel(labelNames).apply {
                    foreground = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
                    font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                })
                content.add(Box.createVerticalStrut(2))
            }

            content.add(JLabel("${value.user?.loginName ?: "-"}  ${value.updatedAt?.let(formatter::format) ?: "-"}").apply {
                foreground = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })

            row.add(content, BorderLayout.CENTER)

            // Comment count
            val comments = value.comments ?: 0
            if (comments > 0) {
                row.add(JLabel("💬 $comments").apply {
                    foreground = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
                    font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                    border = JBUI.Borders.emptyRight(4)
                }, BorderLayout.EAST)
            }

            return row
        }
    }

    private class RepositoryRenderer : ColoredListCellRenderer<GiteaPullRequestRepositoryContext>() {
        override fun customizeCellRenderer(
            list: JList<out GiteaPullRequestRepositoryContext>,
            value: GiteaPullRequestRepositoryContext?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${value.remote.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
