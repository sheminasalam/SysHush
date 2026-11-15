package com.statusbarmanager.app

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.statusbarmanager.app.automation.AppStoppedState
import com.statusbarmanager.app.automation.DeviceCompatibilityManager
import com.statusbarmanager.app.automation.EngageMode
import com.statusbarmanager.app.automation.ForceStopMatcher
import com.statusbarmanager.app.automation.ForceStopResult
import com.statusbarmanager.app.automation.ForceStopWindowEvent
import com.statusbarmanager.app.automation.TargetMode
import com.statusbarmanager.app.automation.isActiveProcessImportance
import com.statusbarmanager.app.automation.shouldPersistStatusBarHidden

class SysHushUnitTest {

    @Test
    fun testEngageModeEnumParsing() {
        assertEquals(EngageMode.STATUS_BAR_ONLY, EngageMode.fromString("STATUS_BAR_ONLY"))
        assertEquals(EngageMode.FORCE_STOP_APPS_ONLY, EngageMode.fromString("FORCE_STOP_APPS_ONLY"))
        assertEquals(EngageMode.BOTH, EngageMode.fromString("BOTH"))
        // Fallback for invalid input
        assertEquals(EngageMode.BOTH, EngageMode.fromString("INVALID_MODE"))
    }

    @Test
    fun testDefaultChecklistForMode() {
        val statusBarChecklist = com.statusbarmanager.app.automation.AutomationProgress.forMode(
            EngageMode.STATUS_BAR_ONLY,
            isEngage = true
        )
        assertTrue(statusBarChecklist.isNotEmpty())
        assertTrue(statusBarChecklist.any { it.id == "time" })

        val forceStopChecklist = com.statusbarmanager.app.automation.AutomationProgress.forMode(
            EngageMode.FORCE_STOP_APPS_ONLY,
            isEngage = true
        )
        assertTrue(forceStopChecklist.isNotEmpty())
        assertTrue(forceStopChecklist.any { it.id == "force_stop" })
    }

    @Test
    fun shortStatusBarLabelsMatchWithoutMatchingUnrelatedWords() {
        val manager = DeviceCompatibilityManager()
        assertTrue(manager.matchesSettingsLabel("Show time in status bar", "Time"))
        assertTrue(manager.matchesSettingsLabel("Time display", "Time"))
        assertFalse(manager.matchesSettingsLabel("Screen timeout", "Time"))
    }

    @Test
    fun appOnlyForceStopDoesNotPersistStatusBarAsHidden() {
        assertFalse(shouldPersistStatusBarHidden(TargetMode.ForceStopOnly))
        assertTrue(shouldPersistStatusBarHidden(TargetMode.HideAll))
        assertTrue(shouldPersistStatusBarHidden(TargetMode.BothEngage))
        assertFalse(shouldPersistStatusBarHidden(TargetMode.RestoreOriginal))
        assertFalse(shouldPersistStatusBarHidden(TargetMode.BothDisengage))
    }

