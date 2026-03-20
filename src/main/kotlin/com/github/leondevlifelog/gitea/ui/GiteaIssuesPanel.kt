/*
 * Copyright (c) 2023-2026. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.authentication.GiteaAccountsUtil
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccountManager
import com.github.leondevlifelog.gitea.services.GiteaSettings
import com.github.leondevlifelog.gitea.util.GiteaUrlUtil
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import git4idea.GitUtil
import kotlinx.coroutines.*
import org.gitnex.tea4j.v2.models.Issue
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class GiteaIssuesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = CollectionListModel<Issue>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No issues found"
        cellRenderer = object : ColoredListCellRenderer<Issue>() {
            override fun customizeCellRenderer(
                list: JList<out Issue>,
                value: Issue,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                append("#${value.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.title)
                append(" by ${value.user.login}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        add(JBScrollPane(list), BorderLayout.CENTER)
        
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val selected = list.selectedValue ?: return false
                BrowserUtil.browse(selected.htmlUrl)
                return true
            }
        }.installOn(list)

        refresh()
    }

    fun refresh() {
        cs.launch {
            list.setPaintBusy(true)
            try {
                val issues = fetchIssues()
                runInEdt {
                    listModel.replaceAll(issues)
                }
            } catch (e: Exception) {
                runInEdt {
                    list.emptyText.text = "Error fetching issues: ${e.message}"
                }
            } finally {
                list.setPaintBusy(false)
            }
        }
    }

    private suspend fun fetchIssues(): List<Issue> = withContext(Dispatchers.IO) {
        val repositoryManager = GitUtil.getRepositoryManager(project)
        val repository = repositoryManager.repositories.firstOrNull() ?: return@withContext emptyList()
        val remote = repository.remotes.firstOrNull() ?: return@withContext emptyList()
        val remoteUrl = remote.firstUrl ?: return@withContext emptyList()
        
        val repoPath = GiteaUrlUtil.getUserAndRepositoryFromRemoteUrl(remoteUrl) ?: return@withContext emptyList()
        val account = GiteaAccountsUtil.getSingleOrDefaultAccount(project) ?: return@withContext emptyList()
        val token = service<GiteaAccountManager>().findCredentials(account) ?: return@withContext emptyList()
        
        val api = service<GiteaSettings>().getGiteaApi(account.server.toString(), token)
        val response = api.getIssueApi().issueListIssues(
            repoPath.owner, repoPath.repository, "open", null, null, null, null, null, null, null, null, null, 1, 50
        ).execute()
        
        if (response.isSuccessful) {
            response.body() ?: emptyList()
        } else {
            emptyList()
        }
    }
}
