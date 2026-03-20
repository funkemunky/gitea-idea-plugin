/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.services

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.api.GiteaRepositoryCoordinates
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccount
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
import org.gitnex.tea4j.v2.models.Branch
import org.gitnex.tea4j.v2.models.ChangedFile
import org.gitnex.tea4j.v2.models.Comment
import org.gitnex.tea4j.v2.models.Commit
import org.gitnex.tea4j.v2.models.CreatePullRequestOption
import org.gitnex.tea4j.v2.models.CreatePullReviewComment
import org.gitnex.tea4j.v2.models.CreatePullReviewOptions
import org.gitnex.tea4j.v2.models.EditPullRequestOption
import org.gitnex.tea4j.v2.models.Issue
import org.gitnex.tea4j.v2.models.Label
import org.gitnex.tea4j.v2.models.MergePullRequestOption
import org.gitnex.tea4j.v2.models.Milestone
import org.gitnex.tea4j.v2.models.PullRequest
import org.gitnex.tea4j.v2.models.PullReview
import org.gitnex.tea4j.v2.models.PullReviewComment
import org.gitnex.tea4j.v2.models.PullReviewRequestOptions
import org.gitnex.tea4j.v2.models.Repository
import org.gitnex.tea4j.v2.models.Team
import org.gitnex.tea4j.v2.models.TimelineComment
import org.gitnex.tea4j.v2.models.User
import retrofit2.Call
import retrofit2.Response
import org.gitnex.tea4j.v2.models.CreateIssueCommentOption

