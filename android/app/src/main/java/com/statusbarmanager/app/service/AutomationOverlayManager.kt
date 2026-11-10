package com.statusbarmanager.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.statusbarmanager.app.MainActivity
import com.statusbarmanager.app.automation.AutomationProgress
import com.statusbarmanager.app.automation.AutomationStep

/**
 * Notification-only progress reporter.
 *
 * This class deliberately does not create WindowManager windows, application overlays,
 * accessibility overlays, dialogs, or views. Keeping progress outside the window hierarchy
 * lets the AccessibilityService inspect Android Settings and App Info without a SysHush
 * window being attached over the system UI.
 */
class AutomationOverlayManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "syshush_progress_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SysHush progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Automation progress and completion status"
                    setShowBadge(false)
                }
            )
        }
    }

    fun updateProgress(progress: AutomationProgress) {
        // Deliberately do not post progress notifications to the system notification shade.
        // User explicitly requested removing the process progress notifications.
        val finished = progress.currentStep == AutomationStep.COMPLETED ||
            progress.currentStep == AutomationStep.PARTIAL ||
            progress.currentStep == AutomationStep.FAILED
        if (finished) {
            dismiss()
        }
    }

    fun dismiss() {
        notificationManager?.cancel(NOTIFICATION_ID)
    }
}
