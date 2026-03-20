/*
 * Copyright (c) 2023-2026. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.actions

import com.github.leondevlifelog.gitea.authentication.accounts.GiteaAccount
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaPersistentAccounts
import com.github.leondevlifelog.gitea.authentication.accounts.GiteaServerPath
import com.intellij.collaboration.actions.BrowseInBrowserActionGroup
import com.intellij.collaboration.auth.AccountsRepository
import com.intellij.openapi.components.service
import git4idea.remote.hosting.GitHostingUrlUtil

class GiteaOpenInBrowserActionGroup : BrowseInBrowserActionGroup<GiteaAccount, GiteaServerPath>() {
    override fun accountsRepository(): AccountsRepository<GiteaAccount> = service<GiteaPersistentAccounts>()

    override fun checkIsServerSupported(serverPath: GiteaServerPath, url: String): Boolean =
        GitHostingUrlUtil.match(serverPath.toURI(), url)
}
