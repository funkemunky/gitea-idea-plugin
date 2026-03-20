/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryContext
import com.github.leondevlifelog.gitea.services.GiteaPullRequestRepositoryDetails
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.gitnex.tea4j.v2.models.CreateIssueOption
import org.gitnex.tea4j.v2.models.Milestone
import java.awt.BorderLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Date
import javax.swing.JComponent
import javax.swing.JPanel

class GiteaCreateIssueDialog(
    private val project: Project,
    private val context: GiteaPullRequestRepositoryContext,
    private val details: GiteaPullRequestRepositoryDetails
) : DialogWrapper(project, true) {

    private val titleField = JBTextField()
    private val bodyField = JBTextArea(8, 60)
    private val assigneesField = JBTextField()
    private val labelsField = JBTextField()
    private val milestoneCombo = ComboBox(CollectionComboBoxModel(createMilestoneOptions(), MilestoneOption.None))
    private val dueDateField = JBTextField()

    init {
        title = GiteaBundle.message("issue.create.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollBody = JBScrollPane(bodyField.apply {
            lineWrap = true
            wrapStyleWord = true
        })
        scrollBody.preferredSize = JBUI.size(600, 150)

        val collaboratorHint = details.collaborators.joinToString(", ") { it.loginName.orEmpty() }.let {
            if (it.isNotBlank()) GiteaBundle.message("issue.form.assignees.hint", it) else ""
        }
        val labelHint = details.labels.joinToString(", ") { "${it.id}:${it.name}" }.let {
            if (it.isNotBlank()) GiteaBundle.message("issue.form.labels.hint", it) else ""
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(GiteaBundle.message("issue.form.title.label"), titleField)
            .addLabeledComponent(GiteaBundle.message("issue.form.description"), scrollBody)
            .addLabeledComponent(JBLabel(GiteaBundle.message("issue.form.assignees")).apply {
                toolTipText = collaboratorHint
            }, assigneesField.apply { toolTipText = collaboratorHint })
            .addLabeledComponent(JBLabel(GiteaBundle.message("issue.form.labels")).apply {
                toolTipText = labelHint
            }, labelsField.apply { toolTipText = labelHint })
            .addLabeledComponent(GiteaBundle.message("issue.form.milestone"), milestoneCombo)
            .addLabeledComponent(GiteaBundle.message("issue.form.due.date"), dueDateField)
            .panel
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo(GiteaBundle.message("issue.validation.title.required"), titleField)
        }
        val labelsText = labelsField.text.trim()
        if (labelsText.isNotBlank()) {
            val valid = labelsText.split(",").all { it.trim().toLongOrNull() != null }
            if (!valid) {
                return ValidationInfo(GiteaBundle.message("issue.validation.labels"), labelsField)
            }
        }
        val dueDateText = dueDateField.text.trim()
        if (dueDateText.isNotBlank()) {
            try {
                LocalDate.parse(dueDateText)
            } catch (_: DateTimeParseException) {
                return ValidationInfo(GiteaBundle.message("issue.validation.due.date"), dueDateField)
            }
        }
        return null
    }

    fun createOption(): CreateIssueOption {
        val option = CreateIssueOption()
        option.title = titleField.text.trim()
        option.body = bodyField.text.trim().takeIf { it.isNotBlank() }
        option.assignees = assigneesField.text.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val labelsText = labelsField.text.trim()
        if (labelsText.isNotBlank()) {
            option.labels = labelsText.split(",").mapNotNull { it.trim().toLongOrNull() }
        }
        (milestoneCombo.selectedItem as? MilestoneOption.Named)?.let {
            option.milestone = it.milestone.id
        }
        val dueDateText = dueDateField.text.trim()
        if (dueDateText.isNotBlank()) {
            try {
                val localDate = LocalDate.parse(dueDateText)
                option.dueDate = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
            } catch (_: DateTimeParseException) {
                // skip
            }
        }
        return option
    }

    private fun createMilestoneOptions(): List<MilestoneOption> {
        return listOf(MilestoneOption.None) + details.milestones.map { MilestoneOption.Named(it) }
    }

    private sealed class MilestoneOption {
        data object None : MilestoneOption() {
            override fun toString() = GiteaBundle.message("pull.request.form.none")
        }
        data class Named(val milestone: Milestone) : MilestoneOption() {
            override fun toString() = milestone.title.orEmpty()
        }
    }
}
