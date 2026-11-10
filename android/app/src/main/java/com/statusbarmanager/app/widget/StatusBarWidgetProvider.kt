package com.statusbarmanager.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.statusbarmanager.app.MainActivity
import com.statusbarmanager.app.R
import com.statusbarmanager.app.automation.EngageMode
import com.statusbarmanager.app.automation.OperationCoordinator
import com.statusbarmanager.app.automation.SettingsAutomationManager
import com.statusbarmanager.app.data.DataStoreRepository
import com.statusbarmanager.app.data.SettingsBackupManager
import com.statusbarmanager.app.data.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * OBJECTIVE 4: Native Android Home-Screen Widget Provider.
 * Allows users to Engage and Disengage StatusBar Manager directly from their home screen.
 * - Displays current state: NORMAL / ENGAGED / RUNNING / PARTIAL
 * - Shows selected Engage Button Behaviour
 * - Action button triggers central OperationCoordinator
 * - If Accessibility permission is missing, opens permission onboarding in MainActivity
 */
class StatusBarWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.statusbarmanager.app.widget.ACTION_WIDGET_TOGGLE"
        const val ACTION_WIDGET_FORCE_STOP = "com.statusbarmanager.app.widget.ACTION_WIDGET_FORCE_STOP"
        const val ACTION_WIDGET_STATUS_BAR_ONLY = "com.statusbarmanager.app.widget.ACTION_WIDGET_STATUS_BAR_ONLY"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val providers = listOf(
                StatusBarWidgetProvider::class.java,
                HushButtonIconWidgetProvider::class.java,
                StopAppsWidgetProvider::class.java,
                StatusBarOnlyWidgetProvider::class.java
            )
            for (providerClass in providers) {
                try {
                    val componentName = ComponentName(context, providerClass)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    if (appWidgetIds.isNotEmpty()) {
                        val intent = Intent(context, providerClass).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                        }
                        context.sendBroadcast(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("StatusBarWidget", "Failed to broadcast update to ${providerClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val repo = DataStoreRepository(context)
        val automationManager = SettingsAutomationManager(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isHideActive = repo.isHideActiveFlow.first()
                val backupModel = repo.backupModelFlow.first()
                val engageMode = repo.engageModeFlow.first()
                val isAccGranted = automationManager.isAccessibilityPermissionGranted()

                for (widgetId in appWidgetIds) {
                    try {
                        updateAppWidget(context, appWidgetManager, widgetId, isHideActive, backupModel?.isPartial ?: false, engageMode, isAccGranted)
                    } catch (e: Exception) {
                        android.util.Log.e("StatusBarWidget", "Failed to update widget $widgetId: ${e.message}")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_TOGGLE &&
            intent.action != ACTION_WIDGET_FORCE_STOP &&
            intent.action != ACTION_WIDGET_STATUS_BAR_ONLY
        ) return

        val pendingResult = goAsync()
        val repo = DataStoreRepository(context)
        val backupManager = SettingsBackupManager(repo)
        val automationManager = SettingsAutomationManager(context)
        val coordinator = OperationCoordinator(context, repo, backupManager)

        fun openMain(prompt: String) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(prompt, true)
            }
            context.startActivity(openIntent)
        }

        if (!automationManager.isAccessibilityPermissionGranted()) {
            openMain("prompt_accessibility")
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_WIDGET_STATUS_BAR_ONLY -> {
                        coordinator.toggleStatusBarOnly(fromWidget = true)
                    }
                    ACTION_WIDGET_FORCE_STOP -> {
                        coordinator.forceStopAppsOnly(fromWidget = true)
                    }
                    else -> {
                        val isHideActive = repo.isHideActiveFlow.first()
                        if (isHideActive) {
                            coordinator.disengage(fromWidget = true)
                        } else {
                            coordinator.engage(fromWidget = true)
                        }
                    }
                }
                updateAllWidgets(context)
            } catch (e: Exception) {
                android.util.Log.e("StatusBarWidget", "Error executing widget action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        isHideActive: Boolean,
        isPartial: Boolean,
        engageMode: EngageMode,
        isAccessibilityGranted: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_status_bar_manager)

        // Set Icon container background, bubble, and badge according to state
        when {
            !isAccessibilityGranted -> {
                views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.widget_icon_bg_normal)
                views.setViewVisibility(R.id.widget_notification_bubble, View.VISIBLE)
                views.setInt(R.id.widget_notification_bubble, "setBackgroundResource", R.drawable.widget_bubble_error)
                views.setTextViewText(R.id.widget_notification_bubble, "!")
                views.setTextViewText(R.id.widget_status_badge, "PERMISSION")
                views.setTextColor(R.id.widget_status_badge, Color.parseColor("#EF4444"))
            }
            isPartial -> {
                views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.widget_icon_bg_active)
                views.setViewVisibility(R.id.widget_notification_bubble, View.VISIBLE)
                views.setInt(R.id.widget_notification_bubble, "setBackgroundResource", R.drawable.widget_bubble_partial)
                views.setTextViewText(R.id.widget_notification_bubble, "!")
                views.setTextViewText(R.id.widget_status_badge, "PARTIAL")
                views.setTextColor(R.id.widget_status_badge, Color.parseColor("#F59E0B"))
            }
            isHideActive -> {
                views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.widget_icon_bg_active)
                views.setViewVisibility(R.id.widget_notification_bubble, View.VISIBLE)
                views.setInt(R.id.widget_notification_bubble, "setBackgroundResource", R.drawable.widget_bubble_active)
                views.setTextViewText(R.id.widget_notification_bubble, "✓")
                views.setTextViewText(R.id.widget_status_badge, "HUSHED")
                views.setTextColor(R.id.widget_status_badge, Color.parseColor("#38BDF8"))
            }
            else -> {
                views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.widget_icon_bg_normal)
                views.setViewVisibility(R.id.widget_notification_bubble, View.GONE)
                views.setTextViewText(R.id.widget_notification_bubble, "")
                views.setTextViewText(R.id.widget_status_badge, "NORMAL")
                views.setTextColor(R.id.widget_status_badge, Color.parseColor("#10B981"))
            }
        }

        // Toggle PendingIntent on click
        val toggleIntent = Intent(context, StatusBarWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        views.setOnClickPendingIntent(R.id.widget_icon_container, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
