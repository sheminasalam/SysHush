package com.statusbarmanager.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.statusbarmanager.app.service.StatusBarAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class ForceStopResult {
    SUCCESSFULLY_STOPPED,
    ALREADY_STOPPED,
    UNAVAILABLE_OR_DISABLED,
    FAILED_OPENING_SETTINGS,
    FAILED_CONFIRMATION,
    FAILED_STILL_RUNNING,
    UNKNOWN_STATE,
    SKIPPED_PROTECTED,
    SKIPPED_WHITELISTED
}

enum class AppStoppedState {
    STOPPED,
    RUNNING,
    UNKNOWN
}

enum class ForceStopStage {
    OPEN_APP_INFO,
    WAIT_FOR_TARGET_WINDOW,
    VERIFY_TARGET_APPLICATION,
    FIND_FORCE_STOP_BUTTON,
    WAIT_FOR_BUTTON_READY,
    CLICK_FORCE_STOP,
    WAIT_FOR_CONFIRMATION,
    CONFIRM_STOP,
    WAIT_FOR_RESULT,
    VERIFY_STOPPED,
    COMPLETE,
    FAILED
}

data class ForceStopWindowEvent(
    val seq: Long,
    val eventType: Int,
    val packageName: String,
    val className: String,
    val attemptToken: Long,
    val targetPackage: String?
)

internal object ForceStopMatcher {
    private val SETTINGS_HOST_KEYWORDS = listOf(
        "settings",
        "appdetail",
        "appmanager",
        "securitycenter",
        "packageinstaller"
    )

    private val HEADER_TITLE_IDS = listOf(
        ":id/entity_header_title",
        ":id/app_name",
        ":id/header_title",
        ":id/app_title",
        ":id/action_bar_title"
    )

    private val FORCE_STOP_BUTTON_IDS = listOf(
        ":id/force_stop",
        ":id/force_stop_button",
        ":id/btn_force_stop",
        ":id/force_stop_btn",
        ":id/app_info_force_stop",
        ":id/action_force_stop"
    )

    private val FORCE_STOP_PHRASE_LABELS = listOf(
        "force stop",
        "force close",
        "arrêt forcé",
        "arrêtt forcé",
        "forzar detención",
        "beenden erzwingen",
        "forçar parada",
        "interrompi",
        "принудительно остановить"
    )

    private val FORCE_STOP_EXACT_LABELS = listOf(
        "stop",
        "end"
    )

    private val FORBIDDEN_APP_INFO_WORDS = listOf(
        "uninstall",
        "desinstalar",
        "clear",
        "disable",
        "notifications",
        "battery",
        "permission",
        "storage",
        "cache",
        "data usage",
        "archive",
        "open"
    )

    private val DIALOG_STRUCTURE_IDS = listOf(
        ":id/buttonpanel",
        ":id/parentpanel",
        ":id/alerttitle",
        ":id/custompanel",
        ":id/button1",
        ":id/button2",
        ":id/button_positive",
        ":id/button_negative",
        ":id/positive_button",
        ":id/negative_button",
        ":id/btn_confirm",
        ":id/btn_cancel"
    )

    private val DIALOG_POSITIVE_IDS = listOf(
        ":id/button1",
        ":id/button_positive",
        ":id/positive_button",
        ":id/btn_confirm",
        ":id/btn_ok",
        ":id/ok_button"
    )

    private val DIALOG_NEGATIVE_IDS = listOf(
        ":id/button2",
        ":id/button3",
        ":id/button_negative",
        ":id/negative_button",
        ":id/btn_cancel",
        ":id/cancel",
        ":id/cancel_button"
    )

    private val DIALOG_NON_BUTTON_IDS = listOf(
        ":id/alerttitle",
        ":id/title",
        ":id/message",
        ":id/dialog_title",
        ":id/dialog_message",
        ":id/msg",
        ":id/body",
        ":id/entity_header_title",
        ":id/summary"
    )

    private val CANCEL_LABELS = listOf(
        "cancel",
        "no",
        "dismiss",
        "keep",
        "back",
        "don't",
        "annuler",
        "cancelar",
        "abbrechen",
        "отмена",
        "não"
    )

    private val CONFIRMATION_BUTTON_LABELS = listOf(
        "ok",
        "okay",
        "force stop",
        "force close",
        "confirm",
        "yes",
        "d'accord",
        "aceptar",
        "ja",
        "sim",
        "да"
    )

    private val DIALOG_STRONG_CONTEXT_PHRASES = listOf(
        "if you force stop",
        "may misbehave",
        "misbehave",
        "cause errors",
        "force stop?",
        "force close?",
        "stop this app",
        "stop running"
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun isAppInfoHostPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return SETTINGS_HOST_KEYWORDS.any { lower.contains(it) }
    }

    fun isRelevantEventPackage(pkg: String, ownPackage: String): Boolean {
        if (pkg.isNotEmpty() && pkg == ownPackage) return false
        if (pkg.isEmpty()) return true
        val lower = pkg.lowercase()
        return isAppInfoHostPackage(lower) ||
            lower == "android" ||
            lower.contains("systemui") ||
            lower.contains("permissioncontroller") ||
            lower.contains("dialog")
    }

