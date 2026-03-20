/*
 * Copyright (c) 2023-2026. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.actions

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaPersistentAccounts
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaServerPath
import com.github.leondevlifelog.gitea.util.GiteaUrlUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import git4idea.GitUtil
import git4idea.repo.GitRepository
import java.awt.datatransfer.StringSelection

class GiteaCopyLinkAction : DumbAwareAction(GiteaBundle.message("action.Gitea.CopyLink.text")) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(file)
        if (repository == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val giteaRemote = findGiteaRemote(repository)
        e.presentation.isEnabledAndVisible = giteaRemote != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(file) ?: return
        val giteaRemote = findGiteaRemote(repository) ?: return

        val serverUrl = giteaRemote.first.toString().trim('/')
        val repoPath = GiteaUrlUtil.getUserAndRepositoryFromRemoteUrl(giteaRemote.second) ?: return
        
        val url = "$serverUrl/$repoPath"
        CopyPasteManager.getInstance().setContents(StringSelection(url))
    }

    private fun findGiteaRemote(repository: GitRepository): Pair<GiteaServerPath, String>? {
        val accounts = service<GiteaPersistentAccounts>().accounts
        if (accounts.isEmpty()) return null

        for (remote in repository.remotes) {
            for (url in remote.urls) {
                for (account in accounts) {
                    if (url.contains(account.server.getHost())) {
                        return account.server to url
                    }
                }
            }
        }
        return null
    }
}