    @Test
    fun testOnlyNonCachedProcessesAreEligibleForForceStop() {
        assertTrue(
            isActiveProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        )
        assertTrue(
            isActiveProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
        )
        assertFalse(
            isActiveProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        )
        assertFalse(
            isActiveProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE)
        )
    }

    @Test
    fun targetAppIdentityRequiresPositiveMatchAndRejectsPreviousOrGenericScreens() {
        // Positive matches
        assertTrue(
            ForceStopMatcher.matchesTargetAppIdentity(
                nodeText = "Chrome",
                nodeDesc = null,
                viewId = "com.android.settings:id/entity_header_title",
                expectedAppName = "Chrome",
                expectedPackage = "com.android.chrome"
            )
        )
        assertTrue(
            ForceStopMatcher.matchesTargetAppIdentity(
                nodeText = "Package: com.android.chrome",
                nodeDesc = null,
                viewId = null,
                expectedAppName = "Chrome",
                expectedPackage = "com.android.chrome"
            )
        )

        // Reject previous app screen or generic Settings controls
        assertFalse(
            ForceStopMatcher.matchesTargetAppIdentity(
                nodeText = "Force stop",
                nodeDesc = null,
                viewId = "com.android.settings:id/button2",
                expectedAppName = "Chrome",
                expectedPackage = "com.android.chrome"
            )
        )
        assertFalse(
            ForceStopMatcher.matchesTargetAppIdentity(
                nodeText = "WhatsApp",
                nodeDesc = null,
                viewId = "com.android.settings:id/entity_header_title",
                expectedAppName = "Chrome",
                expectedPackage = "com.android.chrome"
            )
        )
        assertFalse(
            ForceStopMatcher.matchesTargetAppIdentity(
                nodeText = "YouTube Music",
                nodeDesc = null,
                viewId = "com.android.settings:id/entity_header_title",
                expectedAppName = "YouTube",
                expectedPackage = "com.google.android.youtube"
            )
        )
    }

    @Test
    fun windowEventsFromPreviousAppOrOwnPackageAreRejected() {
        val ownPkg = "com.statusbarmanager.app"
        val currentEvent = ForceStopWindowEvent(
            seq = 12L,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            attemptToken = 5L,
            targetPackage = "com.android.chrome"
        )
        assertTrue(
            ForceStopMatcher.shouldAcceptWindowEvent(
                event = currentEvent,
                minSeq = 10L,
                expectedAttemptToken = 5L,
                expectedTargetPackage = "com.android.chrome",
                ownPackage = ownPkg
            )
        )

        // Stale sequence from before current attempt launched
        assertFalse(
            ForceStopMatcher.shouldAcceptWindowEvent(
                event = currentEvent.copy(seq = 10L),
                minSeq = 10L,
                expectedAttemptToken = 5L,
                expectedTargetPackage = "com.android.chrome",
                ownPackage = ownPkg
            )
        )

        // Event tied to previous app attempt token / package
        assertFalse(
            ForceStopMatcher.shouldAcceptWindowEvent(
                event = currentEvent.copy(attemptToken = 4L, targetPackage = "com.whatsapp"),
                minSeq = 10L,
                expectedAttemptToken = 5L,
                expectedTargetPackage = "com.android.chrome",
                ownPackage = ownPkg
            )
        )

        // Own app event must never advance Settings transition
        assertFalse(
            ForceStopMatcher.shouldAcceptWindowEvent(
                event = currentEvent.copy(packageName = ownPkg),
                minSeq = 10L,
                expectedAttemptToken = 5L,
                expectedTargetPackage = "com.android.chrome",
                ownPackage = ownPkg
            )
        )
    }

    @Test
    fun forceStopButtonDetectionSupportsOemLabelsIdsAndExcludesForbiddenControls() {
        assertTrue(
            ForceStopMatcher.matchesForceStopButtonCandidate(
                text = "Force stop",
                desc = null,
                viewId = null
            )
        )
        assertTrue(
            ForceStopMatcher.matchesForceStopButtonCandidate(
                text = null,
                desc = "Force close",
                viewId = "com.oplus.appdetail:id/force_stop_btn"
            )
        )
        assertTrue(
            ForceStopMatcher.matchesForceStopButtonCandidate(
                text = "Beenden erzwingen",
                desc = null,
                viewId = null
            )
        )
        assertFalse(
            ForceStopMatcher.matchesForceStopButtonCandidate(
                text = "Uninstall",
                desc = null,
                viewId = "com.android.settings:id/button1"
            )
        )
        assertFalse(
            ForceStopMatcher.matchesForceStopButtonCandidate(
                text = "Clear storage",
                desc = null,
                viewId = null
            )
        )
    }

    @Test
    fun confirmationDialogRequiresForceStopContextAndExcludesCancelAndTitles() {
        // Generic OK dialog without Force Stop context must be rejected
        assertFalse(
            ForceStopMatcher.hasForceStopDialogContext(
                textsAndDescs = listOf("Battery optimization", "Disable optimization?", "Cancel", "OK"),
                viewIds = listOf("android:id/alertTitle", "android:id/message", "android:id/button2", "android:id/button1"),
                classNames = listOf("android.widget.TextView", "android.widget.TextView", "android.widget.Button", "android.widget.Button")
            )
        )

        // Authentic Force Stop confirmation dialog must be accepted
        assertTrue(
            ForceStopMatcher.hasForceStopDialogContext(
                textsAndDescs = listOf("Force stop?", "If you force stop an app, it may misbehave.", "Cancel", "OK"),
                viewIds = listOf("android:id/alertTitle", "android:id/message", "android:id/button2", "android:id/button1"),
                classNames = listOf("android.widget.TextView", "android.widget.TextView", "android.widget.Button", "android.widget.Button")
            )
        )

        // Cancel button must score 0; title must score 0; positive button must score > 0
        assertEquals(
            0,
            ForceStopMatcher.scoreConfirmationPositiveCandidate(
                text = "Cancel",
                desc = null,
                viewId = "android:id/button2",
                className = "android.widget.Button",
                isClickableOrHasClickableAncestor = true
            )
        )
        assertEquals(
            0,
            ForceStopMatcher.scoreConfirmationPositiveCandidate(
                text = "Force stop",
                desc = null,
                viewId = "android:id/alertTitle",
                className = "android.widget.TextView",
                isClickableOrHasClickableAncestor = false
            )
        )
        assertTrue(
            ForceStopMatcher.scoreConfirmationPositiveCandidate(
                text = "OK",
                desc = null,
                viewId = "android:id/button1",
                className = "android.widget.Button",
                isClickableOrHasClickableAncestor = true
            ) > 0
        )
    }

    @Test
    fun packageManagerVerificationAndBatchResultAccountingAreAccurate() {
        assertEquals(
            AppStoppedState.STOPPED,
            ForceStopMatcher.classifyStoppedState(ApplicationInfo.FLAG_STOPPED)
        )
        assertEquals(
            AppStoppedState.RUNNING,
            ForceStopMatcher.classifyStoppedState(0)
        )
        assertEquals(
            AppStoppedState.UNKNOWN,
            ForceStopMatcher.classifyStoppedState(null)
        )

        // PackageManager exception must return UNKNOWN_STATE, never SUCCESSFULLY_STOPPED
        assertEquals(
            ForceStopResult.UNKNOWN_STATE,
            ForceStopMatcher.reconcileFinalAppOutcome(
                controllerResult = ForceStopResult.SUCCESSFULLY_STOPPED,
                pmState = AppStoppedState.UNKNOWN
            )
        )

        // Confirmation clicked yet app still running must fail verification
        assertEquals(
            ForceStopResult.FAILED_STILL_RUNNING,
            ForceStopMatcher.reconcileFinalAppOutcome(
                controllerResult = ForceStopResult.SUCCESSFULLY_STOPPED,
                pmState = AppStoppedState.RUNNING
            )
        )

        val stats = ForceStopMatcher.computeBatchStats(
            totalEligible = 6,
            outcomes = listOf(
                ForceStopResult.SUCCESSFULLY_STOPPED,
                ForceStopResult.ALREADY_STOPPED,
                ForceStopResult.SKIPPED_PROTECTED,
                ForceStopResult.SKIPPED_WHITELISTED,
                ForceStopResult.FAILED_STILL_RUNNING,
                ForceStopResult.UNKNOWN_STATE
            )
        )
        assertEquals(6, stats.totalEligible)
        assertEquals(1, stats.successfullyStopped)
        assertEquals(1, stats.alreadyStopped)
        assertEquals(1, stats.skippedProtected)
        assertEquals(1, stats.skippedWhitelisted)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.unknown)
    }
}
