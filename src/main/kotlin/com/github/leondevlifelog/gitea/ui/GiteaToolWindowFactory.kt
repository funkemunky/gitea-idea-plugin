/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel

class GiteaToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val prPanel = GiteaPullRequestsPanel(project)
        val prContent = ContentFactory.getInstance().createContent(prPanel, GiteaBundle.message("pr"), false)
        toolWindow.contentManager.addContent(prContent)

        val issuePanel = GiteaIssuesPanel(project)
        val issueContent = ContentFactory.getInstance()
            .createContent(issuePanel, GiteaBundle.message("issue"), false)
        toolWindow.contentManager.addContent(issueContent)
    }

    override fun shouldBeAvailable(project: Project) = true

    class GiteaToolWindow {
        companion object {
            fun issuePlaceholderContent(): DialogPanel {
                return panel {
                    row {
                        label(GiteaBundle.message("feat.for.now")).align(Align.CENTER)
                    }
                    row {
                        label(GiteaBundle.message("feat.path")).align(Align.CENTER)
                    }
                    row {
                        label(GiteaBundle.message("get.from.vcs.line1")).align(Align.CENTER)
                    }
                    row {
                        label(GiteaBundle.message("get.from.vcs.line2")).align(Align.CENTER)
                    }
                    row {
                        label(GiteaBundle.message("get.from.vcs.line3")).align(Align.CENTER)
                    }
                    row {
                        label(GiteaBundle.message("coming.soon")).align(Align.CENTER)
                    }
                }
            }
        }
    }
}