    fun shouldAcceptWindowEvent(
        event: ForceStopWindowEvent,
        minSeq: Long,
        expectedAttemptToken: Long,
        expectedTargetPackage: String,
        ownPackage: String
    ): Boolean {
        if (event.seq <= minSeq) return false
        if (event.attemptToken != expectedAttemptToken) return false
        if (event.targetPackage != expectedTargetPackage) return false
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return false
        }
        return isRelevantEventPackage(event.packageName, ownPackage)
    }

    fun matchesTargetAppIdentity(
        nodeText: String?,
        nodeDesc: String?,
        viewId: String?,
        expectedAppName: String,
        expectedPackage: String
    ): Boolean {
        val normText = normalize(nodeText)
        val normDesc = normalize(nodeDesc)
        val normLabel = normalize(expectedAppName)
        val normPackage = normalize(expectedPackage)
        val lowerId = viewId?.lowercase() ?: ""

        if (normPackage.isNotEmpty()) {
            if (containsPackageToken(normText, normPackage) || containsPackageToken(normDesc, normPackage)) {
                return true
            }
        }

        if (normLabel.isNotEmpty()) {
            if (normText.equals(normLabel, ignoreCase = true) ||
                normDesc.equals(normLabel, ignoreCase = true)
            ) {
                return true
            }
            if (HEADER_TITLE_IDS.any { lowerId.endsWith(it) }) {
                if (normText.startsWith("$normLabel ", ignoreCase = true) ||
                    normText.startsWith("$normLabel(", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun containsPackageToken(haystack: String, packageName: String): Boolean {
        if (haystack.isEmpty() || packageName.isEmpty()) return false
        if (haystack.equals(packageName, ignoreCase = true)) return true
        val escaped = Regex.escape(packageName)
        val pattern = Regex("(^|[^A-Za-z0-9_.])$escaped($|[^A-Za-z0-9_.])", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(haystack)
    }

    fun isForbiddenAppInfoControl(text: String?, desc: String?): Boolean {
        val lowerText = normalize(text).lowercase()
        val lowerDesc = normalize(desc).lowercase()
        return FORBIDDEN_APP_INFO_WORDS.any { word ->
            lowerText.contains(word) || lowerDesc.contains(word)
        }
    }

    fun isNonButtonTextId(viewId: String?): Boolean {
        val lowerId = viewId?.lowercase() ?: return false
        return DIALOG_NON_BUTTON_IDS.any { lowerId.endsWith(it) }
    }

    fun matchesForceStopButtonCandidate(
        text: String?,
        desc: String?,
        viewId: String?,
        extraSystemLabels: Set<String> = emptySet()
    ): Boolean {
        if (isNonButtonTextId(viewId)) return false
        if (isForbiddenAppInfoControl(text, desc)) return false

        val lowerId = viewId?.lowercase() ?: ""
        if (FORCE_STOP_BUTTON_IDS.any { lowerId.endsWith(it) }) {
            return true
        }

        val lowerText = normalize(text).lowercase()
        val lowerDesc = normalize(desc).lowercase()

        if (extraSystemLabels.any { label ->
                val norm = label.lowercase()
                norm.isNotEmpty() && (lowerText == norm || lowerDesc == norm ||
                    (norm.contains(" ") && (lowerText.contains(norm) || lowerDesc.contains(norm))))
            }
        ) {
            return true
        }

        if (FORCE_STOP_PHRASE_LABELS.any { label ->
                lowerText.contains(label) || lowerDesc.contains(label)
            }
        ) {
            return true
        }

        return FORCE_STOP_EXACT_LABELS.any { label ->
            lowerText == label || lowerDesc == label
        }
    }

    fun isNegativeOrCancelControl(
        text: String?,
        desc: String?,
        viewId: String?,
        extraCancelLabels: Set<String> = emptySet()
    ): Boolean {
        val lowerId = viewId?.lowercase() ?: ""
        if (DIALOG_NEGATIVE_IDS.any { lowerId.endsWith(it) }) return true

        val lowerText = normalize(text).lowercase()
        val lowerDesc = normalize(desc).lowercase()

        if (extraCancelLabels.any { cancel ->
                val norm = cancel.lowercase()
                norm.isNotEmpty() && (lowerText == norm || lowerDesc == norm)
            }
        ) {
            return true
        }

        return CANCEL_LABELS.any { cancel ->
            lowerText == cancel || lowerDesc == cancel ||
                (cancel.length > 3 && (lowerText.contains(cancel) || lowerDesc.contains(cancel)))
        }
    }

    fun hasForceStopDialogContext(
        textsAndDescs: List<String>,
        viewIds: List<String>,
        classNames: List<String>,
        extraDialogPhrases: Set<String> = emptySet()
    ): Boolean {
        var hasStructuralId = false
        var hasCancelControl = false
        for (i in viewIds.indices) {
            val id = viewIds[i].lowercase()
            if (DIALOG_STRUCTURE_IDS.any { id.endsWith(it) }) {
                hasStructuralId = true
            }
            val textOrDesc = textsAndDescs.getOrNull(i) ?: ""
            if (isNegativeOrCancelControl(textOrDesc, textOrDesc, id)) {
                hasCancelControl = true
            }
        }
        val hasDialogClass = classNames.any { cls ->
            val lower = cls.lowercase()
            lower.contains("dialog") || lower.contains("bottomsheet")
        }
        val hasDialogStructure = hasStructuralId || hasDialogClass || hasCancelControl

        val hasStrongPhrase = textsAndDescs.any { raw ->
            val lower = normalize(raw).lowercase()
            lower.isNotEmpty() && (
                DIALOG_STRONG_CONTEXT_PHRASES.any { lower.contains(it) } ||
                    extraDialogPhrases.any { phrase -> phrase.length >= 6 && lower.contains(phrase.lowercase()) }
                )
        }
        if (hasStrongPhrase && (hasDialogStructure || textsAndDescs.size <= 16)) {
            return true
        }

        val hasForceStopHeaderOrAction = textsAndDescs.any { raw ->
            val lower = normalize(raw).lowercase()
            FORCE_STOP_PHRASE_LABELS.any { lower.contains(it) } ||
                extraDialogPhrases.any { phrase -> phrase.isNotEmpty() && lower == phrase.lowercase() }
        }
        return hasDialogStructure && hasForceStopHeaderOrAction
    }

    fun scoreConfirmationPositiveCandidate(
        text: String?,
        desc: String?,
        viewId: String?,
        className: String?,
        isClickableOrHasClickableAncestor: Boolean,
        extraPositiveLabels: Set<String> = emptySet()
    ): Int {
        if (isNegativeOrCancelControl(text, desc, viewId)) return 0
        if (isNonButtonTextId(viewId)) return 0

        val lowerId = viewId?.lowercase() ?: ""
        val lowerText = normalize(text).lowercase()
        val lowerDesc = normalize(desc).lowercase()
        val lowerClass = className?.lowercase() ?: ""

        val isPositiveId = DIALOG_POSITIVE_IDS.any { lowerId.endsWith(it) }
        val isPositiveLabel = CONFIRMATION_BUTTON_LABELS.any { label ->
            lowerText == label || lowerDesc == label
        } || extraPositiveLabels.any { label ->
            val norm = label.lowercase()
            norm.isNotEmpty() && (lowerText == norm || lowerDesc == norm)
        }

        if (!isPositiveId && !isPositiveLabel) return 0

        val isButtonClass = lowerClass.contains("button")
        // Avoid accidentally picking an unclickable non-Button header that shares "Force stop" text
        if (!isPositiveId && !isButtonClass && !isClickableOrHasClickableAncestor) {
            if (lowerText != "ok" && lowerText != "okay" && lowerText != "confirm" && lowerText != "yes") {
                return 0
            }
        }

        var score = 0
        if (isPositiveId) score += 100
        if (isPositiveLabel) score += 60
        if (isButtonClass) score += 30
        if (isClickableOrHasClickableAncestor) score += 20
        return score
    }

    fun classifyStoppedState(flags: Int?): AppStoppedState {
        if (flags == null) return AppStoppedState.UNKNOWN
        return if ((flags and ApplicationInfo.FLAG_STOPPED) != 0) {
            AppStoppedState.STOPPED
        } else {
            AppStoppedState.RUNNING
        }
    }

    fun reconcileFinalAppOutcome(
        controllerResult: ForceStopResult,
        pmState: AppStoppedState
    ): ForceStopResult {
        if (controllerResult == ForceStopResult.SKIPPED_PROTECTED ||
            controllerResult == ForceStopResult.SKIPPED_WHITELISTED
        ) {
            return controllerResult
        }

        return when (pmState) {
            AppStoppedState.UNKNOWN -> ForceStopResult.UNKNOWN_STATE
            AppStoppedState.STOPPED -> {
                if (controllerResult == ForceStopResult.ALREADY_STOPPED) {
                    ForceStopResult.ALREADY_STOPPED
                } else {
                    ForceStopResult.SUCCESSFULLY_STOPPED
                }
            }
            AppStoppedState.RUNNING -> {
                when (controllerResult) {
                    ForceStopResult.UNAVAILABLE_OR_DISABLED,
                    ForceStopResult.FAILED_OPENING_SETTINGS,
                    ForceStopResult.FAILED_CONFIRMATION,
                    ForceStopResult.FAILED_STILL_RUNNING -> controllerResult
                    else -> ForceStopResult.FAILED_STILL_RUNNING
                }
            }
        }
    }

    fun computeBatchStats(
        totalEligible: Int,
        outcomes: Collection<ForceStopResult>
    ): AppForceStopStats {
        var stopped = 0
        var already = 0
        var protectedCount = 0
        var whitelistedCount = 0
        var failed = 0
        var unknown = 0

        for (outcome in outcomes) {
            when (outcome) {
                ForceStopResult.SUCCESSFULLY_STOPPED -> stopped++
                ForceStopResult.ALREADY_STOPPED -> already++
                ForceStopResult.SKIPPED_PROTECTED -> protectedCount++
                ForceStopResult.SKIPPED_WHITELISTED -> whitelistedCount++
                ForceStopResult.UNKNOWN_STATE -> unknown++
                ForceStopResult.UNAVAILABLE_OR_DISABLED,
                ForceStopResult.FAILED_OPENING_SETTINGS,
                ForceStopResult.FAILED_CONFIRMATION,
                ForceStopResult.FAILED_STILL_RUNNING -> failed++
            }
        }

        return AppForceStopStats(
            totalEligible = totalEligible,
            successfullyStopped = stopped,
            alreadyStopped = already,
            skippedProtected = protectedCount,
            skippedWhitelisted = whitelistedCount,
            failed = failed,
            unknown = unknown,
            remaining = 0,
            currentAppName = ""
        )
    }
}

/**
 * Executes native Settings Accessibility-based Force Stop automation for an application
 * using a staged, event-driven state machine with positive target verification,
 * robust multi-OEM button and confirmation dialog handling, and PackageManager ground-truth verification.
 */
class AppForceStopAccessibilityController(
    private val context: Context,
    private val service: StatusBarAccessibilityService
) {

    companion object {
        private const val TAG = "AppForceStopController"
        private const val MAX_PARENT_DEPTH = 6
        private const val MAX_PER_APP_ATTEMPTS = 2
        private const val POLL_FALLBACK_SLICE_MS = 85L

        private const val WAIT_TARGET_WINDOW_TIMEOUT_MS = 2200L
        private const val VERIFY_TARGET_APP_TIMEOUT_MS = 2200L
        private const val FIND_BUTTON_TIMEOUT_MS = 1200L
        private const val BUTTON_READY_TIMEOUT_MS = 1200L
        private const val WAIT_CONFIRMATION_TIMEOUT_MS = 1800L
        private const val WAIT_DIALOG_DISMISS_TIMEOUT_MS = 1400L
        private const val WAIT_RESULT_SETTLE_TIMEOUT_MS = 1000L
        private const val VERIFY_PM_TIMEOUT_MS = 350L
    }

    private data class ResolvedSystemStrings(
        val forceStopLabels: Set<String>,
        val positiveLabels: Set<String>,
        val cancelLabels: Set<String>,
        val dialogContextPhrases: Set<String>
    )

    private val systemStrings: ResolvedSystemStrings by lazy { resolveSystemStrings() }

    private val eventSeqCounter = AtomicLong(0L)
    private val attemptTokenCounter = AtomicLong(0L)

    @Volatile
    private var activeAttemptToken: Long = 0L

    @Volatile
    private var activeTargetPackage: String? = null

    private val windowEvents = MutableSharedFlow<ForceStopWindowEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Lightweight, non-blocking event entry point called from AccessibilityService.onAccessibilityEvent.
     */
    fun notifyWindowEvent(eventType: Int, packageName: String, className: String) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val token = activeAttemptToken
        val target = activeTargetPackage ?: return
        if (token == 0L) return
        if (!ForceStopMatcher.isRelevantEventPackage(packageName, context.packageName)) return

        val seq = eventSeqCounter.incrementAndGet()
        windowEvents.tryEmit(
            ForceStopWindowEvent(
                seq = seq,
                eventType = eventType,
                packageName = packageName,
                className = className,
                attemptToken = token,
                targetPackage = target
            )
        )
    }

    fun checkAppStoppedState(packageName: String): AppStoppedState {
        return ForceStopMatcher.classifyStoppedState(readPackageFlagsOrNull(packageName))
    }

    private fun readPackageFlagsOrNull(packageName: String): Int? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            appInfo.flags
        } catch (e: Exception) {
            Log.w(TAG, "PackageManager query failed for $packageName: ${e.message}")
            null
        }
    }

    /**
     * Attempts to force stop a single target app with bounded internal retries
     * for transient UI transition/confirmation failures.
     */
    suspend fun forceStopApp(
        appItem: AppItem,
        includedSystemApps: Set<String> = emptySet()
    ): ForceStopResult {
        if (appItem.isProtectedRole) {
            return ForceStopResult.SKIPPED_PROTECTED
        }
        if (appItem.isSystemApp && !appItem.isIncludedSystemApp && !includedSystemApps.contains(appItem.packageName)) {
            return ForceStopResult.SKIPPED_PROTECTED
        }
        if (appItem.isWhitelisted) {
            return ForceStopResult.SKIPPED_WHITELISTED
        }

        val initialPmState = checkAppStoppedState(appItem.packageName)
        if (initialPmState == AppStoppedState.STOPPED) {
            Log.i(TAG, "PackageManager confirms already stopped before Settings launch: ${appItem.packageName}")
            return ForceStopResult.ALREADY_STOPPED
        }

        var lastResult = ForceStopResult.FAILED_OPENING_SETTINGS
        for (attempt in 1..MAX_PER_APP_ATTEMPTS) {
            if (attempt > 1) {
                val preRetryPmState = checkAppStoppedState(appItem.packageName)
                if (preRetryPmState == AppStoppedState.STOPPED) {
                    Log.i(TAG, "App confirmed stopped before internal retry #$attempt: ${appItem.packageName}")
                    return ForceStopResult.SUCCESSFULLY_STOPPED
                }
                Log.i(TAG, "Retrying staged force-stop attempt #$attempt for ${appItem.packageName}")
            }

            lastResult = try {
                executeSingleStagedAttempt(appItem)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception in staged attempt #$attempt for ${appItem.packageName}: ${e.message}", e)
                closeAppInfoIfInSettings()
                ForceStopResult.FAILED_CONFIRMATION
            }

            when (lastResult) {
                ForceStopResult.SUCCESSFULLY_STOPPED,
                ForceStopResult.ALREADY_STOPPED,
                ForceStopResult.SKIPPED_PROTECTED,
                ForceStopResult.SKIPPED_WHITELISTED,
                ForceStopResult.UNKNOWN_STATE -> return lastResult
                ForceStopResult.UNAVAILABLE_OR_DISABLED -> {
                    // Only retry if the UI wasn't ready; if still running, one retry may help if OEM rendered late
                    if (attempt == MAX_PER_APP_ATTEMPTS) return lastResult
                }
                ForceStopResult.FAILED_OPENING_SETTINGS,
                ForceStopResult.FAILED_CONFIRMATION,
                ForceStopResult.FAILED_STILL_RUNNING -> {
                    if (attempt == MAX_PER_APP_ATTEMPTS) return lastResult
                }
            }
        }
        return lastResult
    }

    private suspend fun executeSingleStagedAttempt(appItem: AppItem): ForceStopResult {
        val attemptToken = attemptTokenCounter.incrementAndGet()
        val baselineSeq = eventSeqCounter.get()
        activeAttemptToken = attemptToken
        activeTargetPackage = appItem.packageName

        var stage = ForceStopStage.OPEN_APP_INFO
        var lastSeq = baselineSeq
        var targetPageConfirmed = false
        var confirmationDispatched = false
        var finalResult: ForceStopResult = ForceStopResult.FAILED_OPENING_SETTINGS

        try {
            Log.i(TAG, "Stage $stage for ${appItem.packageName} (${appItem.appName})")

            // Stage 1: OPEN_APP_INFO
            val launched = openAppDetailsSettings(appItem.packageName)
            if (!launched) {
                stage = ForceStopStage.FAILED
                return ForceStopResult.FAILED_OPENING_SETTINGS
            }

            // Stage 2: WAIT_FOR_TARGET_WINDOW
            stage = ForceStopStage.WAIT_FOR_TARGET_WINDOW
            val (windowReady, seqAfterWindow) = awaitStageCondition(
                timeoutMs = WAIT_TARGET_WINDOW_TIMEOUT_MS,
                attemptToken = attemptToken,
                targetPackage = appItem.packageName,
                initialMinSeq = lastSeq,
                requireEventBeforeFirstCheck = true
            ) {
                val root = service.rootInActiveWindow ?: return@awaitStageCondition null
                try {
                    val pkg = root.packageName?.toString() ?: ""
                    if (ForceStopMatcher.isAppInfoHostPackage(pkg) && !isConfirmationDialogWindow(root)) {
                        true
                    } else {
                        null
                    }
                } finally {
                    root.recycle()
                }
            }
            lastSeq = seqAfterWindow
            if (windowReady != true) {
                Log.w(TAG, "Stage $stage timed out waiting for Settings window for ${appItem.packageName}")
                stage = ForceStopStage.FAILED
                return ForceStopResult.FAILED_OPENING_SETTINGS
            }

            // Stage 3: VERIFY_TARGET_APPLICATION
            stage = ForceStopStage.VERIFY_TARGET_APPLICATION
            val (verifiedTarget, seqAfterVerify) = awaitStageCondition(
                timeoutMs = VERIFY_TARGET_APP_TIMEOUT_MS,
                attemptToken = attemptToken,
                targetPackage = appItem.packageName,
                initialMinSeq = lastSeq
            ) {
                val root = service.rootInActiveWindow ?: return@awaitStageCondition null
                try {
                    if (isTargetAppInfoPage(root, appItem)) true else null
                } finally {
                    root.recycle()
                }
            }
            lastSeq = seqAfterVerify
            if (verifiedTarget != true) {
                Log.w(TAG, "Stage $stage failed to positively identify ${appItem.packageName}; aborting safely")
                stage = ForceStopStage.FAILED
                return ForceStopResult.FAILED_OPENING_SETTINGS
            }
            targetPageConfirmed = true

            // Stage 4: FIND_FORCE_STOP_BUTTON
            stage = ForceStopStage.FIND_FORCE_STOP_BUTTON
            val (buttonFound, seqAfterFind) = awaitStageCondition(
                timeoutMs = FIND_BUTTON_TIMEOUT_MS,
                attemptToken = attemptToken,
                targetPackage = appItem.packageName,
                initialMinSeq = lastSeq
            ) {
                val root = service.rootInActiveWindow ?: return@awaitStageCondition null
                try {
                    if (!isTargetAppInfoPage(root, appItem)) return@awaitStageCondition null
                    val button = findForceStopButton(root)
                    if (button != null) {
                        if (button !== root) button.recycle()
                        true
                    } else {
                        null
                    }
                } finally {
                    root.recycle()
                }
            }
            lastSeq = seqAfterFind
            if (buttonFound != true) {
                Log.w(TAG, "Stage $stage could not locate Force Stop control on verified page for ${appItem.packageName}")
                stage = ForceStopStage.FAILED
                return ForceStopResult.UNAVAILABLE_OR_DISABLED
            }

            // Stage 5: WAIT_FOR_BUTTON_READY
            stage = ForceStopStage.WAIT_FOR_BUTTON_READY
            val (buttonEnabled, seqAfterReady) = awaitStageCondition(
                timeoutMs = BUTTON_READY_TIMEOUT_MS,
                attemptToken = attemptToken,
                targetPackage = appItem.packageName,
                initialMinSeq = lastSeq
            ) {
                val root = service.rootInActiveWindow ?: return@awaitStageCondition null
                try {
                    if (!isTargetAppInfoPage(root, appItem)) return@awaitStageCondition null
                    val button = findForceStopButton(root) ?: return@awaitStageCondition null
                    val enabled = isNodeAndClickableChainEnabled(button)
                    if (button !== root) button.recycle()
                    if (enabled) true else null
                } finally {
                    root.recycle()
                }
            }
            lastSeq = seqAfterReady
            if (buttonEnabled != true) {
                val pmCheck = checkAppStoppedState(appItem.packageName)
                stage = if (pmCheck == AppStoppedState.STOPPED) ForceStopStage.COMPLETE else ForceStopStage.FAILED
                return when (pmCheck) {
                    AppStoppedState.STOPPED -> {
                        Log.i(TAG, "Force Stop button remained disabled and PM confirms stopped: ${appItem.packageName}")
                        ForceStopResult.ALREADY_STOPPED
                    }
                    AppStoppedState.RUNNING -> {
                        Log.w(TAG, "Force Stop button remained disabled while app still running: ${appItem.packageName}")
                        ForceStopResult.UNAVAILABLE_OR_DISABLED
                    }
                    AppStoppedState.UNKNOWN -> ForceStopResult.UNKNOWN_STATE
                }
            }

            // Stage 6: CLICK_FORCE_STOP (always obtain fresh verified node right before clicking)
            stage = ForceStopStage.CLICK_FORCE_STOP
            val preClickSeq = eventSeqCounter.get()
            val forceStopClicked = clickVerifiedForceStopButton(appItem)
            if (!forceStopClicked) {
                Log.e(TAG, "Stage $stage failed all click fallbacks for ${appItem.packageName}")
                stage = ForceStopStage.FAILED
                return ForceStopResult.UNAVAILABLE_OR_DISABLED
            }
            lastSeq = preClickSeq

            // Stage 7: WAIT_FOR_CONFIRMATION
            stage = ForceStopStage.WAIT_FOR_CONFIRMATION
            val (confirmationOutcome, seqAfterWaitConfirm) = awaitStageCondition(
                timeoutMs = WAIT_CONFIRMATION_TIMEOUT_MS,
                attemptToken = attemptToken,
                targetPackage = appItem.packageName,
                initialMinSeq = lastSeq
            ) {
                val dialogNode = findVerifiedConfirmationButton()
                if (dialogNode != null) {
                    dialogNode.recycle()
                    return@awaitStageCondition "DIALOG_READY"
                }

                // Some OEMs stop immediately without showing a confirmation dialog.
                // Only accept this if PM confirms STOPPED and no dialog is present.
                if (checkAppStoppedState(appItem.packageName) == AppStoppedState.STOPPED) {
                    return@awaitStageCondition "ALREADY_STOPPED_DIRECTLY"
                }
                null
            }
            lastSeq = seqAfterWaitConfirm

            if (confirmationOutcome == null) {
                Log.w(TAG, "Stage $stage timed out waiting for confirmation dialog for ${appItem.packageName}")
                stage = ForceStopStage.FAILED
                return ForceStopResult.FAILED_CONFIRMATION
            }

            if (confirmationOutcome == "DIALOG_READY") {
                // Stage 8: CONFIRM_STOP
                stage = ForceStopStage.CONFIRM_STOP
                val preConfirmSeq = eventSeqCounter.get()
                val confirmClicked = clickVerifiedConfirmationButton()
                if (!confirmClicked) {
                    Log.w(TAG, "Stage $stage failed to click confirmation positive button for ${appItem.packageName}")
                    stage = ForceStopStage.FAILED
                    return ForceStopResult.FAILED_CONFIRMATION
                }
                confirmationDispatched = true
                lastSeq = preConfirmSeq

                // Stage 9: WAIT_FOR_RESULT
                // 9a: Wait for the confirmation dialog itself to disappear first.
                stage = ForceStopStage.WAIT_FOR_RESULT
                val (dialogDismissed, seqAfterDismiss) = awaitStageCondition(
                    timeoutMs = WAIT_DIALOG_DISMISS_TIMEOUT_MS,
                    attemptToken = attemptToken,
                    targetPackage = appItem.packageName,
                    initialMinSeq = lastSeq
                ) {
                    val remainingDialogBtn = findVerifiedConfirmationButton()
                    if (remainingDialogBtn != null) {
                        remainingDialogBtn.recycle()
                        null
                    } else {
                        true
                    }
                }
                lastSeq = seqAfterDismiss
                if (dialogDismissed != true) {
                    Log.w(TAG, "Stage $stage: confirmation dialog did not dismiss in time for ${appItem.packageName}")
                    stage = ForceStopStage.FAILED
                    return ForceStopResult.FAILED_CONFIRMATION
                }

                // 9b: Wait for Settings button state or PackageManager to reflect stop result.
                val (_, seqAfterSettle) = awaitStageCondition(
                    timeoutMs = WAIT_RESULT_SETTLE_TIMEOUT_MS,
                    attemptToken = attemptToken,
                    targetPackage = appItem.packageName,
                    initialMinSeq = lastSeq
                ) {
                    if (checkAppStoppedState(appItem.packageName) == AppStoppedState.STOPPED) {
                        return@awaitStageCondition true
                    }
                    val root = service.rootInActiveWindow ?: return@awaitStageCondition null
                    try {
                        if (isTargetAppInfoPage(root, appItem)) {
                            val btn = findForceStopButton(root)
                            val disabled = btn != null && !isNodeAndClickableChainEnabled(btn)
                            if (btn != null && btn !== root) btn.recycle()
                            if (disabled) true else null
                        } else {
                            null
                        }
                    } finally {
                        root.recycle()
                    }
                }
                lastSeq = seqAfterSettle
            }

            // Stage 10: VERIFY_STOPPED (authoritative PackageManager ground truth)
            stage = ForceStopStage.VERIFY_STOPPED
            val verifiedPmState = awaitPackageManagerStopConfirmation(
                packageName = appItem.packageName,
                attemptToken = attemptToken,
                initialMinSeq = lastSeq
            )

            finalResult = when (verifiedPmState) {
                AppStoppedState.STOPPED -> {
                    stage = ForceStopStage.COMPLETE
                    Log.i(TAG, "Verified Force Stop succeeded via PackageManager for ${appItem.packageName}")
                    ForceStopResult.SUCCESSFULLY_STOPPED
                }
                AppStoppedState.RUNNING -> {
                    stage = ForceStopStage.FAILED
                    Log.w(TAG, "App still reported RUNNING by PackageManager after confirmation for ${appItem.packageName}")
                    if (confirmationDispatched) ForceStopResult.FAILED_STILL_RUNNING else ForceStopResult.FAILED_CONFIRMATION
                }
                AppStoppedState.UNKNOWN -> {
                    stage = ForceStopStage.FAILED
                    Log.w(TAG, "PackageManager state UNKNOWN after confirmation for ${appItem.packageName}")
                    ForceStopResult.UNKNOWN_STATE
                }
            }
            return finalResult
        } finally {
            activeTargetPackage = null
            activeAttemptToken = 0L
            if (targetPageConfirmed || isActiveWindowSettingsOrAppInfo()) {
                closeAppInfoIfInSettings()
            }
            Log.d(TAG, "Finished attempt for ${appItem.packageName} at stage=$stage, result=$finalResult")
        }
    }

    private suspend fun awaitPackageManagerStopConfirmation(
        packageName: String,
        attemptToken: Long,
        initialMinSeq: Long
    ): AppStoppedState {
        var latestState = checkAppStoppedState(packageName)
        if (latestState != AppStoppedState.RUNNING) {
            return latestState
        }

        val (confirmedState, _) = awaitStageCondition(
            timeoutMs = VERIFY_PM_TIMEOUT_MS,
            pollFallbackMs = 70L,
            attemptToken = attemptToken,
            targetPackage = packageName,
            initialMinSeq = initialMinSeq
        ) {
            val state = checkAppStoppedState(packageName)
            latestState = state
            if (state != AppStoppedState.RUNNING) state else null
        }
        return confirmedState ?: latestState
    }

    private suspend fun <T> awaitStageCondition(
        timeoutMs: Long,
        pollFallbackMs: Long = POLL_FALLBACK_SLICE_MS,
        attemptToken: Long,
        targetPackage: String,
        initialMinSeq: Long,
        requireEventBeforeFirstCheck: Boolean = false,
        evaluate: () -> T?
    ): Pair<T?, Long> {
        var lastSeq = initialMinSeq
        val deadline = System.currentTimeMillis() + timeoutMs

        if (!requireEventBeforeFirstCheck) {
            val immediate = evaluate()
            if (immediate != null) {
                return Pair(immediate, maxOf(lastSeq, eventSeqCounter.get()))
            }
        }

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break

            val currentLatestSeq = eventSeqCounter.get()
            if (currentLatestSeq > lastSeq) {
                lastSeq = currentLatestSeq
                val res = evaluate()
                if (res != null) {
                    return Pair(res, lastSeq)
                }
            }

            val slice = minOf(pollFallbackMs, deadline - System.currentTimeMillis())
            if (slice <= 0L) break

            val receivedEvent = withTimeoutOrNull(slice) {
                windowEvents.first { event ->
                    ForceStopMatcher.shouldAcceptWindowEvent(
                        event = event,
                        minSeq = lastSeq,
                        expectedAttemptToken = attemptToken,
                        expectedTargetPackage = targetPackage,
                        ownPackage = context.packageName
                    )
                }
            }

            lastSeq = maxOf(lastSeq, receivedEvent?.seq ?: eventSeqCounter.get())
            val res = evaluate()
            if (res != null) {
                return Pair(res, lastSeq)
            }
        }

        return Pair(null, lastSeq)
    }

    /**
     * Strictly requires positive identification of the target app's App Info screen.
     * Never uses generic Settings/control fallbacks and never reuses stale node trees.
     */
    internal fun isTargetAppInfoPage(root: AccessibilityNodeInfo, appItem: AppItem): Boolean {
        val rootPkg = root.packageName?.toString() ?: ""
        if (!ForceStopMatcher.isAppInfoHostPackage(rootPkg)) {
            return false
        }
        if (isConfirmationDialogWindow(root)) {
            return false
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var matched = false

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (ForceStopMatcher.matchesTargetAppIdentity(
                    nodeText = node.text?.toString(),
                    nodeDesc = node.contentDescription?.toString(),
                    viewId = node.viewIdResourceName,
                    expectedAppName = appItem.appName,
                    expectedPackage = appItem.packageName
                )
            ) {
                matched = true
                if (node !== root) node.recycle()
                break
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
            if (node !== root) node.recycle()
        }

        while (queue.isNotEmpty()) {
            val leftover = queue.removeFirst()
            if (leftover !== root) leftover.recycle()
        }
        return matched
    }

    private fun openAppDetailsSettings(packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting details activity for $packageName: ${e.message}")
            false
        }
    }

    private fun isActiveWindowSettingsOrAppInfo(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val pkg = root.packageName?.toString() ?: ""
            ForceStopMatcher.isAppInfoHostPackage(pkg)
        } finally {
            root.recycle()
        }
    }

    private fun closeAppInfoIfInSettings() {
        if (isActiveWindowSettingsOrAppInfo()) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Finds the Force Stop button across diverse OEM accessibility hierarchies.
     * Supports text, contentDescription, viewIdResourceName, and resolved Settings labels.
     * Does NOT reject nodes whose clickable container is multiple parent levels up.
     */
    internal fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestCandidate: AccessibilityNodeInfo? = null
        var bestScore = -1

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName

            val matches = ForceStopMatcher.matchesForceStopButtonCandidate(
                text = text,
                desc = desc,
                viewId = viewId,
                extraSystemLabels = systemStrings.forceStopLabels
            )

            if (matches) {
                val score = scoreForceStopButtonNode(node)
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate?.recycle()
                    bestCandidate = AccessibilityNodeInfo.obtain(node)
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
            if (node !== root) node.recycle()
        }
        return bestCandidate
    }

    private fun scoreForceStopButtonNode(node: AccessibilityNodeInfo): Int {
        var score = 10
        val id = node.viewIdResourceName?.lowercase() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (id.contains("force_stop")) score += 50
        if (cls.contains("button")) score += 25
        if (isClickableNode(node)) score += 20
        if (node.isVisibleToUser) score += 10
        return score
    }

    private fun isClickableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun isNodeAndClickableChainEnabled(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isScrollable) {
                current.recycle()
                break
            }
            if (isClickableNode(current)) {
                val enabled = current.isEnabled
                current.recycle()
                return enabled
            }
            if (!current.isEnabled) {
                current.recycle()
                return false
            }
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return true
    }

    private suspend fun clickVerifiedForceStopButton(appItem: AppItem): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            if (!isTargetAppInfoPage(root, appItem)) {
                Log.w(TAG, "Aborting click: active root no longer verified as ${appItem.packageName}")
                return false
            }
            val button = findForceStopButton(root) ?: return false
            try {
                if (!isNodeAndClickableChainEnabled(button)) {
                    return false
                }
                return clickNodeWithFallbackSequence(button)
            } finally {
                if (button !== root) button.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectCandidateWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val windows = service.windows
            for (window in windows) {
                val winRoot = window.root ?: continue
                val pkg = winRoot.packageName?.toString() ?: ""
                if (pkg == context.packageName) {
                    winRoot.recycle()
                    continue
                }
                roots.add(winRoot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Window enumeration exception: ${e.message}")
        }

        if (roots.isEmpty()) {
            val activeRoot = service.rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (pkg != context.packageName) {
                    roots.add(activeRoot)
                } else {
                    activeRoot.recycle()
                }
            }
        }
        return roots
    }

    private fun isConfirmationDialogWindow(root: AccessibilityNodeInfo): Boolean {
        val textsAndDescs = mutableListOf<String>()
        val viewIds = mutableListOf<String>()
        val classNames = mutableListOf<String>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = ForceStopMatcher.normalize(node.text?.toString())
            val desc = ForceStopMatcher.normalize(node.contentDescription?.toString())
            textsAndDescs.add(if (text.isNotEmpty()) text else desc)
            viewIds.add(node.viewIdResourceName ?: "")
            classNames.add(node.className?.toString() ?: "")

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
            if (node !== root) node.recycle()
        }

        return ForceStopMatcher.hasForceStopDialogContext(
            textsAndDescs = textsAndDescs,
            viewIds = viewIds,
            classNames = classNames,
            extraDialogPhrases = systemStrings.dialogContextPhrases
        )
    }

    private fun findVerifiedConfirmationButton(): AccessibilityNodeInfo? {
        val roots = collectCandidateWindowRoots()
        var bestButton: AccessibilityNodeInfo? = null
        var bestScore = 0

        for (winRoot in roots) {
            if (isConfirmationDialogWindow(winRoot)) {
                val candidate = findPositiveDialogButton(winRoot)
                if (candidate != null) {
                    if (candidate.second > bestScore) {
                        bestButton?.recycle()
                        bestButton = candidate.first
                        bestScore = candidate.second
                    } else {
                        candidate.first.recycle()
                    }
                }
            }
            winRoot.recycle()
        }

        if (bestButton == null && bestScore == 0) {
            return null
        }
        return bestButton
    }

    private fun findPositiveDialogButton(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, Int>? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            val cls = node.className?.toString()

            val isCancel = ForceStopMatcher.isNegativeOrCancelControl(
                text = text,
                desc = desc,
                viewId = viewId,
                extraCancelLabels = systemStrings.cancelLabels
            )
            if (!isCancel) {
                val hasClickable = isClickableNode(node) || hasClickableAncestor(node)
                val score = ForceStopMatcher.scoreConfirmationPositiveCandidate(
                    text = text,
                    desc = desc,
                    viewId = viewId,
                    className = cls,
                    isClickableOrHasClickableAncestor = hasClickable,
                    extraPositiveLabels = systemStrings.positiveLabels
                )
                if (score > bestScore && node.isEnabled) {
                    bestScore = score
                    bestNode?.let { if (it !== root) it.recycle() }
                    bestNode = AccessibilityNodeInfo.obtain(node)
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
            if (node !== root) node.recycle()
        }

        return bestNode?.let { Pair(it, bestScore) }
    }

    private suspend fun clickVerifiedConfirmationButton(): Boolean {
        val confirmButton = findVerifiedConfirmationButton() ?: return false
        return try {
            clickNodeWithFallbackSequence(confirmButton)
        } finally {
            confirmButton.recycle()
        }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < MAX_PARENT_DEPTH) {
            if (parent.isScrollable) {
                parent.recycle()
                return false
            }
            if (isClickableNode(parent) && parent.isEnabled) {
                parent.recycle()
                return true
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            depth++
        }
        parent?.recycle()
        return false
    }

    /**
     * Required click fallback sequence:
     * 1. Accessibility ACTION_CLICK on the button node.
     * 2. ACTION_CLICK on its clickable ancestor across multiple parent levels.
     * 3. Coordinate-based gesture tap using the verified button's screen bounds.
     */
    private suspend fun clickNodeWithFallbackSequence(node: AccessibilityNodeInfo): Boolean {
        // 1. Direct Accessibility ACTION_CLICK on verified button
        if (isClickableNode(node) && node.isEnabled) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // 2. Walk ancestor chain up to MAX_PARENT_DEPTH levels for clickable ancestor
        var ancestor: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (ancestor != null && depth < MAX_PARENT_DEPTH) {
            if (ancestor.isScrollable) {
                ancestor.recycle()
                ancestor = null
                break
            }
            if (isClickableNode(ancestor) && ancestor.isEnabled) {
                if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    ancestor.recycle()
                    return true
                }
            }
            val next = ancestor.parent
            ancestor.recycle()
            ancestor = next
            depth++
        }
        ancestor?.recycle()

        // 3. Coordinate-based gesture tap using verified bounds (button first, then nearest non-empty ancestor)
        val bounds = resolveScreenBounds(node) ?: return false
        return dispatchTapGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun resolveScreenBounds(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            return rect
        }
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < MAX_PARENT_DEPTH) {
            if (parent.isScrollable) {
                parent.recycle()
                return null
            }
            val parentRect = Rect()
            parent.getBoundsInScreen(parentRect)
            if (!parentRect.isEmpty && parentRect.centerX() > 0 && parentRect.centerY() > 0) {
                parent.recycle()
                return parentRect
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            depth++
        }
        parent?.recycle()
        return null
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float): Boolean {
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val mainHandler = Handler(Looper.getMainLooper())

        return withTimeoutOrNull(650L) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
                if (!dispatched && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveSystemStrings(): ResolvedSystemStrings {
        val forceStopLabels = linkedSetOf<String>()
        val positiveLabels = linkedSetOf<String>()
        val cancelLabels = linkedSetOf<String>()
        val dialogContextPhrases = linkedSetOf<String>()

        try {
            val sysOk = ForceStopMatcher.normalize(context.getString(android.R.string.ok))
            if (sysOk.isNotEmpty()) positiveLabels.add(sysOk)
            val sysCancel = ForceStopMatcher.normalize(context.getString(android.R.string.cancel))
            if (sysCancel.isNotEmpty()) cancelLabels.add(sysCancel)
        } catch (_: Exception) {
        }

        val candidatePackages = listOf(
            "com.android.settings",
            "com.oplus.appdetail",
            "com.coloros.appmanager",
            "com.miui.securitycenter"
        )

        for (pkg in candidatePackages) {
            try {
                val res = context.packageManager.getResourcesForApplication(pkg)
                readStringResource(res, pkg, "force_stop")?.let {
                    forceStopLabels.add(it)
                    positiveLabels.add(it)
                    dialogContextPhrases.add(it)
                }
                readStringResource(res, pkg, "force_stop_button")?.let { forceStopLabels.add(it) }
                readStringResource(res, pkg, "stop_app")?.let { forceStopLabels.add(it) }
                readStringResource(res, pkg, "force_stop_dlg_title")?.let { dialogContextPhrases.add(it) }
                readStringResource(res, pkg, "force_stop_dlg_text")?.let { dialogContextPhrases.add(it) }
                readStringResource(res, pkg, "dlg_ok")?.let { positiveLabels.add(it) }
                readStringResource(res, pkg, "okay")?.let { positiveLabels.add(it) }
                readStringResource(res, pkg, "dlg_cancel")?.let { cancelLabels.add(it) }
            } catch (_: Exception) {
                // Host package not present on this device
            }
        }

        return ResolvedSystemStrings(
            forceStopLabels = forceStopLabels,
            positiveLabels = positiveLabels,
            cancelLabels = cancelLabels,
            dialogContextPhrases = dialogContextPhrases
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun readStringResource(res: Resources, pkg: String, name: String): String? {
        return try {
            val id = res.getIdentifier(name, "string", pkg)
            if (id != 0) {
                val value = ForceStopMatcher.normalize(res.getString(id))
                value.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
