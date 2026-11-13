package ai.opencyclon.action

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import ai.opencyclon.accessibility.FocusedField
import ai.opencyclon.accessibility.VdAccessibilityService
import ai.opencyclon.assist.HomeMode
import ai.opencyclon.backend.PrivilegeBackend
import ai.opencyclon.backend.SystemBackend
import ai.opencyclon.engine.StepResult
import ai.opencyclon.input.InputInjector
import kotlinx.coroutines.delay

/**
 * Dispatches Action objects to InputInjector / AppLauncher and returns StepResults.
 * Ported from Python ActionExecutor.
 *
 * @param displayId Target display (0 for physical, >0 for virtual)
 * @param displaySize Fixed display size (for virtual displays with known resolution)
 */
class ActionExecutor(
    private val context: Context,
    private val displayId: Int = 0,
    displaySize: Point? = null,
    private val onOpenAppSuccess: ((packageName: String) -> Unit)? = null,
    backend: PrivilegeBackend = SystemBackend(),
    private val blacklistedPackages: Set<String> = emptySet(),
    private val toolCaller: (suspend (server: String, tool: String, argumentsJson: String) -> Pair<Boolean, String>)? = null,
    /** Only an editable input focus on this display; null for buttons, no focus, or unreadable focus. */
    private val focusedField: (Int) -> FocusedField? = { VdAccessibilityService.focusedField(it) },
    /** Whether this app may place calls (CALL_PHONE). Tests replace it. */
    private val canCall: () -> Boolean = {
        context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    },
) {

    companion object {
        const val NO_INTEGRATIONS = "No integrations are connected"
        const val TOOL_FAILED = "Tool call failed"
        const val NO_TEXT_FIELD = "No text field is selected. Tap a text field first."
        private const val TEXT_CHECK_READS = 4
        private const val TEXT_CHECK_INTERVAL_MS = 150L

        /** The home app that runs on the hidden display; never our own package, even in home mode. */
        internal fun launcherPackage(context: Context): String? = HomeMode.pickHome(
            HomeMode.homeCandidates(context, android.content.Intent.CATEGORY_HOME), context.packageName
        )?.packageName
    }

    private val inputInjector = InputInjector(context, displayId, displaySize, backend)
    private val appLauncher = AppLauncher(context, displayId, backend)
    private val launcherPackage: String? by lazy { launcherPackage(context) }

    /**
     * Swipe direction -> (startNx, startNy, endNx, endNy).
     * Ported from Python SWIPE_DIRECTIONS.
     */
    private val swipeDirections = mapOf(
        "up" to intArrayOf(500, 700, 500, 300),
        "down" to intArrayOf(500, 300, 500, 700),
        "left" to intArrayOf(700, 500, 300, 500),
        "right" to intArrayOf(300, 500, 700, 500)
    )

    /**
     * Execute an action and return a StepResult.
     */

    suspend fun execute(action: Action, step: Int): StepResult {
        val startTime = System.currentTimeMillis()
        val completed = false  // will be overridden by caller based on LLM response

        if (displayId != 0 && (action is Action.Tap || action is Action.Swipe || action is Action.LongPress)) {
            if (isLauncherOnTop()) {
                val elapsed = System.currentTimeMillis() - startTime
                return StepResult(step, action.typeName,
                    "Cannot tap/swipe on home launcher. Use open_app to launch apps, or list_apps to search.", false,
                    "Action blocked: launcher is the foreground app on virtual display. Use open_app or list_apps instead.", elapsed, false)
            }
        }

        val (success, detail) = try {
            when (action) {
                is Action.CallTool -> {
                    val result = toolCaller?.invoke(action.server, action.tool, action.argumentsJson)
                        ?: (false to NO_INTEGRATIONS)
                    result.first to result.second.take(4000)
                }
                is Action.Tap -> {
                    val ok = inputInjector.tap(action.x, action.y)
                    ok to "Tapped at (${action.x}, ${action.y})"
                }

                is Action.LongPress -> {
                    val ok = inputInjector.longPress(action.x, action.y)
                    ok to "Long pressed at (${action.x}, ${action.y})"
                }

                is Action.OpenApp -> {
                    val result = appLauncher.launch(action.appName)
                    val ok = result.packageName != null
                    if (ok && result.packageName in blacklistedPackages) {
                        false to "Cannot open protected app: ${action.appName}"
                    } else {
                        if (ok) {
                            onOpenAppSuccess?.invoke(result.packageName!!)
                        }
                        ok to result.description
                    }
                }

                is Action.Swipe -> {
                    val coords = swipeDirections[action.direction.lowercase()]
                    if (coords != null) {
                        val ok = inputInjector.swipe(
                            coords[0], coords[1], coords[2], coords[3]
                        )
                        ok to "Swiped ${action.direction}"
                    } else {
                        false to "Unknown swipe direction: ${action.direction}"
                    }
                }

                is Action.KeyEvent -> {
                    val ok = inputInjector.keyEvent(action.key)
                    ok to "Key event: ${action.key}"
                }

                is Action.TypeText -> {
                    // Space can click a focused button. Check before either key events or clipboard paste.
                    val field = try { focusedField(displayId) } catch (_: Exception) { null }
                    if (field == null) {
                        false to NO_TEXT_FIELD
                    } else {
                        val ok = inputInjector.typeText(action.text)
                        when {
                            !ok -> false to "Typed: ${action.text}"
                            textArrived(action.text) -> true to "Typed: ${action.text}"
                            // Never retried by itself: the model decides what to do next.
                            else -> false to TypedTextCheck.MISSING_FEEDBACK
                        }
                    }
                }

                is Action.OpenLink -> when (val check = checkLink(action)) {
                    is LinkCheck.Refused -> false to check.feedback
                    is LinkCheck.Blocked -> false to "Cannot open protected app: ${check.packageName}"
                    is LinkCheck.Opens -> {
                        val intent = LinkCheck.intentFor(action.uri, action.call).setPackage(check.packageName)
                        if (appLauncher.startIntent(intent)) {
                            onOpenAppSuccess?.invoke(check.packageName)
                            true to (if (action.call) "Calling ${action.number} with ${check.label}" else "Opened ${action.uri} in ${check.label}")
                        } else false to "Could not open the link in ${check.label}"
                    }
                }

                is Action.Wait -> {
                    delay(2000)
                    true to "Waited 2s"
                }

                is Action.Finish -> {
                    true to "Task finished"
                }

                is Action.Fail -> {
                    false to "Task failed: ${action.reason}"
                }

                is Action.AskUser -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Asking user: ${action.question}"
                }

                is Action.HandoffUser -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Handing off to user: ${action.reason}"
                }

                is Action.Note -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Note: ${action.note}"
                }

                is Action.Remember -> {
                    // Intercepted by AgentEngine before reaching here
                    true to "Remembered: ${action.key}"
                }

                is Action.ListApps -> {
                    val apps = appLauncher.listApps(action.keyword.ifBlank { null })
                    val kw = if (action.keyword.isNotBlank()) " matching '${action.keyword}'" else ""
                    if (apps.isEmpty() && action.keyword.isNotBlank()) {
                        true to "No apps found matching '${action.keyword}'. Try a different keyword, or use list_apps without keyword to see all apps."
                    } else {
                        true to "Installed apps$kw (${apps.size}): ${apps.joinToString(", ")}"
                    }
                }
            }
        } catch (e: Exception) {
            if (action is Action.CallTool) false to TOOL_FAILED else false to "Error: ${e.message}"
        }

        val duration = System.currentTimeMillis() - startTime

        return StepResult(
            step = step,
            actionType = action.typeName,
            thought = action.thought,
            success = success,
            detail = detail,
            durationMs = duration,
            completed = completed
        )
    }

    /**
     * Where an open_link would go (F18.4): refused schemes, a call without the permission, no app, a protected app.
     * With several apps and no default, the first that is not protected is chosen.
     * ponytail: choosing the first candidate skips the chooser; the owner's default is used when there is one.
     */
    fun checkLink(action: Action.OpenLink): LinkCheck {
        LinkCheck.refusal(action.uri, action.call, canCall())?.let { return LinkCheck.Refused(it) }
        val intent = try {
            LinkCheck.intentFor(action.uri, action.call)
        } catch (e: IllegalArgumentException) {
            return LinkCheck.Refused(e.message?.takeIf { it == LinkCheck.CALL_THROUGH_TEL } ?: LinkCheck.NOT_A_LINK)
        } catch (e: Exception) {
            return LinkCheck.Refused(LinkCheck.NOT_A_LINK)
        }
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val candidates = pm.queryIntentActivities(intent, flags).map { it.activityInfo.packageName }.distinct()
        if (candidates.isEmpty()) return LinkCheck.Refused(LinkCheck.NO_APP)
        val preferred = pm.resolveActivity(intent, flags)?.activityInfo?.packageName?.takeIf { it in candidates }
        val target = preferred ?: candidates.firstOrNull { it !in blacklistedPackages } ?: candidates.first()
        if (target in blacklistedPackages) return LinkCheck.Blocked(target)
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
        } catch (_: Exception) { target }
        return LinkCheck.Opens(target, label)
    }

    /** Polls the focused field a few times: pasted text arrives asynchronously. */
    private suspend fun textArrived(typed: String): Boolean {
        repeat(TEXT_CHECK_READS) { attempt ->
            if (attempt > 0) delay(TEXT_CHECK_INTERVAL_MS)
            when (TypedTextCheck.verify(typed, focusedField(displayId))) {
                TypedTextCheck.Verdict.ARRIVED, TypedTextCheck.Verdict.CANNOT_CHECK -> return true
                TypedTextCheck.Verdict.MISSING -> Unit
            }
        }
        return false
    }

    private fun isLauncherOnTop(): Boolean {
        if (displayId == 0) return false
        val pkg = launcherPackage ?: return false
        val atm = try {
            Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
        } catch (_: Exception) { return false }
        val tasks = try {
            val method = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            method.invoke(atm, 10, false, false, displayId) as? List<*>
        } catch (_: Exception) { null } ?: return false
        val topTask = tasks.firstOrNull() ?: return false
        val baseComponent = try {
            topTask.javaClass.getField("baseActivity").get(topTask) as? android.content.ComponentName
        } catch (_: Exception) { null }
        return baseComponent?.packageName == pkg
    }
}
