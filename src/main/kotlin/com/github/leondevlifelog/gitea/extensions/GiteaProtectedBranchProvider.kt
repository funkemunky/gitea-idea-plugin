/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package com.github.leondevlifelog.gitea.extensions

import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccountManager
import com.github.leondevlifelog.gitea.services.GiteaSettings
import com.github.leondevlifelog.gitea.util.GiteaUrlUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.config.GitProtectedBranchProvider
import git4idea.remote.hosting.GitHostingUrlUtil.match
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking

private val LOG = logger<GiteaProtectedBranchProvider>()

class GiteaProtectedBranchProvider : GitProtectedBranchProvider {
    override fun doGetProtectedBranchPatterns(project: Project): List<String> {
        return try {
            loadProtectedBranchPatterns(project)
        } catch (e: Exception) {
            LOG.debug("Failed to load protected branch patterns from Gitea", e)
            emptyList()
        }
    }

    private fun loadProtectedBranchPatterns(project: Project): List<String> {
        val accountManager = service<GiteaAccountManager>()
        val accounts = accountManager.accountsState.value.mapNotNull { account ->
            val token = runBlocking { accountManager.findCredentials(account) } ?: return@mapNotNull null
            account to token
        }
        if (accounts.isEmpty()) return emptyList()

        val patterns = mutableListOf<String>()
        for (gitRepository in GitRepositoryManager.getInstance(project).repositories) {
            for (remote in gitRepository.remotes) {
                for (url in remote.urls) {
                    val repositoryPath = GiteaUrlUtil.getUserAndRepositoryFromRemoteUrl(url) ?: continue
                    val match = accounts.firstOrNull { (account, _) -> match(account.server.toURI(), url) } ?: continue
                    val (account, token) = match
                    val api = GiteaSettings.getInstance().getGiteaApi(account.server.toString(), token)
                    val owner = repositoryPath.owner
                    val repo = repositoryPath.repository
                    val protections = api.getRepoApi().repoListBranchProtection(owner, repo).execute()
                    if (protections.isSuccessful) {
                        protections.body()?.forEach { protection ->
                            protection.branchName?.takeIf { it.isNotBlank() }?.let { patterns.add(it) }
                        }
                    }
                }
            }
        }
        return patterns.distinct()
    }
}

