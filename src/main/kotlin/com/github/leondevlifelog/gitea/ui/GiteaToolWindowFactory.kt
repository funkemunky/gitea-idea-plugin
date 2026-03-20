/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.ui

import com.github.leondevlifelog.gitea.GiteaBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GiteaToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val prContent = ContentFactory.getInstance()
            .createContent(GiteaPullRequestsPanel(project), GiteaBundle.message("pr"), false)
        toolWindow.contentManager.addContent(prContent)
        val issueContent = ContentFactory.getInstance()
            .createContent(GiteaIssuesPanel(project), GiteaBundle.message("issue"), false)
        toolWindow.contentManager.addContent(issueContent)
    }

    override fun shouldBeAvailable(project: Project) = true
}
