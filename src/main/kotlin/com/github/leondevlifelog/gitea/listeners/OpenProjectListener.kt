/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.listeners

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.GiteaConfig
import com.github.leondevlifelog.gitea.authentication.GiteaAccountsUtil
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccountManager
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaServerPath
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.RunOnceUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.Messages
import git4idea.remote.hosting.GitHostingUrlUtil.match
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import java.net.URI

class OpenProjectListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        val gitea = PluginManagerCore.getPlugin(PluginId.getId(GiteaConfig.GITEA_PLUGIN_ID))
        RunOnceUtil.runOnceForApp(GiteaConfig.GITEA_PLUGIN_ID + gitea?.version) {
            GiteaNotifications.showStarMe(project)
        }

        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                promptAuthenticationIfNeeded(project)
            }, project.disposed)
        }
    }

    private fun promptAuthenticationIfNeeded(project: Project) {
        val remoteUrls = getProjectRemoteUrls(project)
        if (remoteUrls.isEmpty()) return

        val accountManager = service<GiteaAccountManager>()
        val hasUsableAccount = accountManager.accountsState.value.any { account ->
            val hasToken = runBlocking { !accountManager.findCredentials(account).isNullOrBlank() }
            hasToken && remoteUrls.any { url -> match(account.server.toURI(), url) }
        }
        if (hasUsableAccount) return

        val server = remoteUrls.asSequence()
            .mapNotNull(::extractServerPath)
            .firstOrNull()
        showAuthenticationChoice(project, server)
    }

    private fun showAuthenticationChoice(project: Project, server: GiteaServerPath?) {
        if (server == null) {
            GiteaAccountsUtil.requestNewAccount(project = project)
            return
        }
        val result = Messages.showDialog(
            project,
            GiteaBundle.message("startup.auth.missing.message", server.toString()),
            GiteaBundle.message("startup.auth.missing.title"),
            arrayOf(
                GiteaBundle.message("startup.auth.open.browser"),
                GiteaBundle.message("startup.auth.use.token"),
                Messages.getCancelButton()
            ),
            0,
            Messages.getQuestionIcon()
        )
        when (result) {
            0 -> BrowserUtil.browse(server.toAccessTokenUrl())
            1 -> GiteaAccountsUtil.requestNewAccount(server = server, project = project)
        }
    }

    private fun getProjectRemoteUrls(project: Project): List<String> {
        return GitRepositoryManager.getInstance(project).repositories
            .flatMap { repository ->
                repository.remotes.sortedBy { if (it.name == "origin") 0 else 1 }
                    .flatMap { remote -> remote.urls }
            }
    }

    private fun extractServerPath(remoteUrl: String): GiteaServerPath? {
        val normalized = remoteUrl.trim().removeSuffix(".git").trimEnd('/')
        return when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> {
                val uri = URI(normalized)
                val path = normalizeServerPath(uri.path)
                GiteaServerPath(uri.scheme == "http", uri.host ?: return null, uri.port, path)
            }

            normalized.startsWith("ssh://") -> {
                val uri = URI(normalized)
                val path = normalizeServerPath(uri.path)
                GiteaServerPath(false, uri.host ?: return null, -1, path)
            }

            normalized.contains('@') && normalized.contains(':') -> {
                val hostPart = normalized.substringAfter('@').substringBefore(':')
                val pathPart = normalized.substringAfter(':')
                val path = normalizeServerPath("/$pathPart")
                GiteaServerPath(false, hostPart, -1, path)
            }

            else -> null
        }
    }

    private fun normalizeServerPath(path: String?): String {
        val segments = path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        return if (segments.size <= 2) {
            ""
        } else {
            "/" + segments.dropLast(2).joinToString("/")
        }
    }
}
