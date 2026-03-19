/*
 * Copyright (c) 2023. Leon<leondevlifelog@gmail.com>. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.github.leondevlifelog.gitea.util

import com.github.leondevlifelog.gitea.GiteaBundle
import com.github.leondevlifelog.gitea.GiteaConfig
import com.github.leondevlifelog.gitea.exception.GiteaOperationCanceledException
import com.intellij.notification.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts.NotificationContent
import com.intellij.openapi.util.NlsContexts.NotificationTitle
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.net.UnknownHostException


object GiteaNotifications {
    private val LOG: Logger = thisLogger()
    private const val NOTIFICATION_GROUP_ID = "Gitea Notifications"

    private fun notify(
        project: Project?,
        displayId: String?,
        title: String,
        message: String,
        type: NotificationType,
        listener: NotificationListener? = null
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, message, type)
        if (displayId != null) {
            notification.setDisplayId(displayId)
        }

        if (listener != null) {
            notification.setListener(listener)
        }
        notification.notify(project)
    }

    private fun isOperationCanceled(@NotNull e: Throwable?): Boolean {
        return e is GiteaOperationCanceledException || e is ProcessCanceledException
    }

    fun showInfo(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ) {
        LOG.info("$title; $message")
        notify(project, displayId, title!!, message!!, NotificationType.INFORMATION)
    }

    fun showWarning(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ) {
        LOG.info("$title; $message")
        notify(project, displayId, title!!, message!!, NotificationType.WARNING)
    }

    fun showError(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ) {
        LOG.info("$title; $message")
        notify(project, displayId, title!!, message!!, NotificationType.ERROR)
    }

    fun showError(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?,
        @NotNull logDetails: String
    ) {
        LOG.warn("$title; $message; $logDetails")
        notify(project, displayId, title!!, message!!, NotificationType.ERROR)
    }

    fun showError(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String,
        @NotNull e: Throwable
    ) {
        LOG.warn("$title; ", e)
        if (isOperationCanceled(e)) return
        notify(project, displayId, title, getErrorTextFromException(e), NotificationType.ERROR)
    }

    @NlsSafe
    @JvmStatic
    fun getErrorTextFromException(e: Throwable): String {
        return if (e is UnknownHostException) {
            "Unknown host: " + e.message
        } else StringUtil.notNullize(e.message, "Unknown error")
    }

    fun showInfoURL(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?,
        @NotNull url: String
    ) {
        LOG.info("$title; $message; $url")
        notify(
            project,
            displayId,
            title!!,
            HtmlChunk.link(url, message!!).toString(),
            NotificationType.INFORMATION,
            NotificationListener.URL_OPENING_LISTENER
        )
    }

    fun showWarningURL(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull prefix: String,
        @NotNull highlight: String,
        @NotNull postfix: String,
        @NotNull url: String
    ) {
        LOG.info("$title; $prefix$highlight$postfix; $url")
        notify(
            project,
            displayId,
            title!!,
            "$prefix<a href='$url'>$highlight</a>$postfix",
            NotificationType.WARNING,
            NotificationListener.URL_OPENING_LISTENER
        )
    }

    fun showErrorURL(
        @NotNull project: Project?,
        @NonNls @Nullable displayId: String?,
        @NotNull title: @NotificationTitle String?,
        @NotNull prefix: String,
        @NotNull highlight: String,
        @NotNull postfix: String,
        @NotNull url: String
    ) {
        LOG.info("$title; $prefix$highlight$postfix; $url")
        notify(
            project,
            displayId,
            title!!,
            "$prefix<a href='$url'>$highlight</a>$postfix",
            NotificationType.ERROR,
            NotificationListener.URL_OPENING_LISTENER
        )
    }

    fun showWarningDialog(
        @Nullable project: Project?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ) {
        LOG.info("$title; $message")
        Messages.showWarningDialog(project, message, title!!)
    }

    fun showErrorDialog(
        @Nullable project: Project?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ) {
        LOG.info("$title; $message")
        Messages.showErrorDialog(project, message, title!!)
    }

    @Messages.YesNoResult
    fun showYesNoDialog(
        @Nullable project: Project?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?
    ): Boolean {
        return MessageDialogBuilder.yesNo(title!!, message!!).ask(project)
    }

    @Messages.YesNoResult
    fun showYesNoDialog(
        @Nullable project: Project?,
        @NotNull title: @NotificationTitle String?,
        @NotNull message: @NotificationContent String?,
        @NotNull doNotAskOption: DoNotAskOption?
    ): Boolean {
        return MessageDialogBuilder.yesNo(title!!, message!!).icon(Messages.getQuestionIcon()).doNotAsk(doNotAskOption)
            .ask(project)
    }

    fun showStarMe(project: Project) {
        val notification = Notification(
            GiteaNotificationIdsHolder.GITHUB_START_ME,
            GiteaBundle.message("github.star.me"),
            GiteaBundle.message("github.made.with.love"),
            NotificationType.INFORMATION
        )
        notification.addAction(
            BrowseNotificationAction(
                GiteaBundle.message("github.goto.star.it"), GiteaConfig.REPO_URL
            )
        )
        Notifications.Bus.notify(notification, project)
    }

}
