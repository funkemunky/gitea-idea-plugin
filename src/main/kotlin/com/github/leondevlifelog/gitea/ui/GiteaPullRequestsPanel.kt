/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.github.leondevlifelog.gitea.services.GiteaPullRequestService
import com.github.leondevlifelog.gitea.util.GiteaNotificationIdsHolder
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.gitnex.tea4j.v2.models.PullRequest
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Locale
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

class GiteaPullRequestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = project.service<GiteaPullRequestService>()
    private val repositoryModel = CollectionComboBoxModel<GiteaPullRequestRepositoryContext>()
    private val repositoryCombo = com.intellij.openapi.ui.ComboBox(repositoryModel)
    private val refreshButton = JButton(GiteaBundle.message("pull.request.refresh"))
    private val createButton = JButton(GiteaBundle.message("pull.request.create"))
    private val statusLabel = JBLabel()
    private val tabs = JBTabbedPane()
    private val openListModel = DefaultListModel<PullRequest>()
    private val closedListModel = DefaultListModel<PullRequest>()
    private val openList = JBList(openListModel)
    private val closedList = JBList(closedListModel)
    private val detailsCache = linkedMapOf<String, GiteaPullRequestRepositoryDetails>()

    init {
        border = JBUI.Borders.empty(8)
        add(buildToolbar(), BorderLayout.NORTH)
        configureList(openList)
        configureList(closedList)
        tabs.addTab(GiteaBundle.message("pull.request.tab.open"), JBScrollPane(openList))
        tabs.addTab(GiteaBundle.message("pull.request.tab.closed"), JBScrollPane(closedList))
        add(tabs, BorderLayout.CENTER)

        repositoryCombo.renderer = RepositoryRenderer()

        repositoryCombo.addActionListener {
            if (repositoryCombo.selectedItem != null) {
                refreshPullRequests()
            }
        }
        refreshButton.addActionListener { refreshPullRequests(forceReloadDetails = true) }
        createButton.addActionListener { createPullRequest() }

        reloadRepositories()
    }

    private fun buildToolbar(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(GiteaBundle.message("pull.request.repository"), repositoryCombo)
            .addComponentToRightColumn(refreshButton)
            .addComponentToRightColumn(createButton)
            .addComponent(statusLabel)
            .panel
    }

    private fun reloadRepositories() {
        runBackground(
            GiteaBundle.message("pull.request.loading.repositories"),
            onSuccess = { repositories ->
                repositoryModel.removeAll()
                repositories.forEach(repositoryModel::add)
                statusLabel.text = when {
                    repositories.isEmpty() -> GiteaBundle.message("pull.request.no.repositories")
                    else -> GiteaBundle.message("pull.request.repositories.loaded", repositories.size)
                }
                repositoryCombo.isEnabled = repositories.isNotEmpty()
                refreshButton.isEnabled = repositories.isNotEmpty()
                createButton.isEnabled = repositories.isNotEmpty()
                if (repositories.isNotEmpty()) {
                    repositoryCombo.selectedItem = repositories.first()
                    refreshPullRequests()
                }
            },
            task = { service.findRepositories() }
        )
    }

    private fun refreshPullRequests(forceReloadDetails: Boolean = false) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        runBackground(
            GiteaBundle.message("pull.request.loading"),
            onSuccess = { result ->
                if (forceReloadDetails || !detailsCache.containsKey(context.gitRepository.root.path)) {
                    detailsCache[context.gitRepository.root.path] = result.details
                }
                openListModel.clear()
                closedListModel.clear()
                result.openPullRequests.forEach(openListModel::addElement)
                result.closedPullRequests.forEach(closedListModel::addElement)
                statusLabel.text = GiteaBundle.message(
                    "pull.request.loaded.split",
                    result.openPullRequests.size,
                    result.closedPullRequests.size
                )
            },
            task = {
                PullRequestLoadResult(
                    details = if (forceReloadDetails || !detailsCache.containsKey(context.gitRepository.root.path)) {
                        service.loadRepositoryDetails(context)
                    } else {
                        detailsCache.getValue(context.gitRepository.root.path)
                    },
                    openPullRequests = service.listPullRequests(context, "open"),
                    closedPullRequests = service.listPullRequests(context, "closed")
                )
            }
        )
    }

    private fun createPullRequest() {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        val details = detailsCache[context.gitRepository.root.path]
        if (details == null) {
            refreshPullRequests(forceReloadDetails = true)
            return
        }
        val dialog = GiteaCreatePullRequestDialog(context, details)
        if (!dialog.showAndGet()) return

        val request = dialog.createRequest()
        runBackground(
            GiteaBundle.message("pull.request.creating"),
            onSuccess = { pullRequest ->
                GiteaNotifications.showInfoURL(
                    project,
                    GiteaNotificationIdsHolder.PULL_REQUEST_CREATED,
                    GiteaBundle.message("pull.request.created.title"),
                    GiteaBundle.message("pull.request.created.message", pullRequest.number ?: 0),
                    pullRequest.htmlUrl ?: context.coordinates.toUrl()
                )
                refreshPullRequests(forceReloadDetails = false)
            },
            task = { service.createPullRequest(context, request) }
        )
    }

    private fun <T> runBackground(
        title: String,
        onSuccess: (T) -> Unit,
        task: () -> T
    ) {
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
                        GiteaNotifications.showError(
                            project,
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
        repositoryCombo.isEnabled = !busy
        refreshButton.isEnabled = !busy
        createButton.isEnabled = !busy && repositoryCombo.itemCount > 0
        openList.isEnabled = !busy
        closedList.isEnabled = !busy
    }

    private fun configureList(list: JBList<PullRequest>) {
        list.emptyText.text = GiteaBundle.message("pull.request.empty")
        list.cellRenderer = PullRequestRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                    val row = list.locationToIndex(e.point)
                    if (row >= 0) {
                        list.selectedIndex = row
                        list.selectedValue?.let { openPullRequestDetails(it) }
                    }
                    return
                }
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                    list.selectedValue?.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowPopup(list, e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowPopup(list, e)
            }
        })
    }

    private fun openPullRequestDetails(pullRequest: PullRequest) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        val details = detailsCache[context.gitRepository.root.path]
        if (details == null) {
            refreshPullRequests(forceReloadDetails = true)
            return
        }

        GiteaPullRequestDetailsDialog(
            context = context,
            repositoryDetails = details,
            initialPullRequest = pullRequest,
            service = service,
            onPullRequestUpdated = { refreshPullRequests(forceReloadDetails = false) }
        ).show()
    }

    private fun maybeShowPopup(list: JBList<PullRequest>, event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val row = list.locationToIndex(event.point)
        if (row < 0) return
        list.selectedIndex = row
        val pr = list.selectedValue ?: return

        val menu = JPopupMenu()
        val viewInBrowser = JMenuItem(GiteaBundle.message("pull.request.menu.view.browser"))
        viewInBrowser.addActionListener {
            pr.htmlUrl?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse)
        }
        menu.add(viewInBrowser)

        val isOpen = pr.state.equals("open", ignoreCase = true)
        val actionLabel = if (isOpen) {
            GiteaBundle.message("pull.request.menu.close")
        } else {
            GiteaBundle.message("pull.request.menu.reopen")
        }
        val updateStateItem = JMenuItem(actionLabel)
        updateStateItem.addActionListener {
            updatePullRequestState(pr, if (isOpen) "closed" else "open")
        }
        menu.add(updateStateItem)
        menu.show(list, event.x, event.y)
    }

    private fun updatePullRequestState(pullRequest: PullRequest, state: String) {
        val context = repositoryCombo.selectedItem as? GiteaPullRequestRepositoryContext ?: return
        runBackground(
            GiteaBundle.message("pull.request.updating"),
            onSuccess = { updated ->
                val msgKey = if (state == "closed") "pull.request.updated.closed" else "pull.request.updated.reopened"
                GiteaNotifications.showInfo(
                    project,
                    GiteaNotificationIdsHolder.PULL_REQUEST_CREATED,
                    GiteaBundle.message("pull.request.created.title"),
                    GiteaBundle.message(msgKey, updated.number ?: 0)
                )
                refreshPullRequests(forceReloadDetails = false)
            },
            task = { service.updatePullRequestState(context, pullRequest, state) }
        )
    }

    private data class PullRequestLoadResult(
        val details: GiteaPullRequestRepositoryDetails,
        val openPullRequests: List<PullRequest>,
        val closedPullRequests: List<PullRequest>
    )

    private class PullRequestRenderer : ColoredListCellRenderer<PullRequest>() {
        private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        override fun customizeCellRenderer(
            list: JList<out PullRequest>,
            value: PullRequest?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append("#${value.number ?: "-"} ${value.title.orEmpty()}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ${value.state.orEmpty()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("\n")
            val author = value.user?.loginName ?: "-"
            val head = value.head?.label ?: value.head?.ref ?: "-"
            val base = value.base?.label ?: value.base?.ref ?: "-"
            append("$author  $head -> $base", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.updatedAt?.let {
                append("  ${formatter.format(it)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
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