@Service(Service.Level.PROJECT)
class GiteaPullRequestService(private val project: Project) {

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
                        coordinates = GiteaRepositoryCoordinates(account.server, repositoryPath),
                        account = account,
                        token = token
                    )
                )
            }
        }

        return contexts.values.toList()
    }

    fun loadRepositoryDetails(context: GiteaPullRequestRepositoryContext): GiteaPullRequestRepositoryDetails {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository

        val repository = execute(api.getRepoApi().repoGet(owner, repo))
        val branches = execute(api.getRepoApi().repoListBranches(owner, repo, 1, 100)).sortedBy { it.name.orEmpty() }
        val collaborators = execute(api.getRepoApi().repoListCollaborators(owner, repo, 1, 100))
            .sortedBy { it.loginName.orEmpty() }
        val labels = execute(api.getIssueApi().issueListLabels(owner, repo, 1, 100)).sortedBy { it.name.orEmpty() }
        val milestones = execute(api.getIssueApi().issueGetMilestonesList(owner, repo, "all", null, 1, 100))
            .sortedBy { it.title.orEmpty() }
        val teams = executeOptional(api.getRepoApi().repoListTeams(owner, repo), setOf(403, 404, 405))
            ?.sortedBy { it.name.orEmpty() }
            ?: emptyList()

        return GiteaPullRequestRepositoryDetails(repository, branches, collaborators, labels, milestones, teams)
    }

    fun listPullRequests(
        context: GiteaPullRequestRepositoryContext,
        state: String = "all"
    ): List<PullRequest> {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        return execute(api.getRepoApi().repoListPullRequests(owner, repo, null, state, "recentupdate", null, null, null, 1, 100))
    }

    fun createPullRequest(
        context: GiteaPullRequestRepositoryContext,
        request: CreatePullRequestOption
    ): PullRequest {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        return execute(api.getRepoApi().repoCreatePullRequest(owner, repo, request))
    }

    fun updatePullRequestState(
        context: GiteaPullRequestRepositoryContext,
        pullRequest: PullRequest,
        state: String
    ): PullRequest {
        val index = pullRequest.number ?: error(GiteaBundle.message("pull.request.error.missing.number"))
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val request = EditPullRequestOption().apply {
            this.state = state
        }
        return execute(api.getRepoApi().repoEditPullRequest(owner, repo, index, request))
    }

    fun getPullRequestDetails(
        context: GiteaPullRequestRepositoryContext,
        number: Long
    ): GiteaPullRequestFullDetails {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository

        val pullRequest = execute(api.getRepoApi().repoGetPullRequest(owner, repo, number))
        val issue = execute(api.getIssueApi().issueGetIssue(owner, repo, number))
        val timeline = execute(api.getIssueApi().issueGetCommentsAndTimeline(owner, repo, number, null, 1, 200, null))
        val comments = execute(api.getIssueApi().issueGetComments(owner, repo, number, null, null))
        val commits = execute(api.getRepoApi().repoGetPullRequestCommits(owner, repo, number, 1, 200, true, false))
        val files = execute(api.getRepoApi().repoGetPullRequestFiles(owner, repo, number, null, null, 1, 300))
        val reviews = execute(api.getRepoApi().repoListPullReviews(owner, repo, number, 1, 200))
        val reviewComments = reviews
            .mapNotNull { review -> review.id?.let { it to review } }
            .flatMap { (reviewId, _) ->
                execute(api.getRepoApi().repoGetPullReviewComments(owner, repo, number, reviewId))
            }

        return GiteaPullRequestFullDetails(
            pullRequest = pullRequest,
            issue = issue,
            timeline = timeline,
            comments = comments,
            commits = commits,
            files = files,
            reviews = reviews,
            reviewComments = reviewComments
        )
    }

    fun updatePullRequestMetadata(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        assignees: List<String>,
        labels: List<Long>,
        milestoneId: Long?,
        dueDate: java.util.Date?
    ): PullRequest {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val request = EditPullRequestOption().apply {
            this.assignees = assignees
            this.labels = labels
            this.milestone = milestoneId
            this.dueDate = dueDate
            this.setUnsetDueDate(dueDate == null)
        }
        return execute(api.getRepoApi().repoEditPullRequest(owner, repo, number, request))
    }

    fun requestReviewers(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        reviewers: List<String>,
        teamReviewers: List<String>
    ) {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val body = PullReviewRequestOptions().apply {
            this.reviewers = reviewers
            this.teamReviewers = teamReviewers
        }
        execute(api.getRepoApi().repoCreatePullReviewRequests(body, owner, repo, number))
    }

    fun clearRequestedReviewers(
        context: GiteaPullRequestRepositoryContext,
        number: Long
    ) {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val body = PullReviewRequestOptions().apply {
            this.reviewers = emptyList()
            this.teamReviewers = emptyList()
        }
        executeVoid(api.getRepoApi().repoDeletePullReviewRequests(body, owner, repo, number))
    }

    fun addPullRequestComment(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        message: String
    ): Comment {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val body = CreateIssueCommentOption().apply {
            this.body = message
        }
        return execute(api.getIssueApi().issueCreateComment(owner, repo, number, body))
    }

    fun submitReview(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        event: String,
        bodyText: String
    ): PullReview {
        return submitReview(context, number, event, bodyText, emptyList())
    }

    fun submitReview(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        event: String,
        bodyText: String,
        lineComments: List<GiteaReviewDraftComment>
    ): PullReview {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val body = CreatePullReviewOptions().apply {
            this.body = bodyText
            this.event = event
            if (lineComments.isNotEmpty()) {
                this.comments = lineComments.map { draft ->
                    CreatePullReviewComment().apply {
                        this.body = draft.body
                        this.path = draft.path
                        if (draft.side == GiteaReviewDraftComment.Side.NEW) {
                            this.newPosition = draft.position.toLong()
                            this.oldPosition = 0L
                        } else {
                            this.oldPosition = draft.position.toLong()
                            this.newPosition = 0L
                        }
                    }
                }
            }
        }
        return execute(api.getRepoApi().repoCreatePullReview(body, owner, repo, number))
    }

    fun mergePullRequest(
        context: GiteaPullRequestRepositoryContext,
        number: Long,
        mergeMessage: String?
    ) {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        val body = MergePullRequestOption().apply {
            this.setDo(MergePullRequestOption.DoEnum.MERGE)
            this.mergeMessageField = mergeMessage?.takeIf { it.isNotBlank() }
            this.setDeleteBranchAfterMerge(false)
        }
        executeVoid(api.getRepoApi().repoMergePullRequest(owner, repo, number, body))
    }

    fun resolveReviewComment(
        context: GiteaPullRequestRepositoryContext,
        commentId: Long
    ) {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        executeVoid(api.getRepoApi().repoResolvePullReviewComment(owner, repo, commentId))
    }

    fun unresolveReviewComment(
        context: GiteaPullRequestRepositoryContext,
        commentId: Long
    ) {
        val api = GiteaSettings.getInstance().getGiteaApi(context.account.server.toString(), context.token)
        val owner = context.coordinates.repositoryPath.owner
        val repo = context.coordinates.repositoryPath.repository
        executeVoid(api.getRepoApi().repoUnresolvePullReviewComment(owner, repo, commentId))
    }

    private fun <T> execute(call: Call<T>): T {
        val response = call.execute()
        if (response.isSuccessful) {
            return response.body() ?: error(GiteaBundle.message("pull.request.error.empty.response"))
        }
        throw errorFrom(response)
    }

    private fun <T> executeOptional(call: Call<T>, ignoredStatusCodes: Set<Int>): T? {
        val response = call.execute()
        if (response.isSuccessful) {
            return response.body()
        }
        if (ignoredStatusCodes.contains(response.code())) {
            return null
        }
        throw errorFrom(response)
    }

    private fun executeVoid(call: Call<Void>) {
        val response = call.execute()
        if (!response.isSuccessful) {
            throw errorFrom(response)
        }
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

data class GiteaPullRequestFullDetails(
    val pullRequest: PullRequest,
    val issue: Issue,
    val timeline: List<TimelineComment>,
    val comments: List<Comment>,
    val commits: List<Commit>,
    val files: List<ChangedFile>,
    val reviews: List<PullReview>,
    val reviewComments: List<PullReviewComment>
)

data class GiteaPullRequestRepositoryContext(
    val gitRepository: GitRepository,
    val remote: GitRemote,
    val remoteUrl: String,
    val coordinates: GiteaRepositoryCoordinates,
    val account: GiteaAccount,
    val token: String
) {
    val currentBranch: String?
        get() = gitRepository.currentBranch?.name

    val displayName: String
        get() = "${coordinates.repositoryPath} (${gitRepository.root.name})"

    override fun toString(): String = displayName
}

data class GiteaPullRequestRepositoryDetails(
    val repository: Repository,
    val branches: List<Branch>,
    val collaborators: List<User>,
    val labels: List<Label>,
    val milestones: List<Milestone>,
    val teams: List<Team>
) {
    val branchNames: List<String>
        get() = branches.mapNotNull { it.name }.distinct().sorted()
}

data class GiteaReviewDraftComment(
    val path: String,
    val position: Int,
    val side: Side,
    val body: String
) {
    enum class Side {
        NEW,
        OLD
    }
}
