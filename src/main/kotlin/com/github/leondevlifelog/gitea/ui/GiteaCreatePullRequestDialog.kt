/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import git4idea.branch.GitBrancher
import org.gitnex.tea4j.v2.models.CreatePullRequestOption
import org.gitnex.tea4j.v2.models.Milestone
import java.awt.BorderLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class GiteaCreatePullRequestDialog(
    private val context: GiteaPullRequestRepositoryContext,
    private val details: GiteaPullRequestRepositoryDetails
) : DialogWrapper(context.gitRepository.project, true) {

    private val titleField = JBTextField()
    private val sourceBranchCombo = ComboBox(CollectionComboBoxModel(createSourceBranches(), defaultSourceBranch()))
    private val targetBranchCombo = ComboBox(CollectionComboBoxModel(details.branchNames, defaultTargetBranch()))
    private val bodyField = JBTextArea(8, 60)
    private val allowMaintainerEdit = JBCheckBox(GiteaBundle.message("pull.request.form.allow.maintainer.edit"), true)
    private val assigneeCombo = ComboBox(CollectionComboBoxModel(createAssigneeOptions(), ""))
    private val assigneesField = JBTextField()
    private val reviewersField = JBTextField()
    private val teamReviewersField = JBTextField()
    private val labelsField = JBTextField()
    private val milestoneCombo = ComboBox(CollectionComboBoxModel(createMilestoneOptions(), MilestoneOption.None))
    private val dueDateField = JBTextField()
    private val changedFilesModel = CollectionComboBoxModel<String>()
    private val changedFilesCombo = ComboBox(changedFilesModel)
    private val openNativeCompareButton = com.intellij.ui.components.JBLabel(
        "<html><a href=''>" + GiteaBundle.message("pull.request.diff.open.native") + "</a></html>"
    )
    private val diffRequestPanel = DiffManager.getInstance().createRequestPanel(context.gitRepository.project, disposable, null)
    private val diffVersion = AtomicInteger(0)
    @Volatile
    private var currentRangeSpec: RangeSpec? = null

    init {
        title = GiteaBundle.message("pull.request.create.title")
        sourceBranchCombo.addActionListener { refreshDiffAsync() }
        targetBranchCombo.addActionListener { refreshDiffAsync() }
        changedFilesCombo.addActionListener { refreshSelectedFileDiffAsync() }
        openNativeCompareButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        openNativeCompareButton.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                openNativeCompare()
            }
        })
        init()
        refreshDiffAsync()
    }

    fun createRequest(): CreatePullRequestOption {
        val request = CreatePullRequestOption()
        request.title = titleField.text.trim()
        request.head = selectedSourceBranch()
        request.base = selectedTargetBranch()
        request.body = bodyField.text.takeIf { it.isNotBlank() }
        request.setAllowMaintainerEdit(allowMaintainerEdit.isSelected)
        request.assignee = assigneeCombo.item?.takeIf { it.isNotBlank() }
        request.assignees = parseCsv(assigneesField.text).takeIf { it.isNotEmpty() }
        request.reviewers = parseCsv(reviewersField.text).takeIf { it.isNotEmpty() }
        request.teamReviewers = parseCsv(teamReviewersField.text).takeIf { it.isNotEmpty() }
        request.labels = parseLabelIds(labelsField.text).takeIf { it.isNotEmpty() }
        request.milestone = (milestoneCombo.item as? MilestoneOption.Selected)?.id
        request.dueDate = parseDueDate(dueDateField.text)
        return request
    }

    override fun createCenterPanel(): JComponent {
        bodyField.lineWrap = true
        bodyField.wrapStyleWord = true

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(GiteaBundle.message("pull.request.form.repository"), JBLabel(context.displayName))
            .addLabeledComponent(GiteaBundle.message("pull.request.form.source.branch"), sourceBranchCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.target.branch"), targetBranchCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.title.label"), titleField)
            .addLabeledComponent(
                GiteaBundle.message("pull.request.form.description"),
                JBScrollPane(bodyField),
                1,
                false
            )
            .addComponent(allowMaintainerEdit)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.assignee"), assigneeCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.assignees"), assigneesField)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.reviewers"), reviewersField)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.team.reviewers"), teamReviewersField)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.labels"), labelsField)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.milestone"), milestoneCombo)
            .addLabeledComponent(GiteaBundle.message("pull.request.form.due.date"), dueDateField)
            .addComponent(helperLabel(GiteaBundle.message("pull.request.form.assignees.hint", availableCollaborators())))
            .addComponent(helperLabel(GiteaBundle.message("pull.request.form.reviewers.hint", availableCollaborators())))
            .addComponent(helperLabel(GiteaBundle.message("pull.request.form.team.reviewers.hint", availableTeams())))
            .addComponent(helperLabel(GiteaBundle.message("pull.request.form.labels.hint", availableLabels())))
            .addComponent(helperLabel(GiteaBundle.message("pull.request.form.milestone.hint", availableMilestones())))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        changedFilesCombo.isEnabled = false

        val diffPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0),
                JBUI.Borders.empty(0, 8, 0, 0)
            )
            val header = FormBuilder.createFormBuilder()
                .addComponent(JBLabel(GiteaBundle.message("pull.request.diff.title")))
                .addLabeledComponent(GiteaBundle.message("pull.request.diff.file"), changedFilesCombo)
                .addComponent(openNativeCompareButton)
                .panel
            add(header, BorderLayout.NORTH)
            add(diffRequestPanel.component, BorderLayout.CENTER)
        }

        val split = JBSplitter(false, 0.52f).apply {
            firstComponent = JPanel(BorderLayout()).apply { add(form, BorderLayout.CENTER) }
            secondComponent = diffPanel
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(split, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo(GiteaBundle.message("pull.request.validation.title.required"), titleField)
        }
        if (selectedSourceBranch().isBlank()) {
            return ValidationInfo(GiteaBundle.message("pull.request.validation.source.required"), sourceBranchCombo)
        }
        if (selectedTargetBranch().isBlank()) {
            return ValidationInfo(GiteaBundle.message("pull.request.validation.target.required"), targetBranchCombo)
        }
        if (selectedSourceBranch() == selectedTargetBranch()) {
            return ValidationInfo(GiteaBundle.message("pull.request.validation.branches.different"), targetBranchCombo)
        }
        return try {
            parseLabelIds(labelsField.text)
            parseDueDate(dueDateField.text)
            null
        } catch (e: IllegalArgumentException) {
            ValidationInfo(e.message.orEmpty(), labelsField)
        } catch (e: DateTimeParseException) {
            ValidationInfo(GiteaBundle.message("pull.request.validation.due.date"), dueDateField)
        }
    }

    private fun selectedSourceBranch(): String = sourceBranchCombo.item.orEmpty()

    private fun selectedTargetBranch(): String = targetBranchCombo.item.orEmpty()

    private fun createSourceBranches(): List<String> {
        val branches = linkedSetOf<String>()
        context.currentBranch?.let { branches += it }
        branches += details.branchNames
        return branches.toList()
    }

    private fun defaultSourceBranch(): String? = context.currentBranch ?: details.branchNames.firstOrNull()

    private fun defaultTargetBranch(): String? =
        details.repository.defaultBranch ?: details.repository.defaultTargetBranch ?: details.branchNames.firstOrNull()

    private fun createAssigneeOptions(): List<String> = listOf("") + details.collaborators.mapNotNull { it.loginName }

    private fun createMilestoneOptions(): List<MilestoneOption> =
        listOf(MilestoneOption.None) + details.milestones.mapNotNull { milestone ->
            val id = milestone.id ?: return@mapNotNull null
            MilestoneOption.Selected(id, milestone.title ?: id.toString())
        }

    private fun parseCsv(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun parseLabelIds(value: String): List<Long> {
        if (value.isBlank()) return emptyList()
        return parseCsv(value).map {
            it.toLongOrNull() ?: throw IllegalArgumentException(GiteaBundle.message("pull.request.validation.labels"))
        }
    }

    private fun parseDueDate(value: String): Date? {
        if (value.isBlank()) return null
        val localDate = LocalDate.parse(value.trim())
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    private fun availableCollaborators(): String =
        details.collaborators.mapNotNull { it.loginName }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun availableTeams(): String =
        details.teams.mapNotNull { it.name }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun availableLabels(): String =
        details.labels.mapNotNull { label ->
            val id = label.id ?: return@mapNotNull null
            val name = label.name ?: id.toString()
            "$id:$name"
        }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun availableMilestones(): String =
        details.milestones.mapNotNull { milestone ->
            val id = milestone.id ?: return@mapNotNull null
            val title = milestone.title ?: id.toString()
            "$id:$title"
        }.ifEmpty { listOf("-") }.joinToString(", ")

    private fun helperLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyLeft(4)
        }
    }

    private fun refreshDiffAsync() {
        val source = selectedSourceBranch()
        val target = selectedTargetBranch()
        if (source.isBlank() || target.isBlank()) {
            currentRangeSpec = null
            changedFilesCombo.isEnabled = false
            changedFilesModel.removeAll()
            showDiffMessage(GiteaBundle.message("pull.request.diff.missing.branches"))
            return
        }
        if (source == target) {
            currentRangeSpec = null
            changedFilesCombo.isEnabled = false
            changedFilesModel.removeAll()
            showDiffMessage(GiteaBundle.message("pull.request.diff.same.branches"))
            return
        }

        val version = diffVersion.incrementAndGet()
        changedFilesCombo.isEnabled = false
        showDiffMessage(GiteaBundle.message("pull.request.diff.loading"))
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit {
            val result = loadRangeDiff(source, target)
            SwingUtilities.invokeLater {
                if (version == diffVersion.get()) {
                    if (result == null) {
                        currentRangeSpec = null
                        changedFilesModel.removeAll()
                        changedFilesCombo.isEnabled = false
                        showDiffMessage(GiteaBundle.message("pull.request.diff.failed"))
                        return@invokeLater
                    }
                    currentRangeSpec = result.rangeSpec
                    changedFilesModel.removeAll()
                    result.files.forEach(changedFilesModel::add)
                    changedFilesCombo.isEnabled = result.files.isNotEmpty()
                    if (result.files.isEmpty()) {
                        showDiffMessage(GiteaBundle.message("pull.request.diff.no.files", result.rangeSpec.diffRange))
                    } else {
                        changedFilesCombo.selectedItem = result.files.first()
                        refreshSelectedFileDiffAsync()
                    }
                }
            }
        }
    }

    private fun refreshSelectedFileDiffAsync() {
        val selectedFile = changedFilesCombo.selectedItem as? String
        val rangeSpec = currentRangeSpec
        if (selectedFile.isNullOrBlank() || rangeSpec == null) {
            showDiffMessage(GiteaBundle.message("pull.request.diff.select.file"))
            return
        }

        val version = diffVersion.incrementAndGet()
        showDiffMessage(GiteaBundle.message("pull.request.diff.loading.file", selectedFile))
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit {
            val leftText = loadFileAtRef(rangeSpec.targetRefCandidates, selectedFile).orEmpty()
            val rightText = loadFileAtRef(rangeSpec.sourceRefCandidates, selectedFile).orEmpty()
            SwingUtilities.invokeLater {
                if (version == diffVersion.get()) {
                    val request = SimpleDiffRequest(
                        GiteaBundle.message("pull.request.diff.request.title", rangeSpec.targetDisplay, rangeSpec.sourceDisplay),
                        DiffContentFactory.getInstance().create(leftText),
                        DiffContentFactory.getInstance().create(rightText),
                        GiteaBundle.message("pull.request.diff.left.title", rangeSpec.targetDisplay, selectedFile),
                        GiteaBundle.message("pull.request.diff.right.title", rangeSpec.sourceDisplay, selectedFile)
                    )
                    diffRequestPanel.setRequest(request)
                }
            }
        }
    }

    private fun loadRangeDiff(source: String, target: String): RangeDiffResult? {
        val repoDir = context.gitRepository.root.path
        val attempts = listOf(
            RangeSpec(
                diffRange = "origin/$target...$source",
                targetDisplay = "origin/$target",
                sourceDisplay = source,
                targetRefCandidates = listOf("origin/$target", target),
                sourceRefCandidates = listOf(source, "origin/$source")
            ),
            RangeSpec(
                diffRange = "$target...$source",
                targetDisplay = target,
                sourceDisplay = source,
                targetRefCandidates = listOf(target, "origin/$target"),
                sourceRefCandidates = listOf(source, "origin/$source")
            )
        )
        for (attempt in attempts) {
            val command = runGit(repoDir, listOf("diff", "--name-only", attempt.diffRange))
            if (command.exitCode == 0) {
                val files = command.output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                return RangeDiffResult(attempt, files)
            }
        }
        return null
    }

    private fun loadFileAtRef(refCandidates: List<String>, filePath: String): String? {
        val repoDir = context.gitRepository.root.path
        for (ref in refCandidates) {
            val result = runGit(repoDir, listOf("show", "$ref:$filePath"))
            if (result.exitCode == 0) {
                return result.output
            }
        }
        return null
    }

    private fun showDiffMessage(message: String) {
        diffRequestPanel.setRequest(MessageDiffRequest(message))
    }

    private fun openNativeCompare() {
        val source = selectedSourceBranch()
        val target = selectedTargetBranch()
        if (source.isBlank() || target.isBlank() || source == target) return
        FileDocumentManager.getInstance().saveAllDocuments()
        GitBrancher.getInstance(context.gitRepository.project)
            .showDiff(target, source, listOf(context.gitRepository))
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

    private data class GitCommandOutput(val exitCode: Int, val output: String)
    private data class RangeDiffResult(val rangeSpec: RangeSpec, val files: List<String>)
    private data class RangeSpec(
        val diffRange: String,
        val targetDisplay: String,
        val sourceDisplay: String,
        val targetRefCandidates: List<String>,
        val sourceRefCandidates: List<String>
    )

    private sealed interface MilestoneOption {
        data object None : MilestoneOption {
            override fun toString(): String = GiteaBundle.message("pull.request.form.none")
        }

        data class Selected(val id: Long, private val label: String) : MilestoneOption {
            override fun toString(): String = label
        }
    }
}
