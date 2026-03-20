/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.services

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccountManager
import com.github.leondevlifelog.gitea.util.GiteaUrlUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.remote.hosting.GitHostingUrlUtil.match
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking
import org.gitnex.tea4j.v2.models.Comment
import org.gitnex.tea4j.v2.models.CreateIssueCommentOption
import org.gitnex.tea4j.v2.models.CreateIssueOption
import org.gitnex.tea4j.v2.models.EditIssueOption
import org.gitnex.tea4j.v2.models.Issue
import org.gitnex.tea4j.v2.models.IssueLabelsOption
import org.gitnex.tea4j.v2.models.TimelineComment
import retrofit2.Call
import retrofit2.Response

@Service(Service.Level.PROJECT)
class GiteaIssueService(private val project: Project) {

    private val accountManager: GiteaAccountManager
        get() = service()

    fun findRepositories(): List<GiteaPullRequestRepositoryContext> {
        val accounts = accountManager.accountsState.value.mapNotNull { account ->
            val token = runBlocking { accountManager.findCredentials(account) } ?: return@mapNotNull null
            account to token
        }
        if (accounts.isEmpty()) return emptyList()

        val preferredRoot = project.basePath
        val contexts = LinkedHashMap<String, GiteaPullRequestRepositoryContext>()
        val repositories = GitRepositoryManager.getInstance(project).repositories.sortedBy { repository ->
            val path = repository.root.path
            when {
                preferredRoot != null && preferredRoot.equals(path, ignoreCase = true) -> 0
                else -> 1
            }
        }

        for (gitRepository in repositories) {
            for ((remote, url) in getOrderedRemoteUrls(gitRepository)) {
                val repositoryPath = GiteaUrlUtil.getUserAndRepositoryFromRemoteUrl(url) ?: continue
                val match = accounts.firstOrNull { (account, _) -> match(account.server.toURI(), url) } ?: continue
                val (account, token) = match
                contexts.putIfAbsent(
                    gitRepository.root.path,
                    GiteaPullRequestRepositoryContext(
                        gitRepository = gitRepository,
                        remote = remote,
                        remoteUrl = url,
                        coordinates = com.github.leondevlifelog.gitea.api.GiteaRepositoryCoordinates(
                            account.server, repositoryPath
                        ),
                        account = account,
                        token = token
                    )
                )
            }
        }

        return contexts.values.toList()
    }

    fun listIssues(
        context: GiteaPullRequestRepositoryContext,
        state: String = "open"
    ): List<Issue> {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        return execute(
            api.getIssueApi().issueListIssues(
                owner, repo, state, null, null, "issues", null, null, null, null, null, null, 1, 100
            )
        )
    }

    fun createIssue(
        context: GiteaPullRequestRepositoryContext,
        option: CreateIssueOption
    ): Issue {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        return execute(api.getIssueApi().issueCreateIssue(owner, repo, option))
    }

    fun updateIssueState(
        context: GiteaPullRequestRepositoryContext,
        issueNumber: Long,
        state: String
    ): Issue {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val option = EditIssueOption().apply {
            this.state = state
        }
        return execute(api.getIssueApi().issueEditIssue(owner, repo, issueNumber, option))
    }

    fun updateIssueMetadata(
        context: GiteaPullRequestRepositoryContext,
        issueNumber: Long,
        title: String?,
        body: String?,
        assignees: List<String>,
        milestoneId: Long?,
        dueDate: java.util.Date?,
        labelIds: List<Long>? = null
    ): Issue {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val option = EditIssueOption().apply {
            title?.let { this.title = it }
            body?.let { this.body = it }
            this.assignees = assignees
            milestoneId?.let { this.milestone = it }
            dueDate?.let { this.dueDate = it }
            this.setUnsetDueDate(dueDate == null)
        }
        val updated = execute(api.getIssueApi().issueEditIssue(owner, repo, issueNumber, option))

        // Replace labels via separate API if label IDs were provided.
        // IssueLabelsOption accepts List<Object> since labels can be IDs (Long) or names (String).
        if (labelIds != null) {
            val labelsOption = IssueLabelsOption().apply {
                this.labels = labelIds.map { it as Any }
            }
            try {
                execute(api.getIssueApi().issueReplaceLabels(owner, repo, issueNumber, labelsOption))
            } catch (_: Exception) {
                // If label replacement fails, still return the updated issue
            }
        }
        return updated
    }

    fun addComment(
        context: GiteaPullRequestRepositoryContext,
        issueNumber: Long,
        message: String
    ): Comment {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val option = CreateIssueCommentOption().apply {
            this.body = message
        }
        return execute(api.getIssueApi().issueCreateComment(owner, repo, issueNumber, option))
    }

    fun getIssueDetails(
        context: GiteaPullRequestRepositoryContext,
        issueNumber: Long
    ): GiteaIssueFullDetails {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository

        val issue = execute(api.getIssueApi().issueGetIssue(owner, repo, issueNumber))
        val timeline = execute(
            api.getIssueApi().issueGetCommentsAndTimeline(owner, repo, issueNumber, null, 1, 200, null)
        )
        val comments = execute(api.getIssueApi().issueGetComments(owner, repo, issueNumber, null, null))

        return GiteaIssueFullDetails(issue = issue, timeline = timeline, comments = comments)
    }

    private fun <T> execute(call: Call<T>): T {
        val response = call.execute()
        if (response.isSuccessful) {
            return response.body() ?: error(GiteaBundle.message("issue.error.empty.response"))
        }
        throw errorFrom(response)
    }

    private fun errorFrom(response: Response<*>): IllegalStateException {
        val body = try {
            response.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        val message = listOfNotNull(
            GiteaBundle.message("pull.request.error.request.failed", response.code()),
            body?.takeIf { it.isNotBlank() },
            response.message().takeIf { it.isNotBlank() }
        ).joinToString("\n")
        return IllegalStateException(message)
    }

    private fun getOrderedRemoteUrls(gitRepository: GitRepository): List<Pair<GitRemote, String>> {
        return gitRepository.remotes.sortedBy { if (it.name == "origin") 0 else 1 }.flatMap { remote ->
            remote.urls.map { remote to it }
        }
    }
}

data class GiteaIssueFullDetails(
    val issue: Issue,
    val timeline: List<TimelineComment>,
    val comments: List<Comment>
)
