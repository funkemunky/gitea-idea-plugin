/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package com.github.leondevlifelog.gitea.authentication.accounts

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.util.GiteaNotifications
import com.github.leondevlifelog.gitea.util.GiteaNotificationIdsHolder
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.collaboration.auth.PersistentDefaultAccountHolder
import kotlinx.coroutines.CoroutineScope

@State(name = "GiteaDefaultAccount", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
@Service(Service.Level.PROJECT)
class GiteaProjectDefaultAccountHolder(project: Project, scope: CoroutineScope) :
    PersistentDefaultAccountHolder<GiteaAccount>(project, scope) {

    override fun accountManager() = service<GiteaAccountManager>()

    override fun notifyDefaultAccountMissing() = runInEdt {
        GiteaNotifications.showWarning(
            project,
            GiteaNotificationIdsHolder.MISSING_DEFAULT_ACCOUNT,
            GiteaBundle.message("accounts.default.missing"),
            GiteaBundle.message("accounts.default.missing")
        )
    }
}
