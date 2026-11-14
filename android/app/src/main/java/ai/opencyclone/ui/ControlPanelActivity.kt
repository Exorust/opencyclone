package ai.opencyclone.ui

import ai.opencyclone.R
import ai.opencyclone.assist.HomeMode
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.feed.AppTile
import ai.opencyclone.feed.Feed
import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.feed.HomeCommands
import ai.opencyclone.feed.LiveState
import ai.opencyclone.feed.QuestionView
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.miniapp.MiniAppActivity
import ai.opencyclone.ui.history.HistoryPageView
import ai.opencyclone.ui.home.AppSearch
import ai.opencyclone.ui.home.BuiltAppRemoval
import ai.opencyclone.ui.home.HomeHeader
import ai.opencyclone.ui.home.HomePager
import ai.opencyclone.ui.home.HomeRules
import ai.opencyclone.ui.home.StartChecks
import ai.opencyclone.ui.home.TypeInput
import ai.opencyclone.ui.start.StartActivity
import ai.opencyclone.ui.theme.Motion
import ai.opencyclone.ui.theme.crossFadeWash
import ai.opencyclone.ui.theme.drawBehindSystemBars
import ai.opencyclone.ui.theme.fadeIn
import ai.opencyclone.ui.theme.fadeOut
import ai.opencyclone.ui.thread.ThreadAdapter
import ai.opencyclone.ui.thread.ThreadSpacing
import ai.opencyclone.ui.voice.ListeningActivity
import ai.opencyclone.ui.widgets.WidgetHost
import ai.opencyclone.widgets.WidgetFlow
import ai.opencyclone.widgets.Widgets
import ai.opencyclone.ui.widgets.WidgetsPageView
import ai.opencyclone.ui.widget.AppIcons
import ai.opencyclone.ui.widget.RunningPanelView
import ai.opencyclone.ui.widget.RunningPillView
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The home page (UI.md 5.1 to 5.8 and 5.10, with sections 9 and 10). It keeps the class name of the old
 * control panel because the launcher entry, the service, the overlay, and the shortcuts start it by name.
 *
 * It reads only [FeedLocator.feed] and calls only [FeedLocator.commands]; the one use of the service is the
 * pairing check in [StartChecks]. Its own state is "panel open" and "input open"; everything else comes from
 * the feed.
 *
 * Version 2 (F15, F16): the home page is the middle one of three pages in a [HomePager]: History at the left,
 * Widgets at the right. The app always opens on Home; [EXTRA_PAGE] asks for the history page.
 */
class ControlPanelActivity : AppCompatActivity() {

    private lateinit var pager: HomePager
    private lateinit var historyPage: HistoryPageView
    private lateinit var widgetsPage: WidgetsPageView
    private lateinit var root: ConstraintLayout
    private lateinit var insetTop: View
    private lateinit var header: HomeHeader
    private lateinit var tilesScroll: View
    private lateinit var tiles: LinearLayout
    private lateinit var pill: RunningPillView
    private lateinit var panel: RunningPanelView
    private lateinit var scrim: View
    private lateinit var thread: RecyclerView
    private lateinit var notice: TextView
    private lateinit var footer: View
    private lateinit var footerTalk: TextView
    private lateinit var footerType: TextView
    private lateinit var typeInput: TypeInput
    private lateinit var appSearch: AppSearch
    private lateinit var swipe: GestureDetector
    private lateinit var startChecks: StartChecks
    private lateinit var config: ConfigRepository
    private val adapter = ThreadAdapter()

    private var feed: Feed? = null
    private var commands: HomeCommands? = null
    private var live = LiveState()
    private var items: List<ThreadItem> = emptyList()
    private var lastQuestion: QuestionView? = null
    private var liveSeen = false
    private var stickToBottom = true
    private var bottomGap = 0
    private var restorePanel = false
    private var tickRegistered = false

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                pager.page != HomePager.HOME -> pager.show(HomePager.HOME)
                appSearch.isOpen -> appSearch.close()
                typeInput.isOpen -> closeInput()
                panel.isOpen -> closePanel()
                // Home mode: Back on the home screen does nothing.
            }
        }
    }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshClock()
    }

    private val hideNotice = Runnable { if (notice.visibility == View.VISIBLE) notice.fadeOut() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.activity_home)
        config = ConfigRepository(this)

        pager = findViewById(R.id.home_pager)
        historyPage = findViewById(R.id.page_history)
        widgetsPage = WidgetsPageView(this)
        findViewById<FrameLayout>(R.id.page_widgets_slot).addView(widgetsPage,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        widgetsPage.onShowHome = { pager.show(HomePager.HOME, animate = false) }
        root = findViewById(R.id.home_root)
        insetTop = findViewById(R.id.home_inset_top)
        header = HomeHeader(findViewById(R.id.home_header))
        tilesScroll = findViewById(R.id.home_tiles_scroll)
        tiles = findViewById(R.id.home_tiles)
        pill = findViewById(R.id.home_pill)
        panel = findViewById(R.id.home_panel)
        scrim = findViewById(R.id.home_scrim)
        thread = findViewById(R.id.home_thread)
        notice = findViewById(R.id.home_notice)
        footer = findViewById(R.id.home_footer)
        footerTalk = findViewById(R.id.footer_talk)
        footerType = findViewById(R.id.footer_type)
        typeInput = TypeInput(root, ::submit)
        appSearch = AppSearch(root) { updateBack() }
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                val dx = e2.x - start.x
                if (dy > -dp(SWIPE_MIN_DP) || kotlin.math.abs(dx) > kotlin.math.abs(dy) || velocityY > -SWIPE_MIN_VELOCITY) return false
                return onSwipeUp()
            }
        })
        onBackPressedDispatcher.addCallback(this, back)

        // F15.7: the pages do not move while the panel, the type input, or the app search is open.
        pager.canPage = { !panel.isOpen && !typeInput.isOpen && !appSearch.isOpen }
        pager.onPageChanged = { page ->
            if (page != HomePager.HISTORY) historyPage.onHidden()
            // The widgets' WebViews exist only while their page is the one in view (F16.6).
            widgetsPage.setActive(page == HomePager.WIDGETS)
            updateBack()
        }
        historyPage.onAskAgain = { entry ->
            commands?.askAgain(entry.taskId)
            stickToBottom = true
            pager.show(HomePager.HOME)
        }
        if (savedInstanceState == null) pager.show(requestedPage(intent), animate = false)
        widgetsPage.setActive(pager.page == HomePager.WIDGETS)

        thread.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        thread.adapter = adapter
        thread.addItemDecoration(ThreadSpacing(this))
        thread.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // The owner scrolled up: a new item does not pull the list down until they return to the end.
                stickToBottom = !recyclerView.canScrollVertically(1)
            }
        })
        adapter.onApprove = ::approve
        adapter.onNotNow = { taskId -> commands?.reject(taskId) }
        adapter.onOpen = ::open
        adapter.onConfirm = { item, yes ->
            if (yes && item.actionWord == WidgetFlow.DRAFT_CARD &&
                (!Widgets.isReady || Widgets.flow.page.value.draft?.file?.isFile != true)) {
                showNotice(getString(R.string.x4_draft_gone))
            } else commands?.confirm(item.id, yes)
        }
        adapter.onDismiss = { item ->
            dismissed += item.id
            bindItems(items)
        }

        header.greeting.setOnLongClickListener { openMore(); true }
        // "Not connected. Tap to reconnect." opens the pairing screen.
        header.status.setOnClickListener {
            if (live.status == ai.opencyclone.feed.StatusSentence.NOT_CONNECTED) {
                startActivity(Intent(this, ai.opencyclone.backend.SetupActivity::class.java))
            }
        }
        findViewById<View>(R.id.home_menu).setOnClickListener { openMore() }
        pill.onClick = ::openPanel
        scrim.setOnClickListener { closePanel() }
        panel.onStop = { taskId -> commands?.stop(taskId) }
        panel.onOpenLive = { taskId -> commands?.openLive(this, taskId) }
        footerTalk.setOnClickListener { startActivity(ListeningActivity.listenIntent(this)) }
        footerType.setOnClickListener { openInput() }

        applyInsets()
        root.crossFadeWash(HomeRules.wash(live, hour(), clock()), 0)
        refreshClock()
        header.setStatus(live.status, false)
        header.status.isClickable = live.status == ai.opencyclone.feed.StatusSentence.NOT_CONNECTED
        header.status.minimumHeight = if (header.status.isClickable) resources.getDimensionPixelSize(R.dimen.button_height) else 0
        bindFooter()

        restorePanel = savedInstanceState?.getBoolean(STATE_PANEL_OPEN) == true ||
            (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_PANEL, false))
        val restoreInput = savedInstanceState?.getBoolean(STATE_INPUT_OPEN) == true

        connectFeed()

        startChecks = StartChecks(this, savedInstanceState?.getBoolean(STATE_FIRST_RUN_OPENED) == true)
        startChecks.onCreate()

        if (savedInstanceState == null && !AppEntries.needsFirstRun(config) && AppEntries.wantsVoice(intent)) {
            startActivity(ListeningActivity.listenIntent(this))
        }
        if (restoreInput || (savedInstanceState == null && wantsTypeInput(intent))) root.post { openInput() }
    }

    override fun onStart() {
        super.onStart()
        if (!tickRegistered) {
            ContextCompat.registerReceiver(this, tick, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            tickRegistered = true
        }
        refreshClock()
        connectFeed()
    }

    override fun onStop() {
        if (tickRegistered) {
            unregisterReceiver(tick)
            tickRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        ai.opencyclone.HomeForeground.resumed = true
        bindFooter()
        updateBack()
        startChecks.onResume()
    }

    override fun onPause() {
        ai.opencyclone.HomeForeground.resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        notice.removeCallbacks(hideNotice)
        header.cancel()
        startChecks.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PANEL_OPEN, panel.isOpen)
        outState.putBoolean(STATE_INPUT_OPEN, typeInput.isOpen)
        outState.putBoolean(STATE_FIRST_RUN_OPENED, startChecks.firstRunOpened)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The app always opens on Home, unless the intent asks for the history or the widgets page.
        pager.show(requestedPage(intent), animate = false)
        widgetsPage.setActive(pager.page == HomePager.WIDGETS)
        // The working notification opens the running panel.
        if (intent.getBooleanExtra(EXTRA_OPEN_PANEL, false)) {
            if (pill.visibility == View.VISIBLE) openPanel() else restorePanel = true
        }
        if (HomeMode.isHomeIntent(intent)) onHomePressed()
        if (AppEntries.wantsVoice(intent)) startActivity(ListeningActivity.listenIntent(this))
        if (wantsTypeInput(intent)) root.post { openInput() }
    }

    /** [EXTRA_PAGE] ("history") or [WidgetHost.EXTRA_OPEN_PAGE] ("widgets", "home", "history"); anything else is Home. */
    private fun requestedPage(intent: Intent?): Int {
        val page = intent?.getStringExtra(WidgetHost.EXTRA_OPEN_PAGE) ?: intent?.getStringExtra(EXTRA_PAGE)
        return when (page) {
            PAGE_HISTORY -> HomePager.HISTORY
            WidgetHost.PAGE_WIDGETS -> HomePager.WIDGETS
            else -> HomePager.HOME
        }
    }

    /** The start's "Type" button asks for the type input (StartActivity.EXTRA_OPEN_TYPE). */
    private fun wantsTypeInput(intent: Intent?): Boolean =
        intent?.getBooleanExtra(StartActivity.EXTRA_OPEN_TYPE, false) == true

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        startChecks.onActivityResult(requestCode)
    }

    // ── Feed ────────────────────────────────────────────────────────────

    /** Subscribes once the feed exists. Before that the page shows the idle state. */
    private fun connectFeed() {
        if (feed != null || !FeedLocator.isReady) return
        val f = FeedLocator.feed
        feed = f
        commands = FeedLocator.commands
        panel.collectPreviews(lifecycleScope, f)
        historyPage.connect(f, FeedLocator.commands, this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { f.live.collect(::bindLive) }
                launch { f.today().collect(::bindItems) }
            }
        }
    }

    private fun bindLive(state: LiveState) {
        val first = !liveSeen
        liveSeen = true
        live = state
        val working = HomeRules.isWorking(state)
        header.setStatus(state.status, working, state.statusEmphasis)
        header.status.isClickable = state.status == ai.opencyclone.feed.StatusSentence.NOT_CONNECTED
        header.status.minimumHeight = if (header.status.isClickable) resources.getDimensionPixelSize(R.dimen.button_height) else 0
        root.crossFadeWash(HomeRules.wash(state, hour(), clock()), if (first) 0 else Motion.WASH_MS)
        bindTiles(state)
        pill.bind(state.running, state.queued)
        panel.bind(state.running.firstOrNull(), state.queuedGoals)
        if (pill.visibility != View.VISIBLE && panel.isOpen) closePanel()
        if (restorePanel && pill.visibility == View.VISIBLE) openPanel()
        restorePanel = false
        bindFooter()
        maybeSpeak(state.question)
    }

    private fun bindItems(list: List<ThreadItem>) {
        items = list
        val shown = if (dismissed.isEmpty()) list else list.filter { it.id !in dismissed }
        val stick = stickToBottom
        adapter.submitList(shown) { if (stick) scrollToEnd() }
        bindFooter()
    }

    private fun scrollToEnd() {
        val count = adapter.itemCount
        if (count > 0) thread.scrollToPosition(count - 1)
        stickToBottom = true
    }

    private fun bindTiles(state: LiveState) {
        tilesScroll.visibility = if (state.apps.isEmpty()) View.GONE else View.VISIBLE
        tiles.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.icon_tile)
        val gap = dp(12)
        state.apps.forEachIndexed { i, app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumWidth = resources.getDimensionPixelSize(R.dimen.button_height)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.button_height)
                gravity = Gravity.CENTER_HORIZONTAL
                alpha = if (app.updating) UPDATING_ALPHA else 1f
                contentDescription = getString(R.string.home_open_app, app.name)
                isClickable = true
                setOnClickListener { startActivity(MiniAppActivity.intent(this@ControlPanelActivity, app.id)) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@ControlPanelActivity)
                        .setItems(arrayOf(getString(R.string.x4_remove))) { _, _ -> confirmRemoveApp(app) }
                        .show()
                    true
                }
            }
            tile.addView(ImageView(this).apply {
                setImageDrawable(AppIcons.letterTile(context, app.name, AppIcons.parseAccent(context, app.accent), size))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(size, size))
            tile.addView(TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_App_Small)
                text = app.name
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            tiles.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = gap
            })
        }
    }

    private fun confirmRemoveApp(app: AppTile) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.x4_remove_app_question, app.name))
            .setNegativeButton(R.string.ui_not_now, null)
            .setPositiveButton(R.string.x4_remove) { _, _ ->
                lifecycleScope.launch {
                    val removed = BuiltAppRemoval.remove(this@ControlPanelActivity, app.id)
                    showNotice(getString(if (removed) R.string.x4_app_removed else R.string.x4_app_remove_failed))
                }
            }.show()
    }

    /** Speaks a question once, when it appears while the page is in front (UI.md 5.3). */
    private fun maybeSpeak(question: QuestionView?) {
        val previous = lastQuestion
        lastQuestion = question
        if (question == null || previous != null) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!spokenQuestions.add(question.text)) return
        startActivity(ListeningActivity.speakIntent(this, question.text, HomeRules.lastOwnerWords(items)))
    }

    // ── Thread callbacks ────────────────────────────────────────────────

    private fun approve(taskId: Long) {
        val c = commands ?: return
        lifecycleScope.launch {
            if (!c.approve(taskId)) showNotice(getString(R.string.home_unlock_to_approve))
        }
    }

    private fun open(item: ThreadItem) {
        val appId = item.openAppId
        val taskId = item.taskId
        when {
            appId != null -> startActivity(MiniAppActivity.intent(this, appId))
            item.kind == ThreadKind.APPROVAL && taskId != null -> commands?.openLive(this, taskId)
            // The owner opens the notification on the main screen: no task, no approval, blocked apps too.
            item.kind == ThreadKind.NOTIFICATION ->
                if (!ai.opencyclone.proactive.NotificationIntents.open(this, item.id, item.appPackage)) {
                    showNotice(getString(R.string.x2_could_not_open))
                }
        }
    }

    // ── Panel ───────────────────────────────────────────────────────────

    private fun openPanel() {
        if (panel.isOpen) return
        if (typeInput.isOpen) closeInput()
        panel.open()
        scrim.fadeIn()
        updateBack()
    }

    private fun closePanel() {
        if (!panel.isOpen) return
        panel.close()
        scrim.fadeOut()
        updateBack()
    }

    // ── Footer and type input ───────────────────────────────────────────

    private fun bindFooter() {
        val asking = live.question != null
        footerTalk.setText(when {
            asking -> R.string.home_answer_by_voice
            holdsAssistantRole() -> R.string.ui_hold_to_talk
            else -> R.string.ui_tap_to_talk
        })
        footerType.setText(if (asking) R.string.home_type_answer else R.string.ui_type)
        val hidden = typeInput.isOpen || HomeRules.footerHidden(items)
        footer.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun openInput() {
        if (typeInput.isOpen) return
        if (commands == null) {
            showNotice(getString(R.string.home_still_starting))
            return
        }
        if (panel.isOpen) closePanel()
        typeInput.open(bottomGap)
        bindFooter()
        updateBack()
    }

    private fun closeInput() {
        typeInput.close()
        bindFooter()
        updateBack()
    }

    private fun submit(text: String) {
        bindFooter()
        updateBack()
        // ponytail: this local deletion phrase is for typed home input; voice still uses the request router.
        if (live.question == null && live.approval == null) {
            val name = BuiltAppRemoval.requestedName(text)
            if (name != null) {
                val matches = live.apps.filter { it.name.equals(name, ignoreCase = true) }
                if (matches.size == 1) confirmRemoveApp(matches.single())
                else showNotice(getString(R.string.x4_app_not_found))
                return
            }
        }
        // "open <name>" with one installed app of that name starts it on the main screen, not on the hidden display.
        if (AppSearch.tryOpen(this, text)) return
        val c = commands
        if (c == null) {
            showNotice(getString(R.string.home_still_starting))
            return
        }
        // F1.4: without a model the commands write a sentence and a "Set up" row (no dialog), as for voice.
        stickToBottom = true
        c.submit(text)
    }

    private fun updateBack() {
        back.isEnabled = pager.page != HomePager.HOME || typeInput.isOpen || panel.isOpen || appSearch.isOpen || isHome()
    }

    // ── Home mode ───────────────────────────────────────────────────────

    /** Started as the home screen, or home mode is on (the alias is enabled and the system chose this app). */
    private fun isHome(): Boolean = HomeMode.isHomeIntent(intent) || HomeMode.isOn(this)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized && isHome() && pager.page == HomePager.HOME) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** A swipe up opens the app search, only in home mode and only when the thread is at its end. */
    @VisibleForTesting internal fun onSwipeUp(): Boolean {
        if (!isHome() || pager.page != HomePager.HOME || appSearch.isOpen || typeInput.isOpen || panel.isOpen || thread.canScrollVertically(1)) return false
        appSearch.open()
        updateBack()
        return true
    }

    /** A new HOME intent (the Home key while the page is in front) closes everything and shows the newest item. */
    private fun onHomePressed() {
        pager.show(HomePager.HOME, animate = false)
        appSearch.close()
        if (typeInput.isOpen) closeInput()
        closePanel()
        scrollToEnd()
        updateBack()
    }

    /** A short-lived line above the footer. */
    private fun showNotice(text: String) {
        notice.removeCallbacks(hideNotice)
        notice.text = text
        notice.fadeIn()
        notice.postDelayed(hideNotice, NOTICE_MS)
    }

    // ── Secondary screens ───────────────────────────────────────────────

    /** Section 9 item 5: the long press on the greeting opens the plain list of secondary screens. */
    private fun openMore() {
        val intent = Intent().setComponent(ComponentName(packageName, MORE_ACTIVITY))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The list screen is not in this build.
        }
    }

    // ── Time, insets, roles ─────────────────────────────────────────────

    private fun refreshClock() {
        val now = clock()
        header.setClock(now)
        root.crossFadeWash(HomeRules.wash(live, hour(), now))
    }

    private fun hour(): Int = Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.HOUR_OF_DAY)

    /**
     * The page draws behind the bars. The header starts below the status bar; the footer's text baseline sits
     * about 40 dp above the bottom edge (a 48 dp touch target 20 dp up) or above the navigation bar, and the
     * type input follows the keyboard.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, 0)
            insetTop.updateLayoutParams<ViewGroup.LayoutParams> { height = bars.top }
            val footerGap = maxOf(dp(FOOTER_GAP_DP), bars.bottom)
            bottomGap = if (ime.bottom > 0) ime.bottom + dp(8) else footerGap
            footer.updateLayoutParams<ConstraintLayout.LayoutParams> { bottomMargin = footerGap }
            typeInput.setBottomMargin(bottomGap)
            insets
        }
    }

    private fun holdsAssistantRole(): Boolean = runCatching {
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @VisibleForTesting internal fun threadAdapter(): ThreadAdapter = adapter
    @VisibleForTesting internal fun statusChanges(): Int = header.statusChanges
    @VisibleForTesting internal fun isInputOpen(): Boolean = typeInput.isOpen
    @VisibleForTesting internal fun typeField() = typeInput.field
    @VisibleForTesting internal fun appSearch(): AppSearch = appSearch
    @VisibleForTesting internal fun isBackHandled(): Boolean = back.isEnabled
    @VisibleForTesting internal fun pager(): HomePager = pager
    @VisibleForTesting internal fun historyPage(): HistoryPageView = historyPage
    @VisibleForTesting internal fun widgetsPage(): WidgetsPageView = widgetsPage

    companion object {
        /** The list of secondary screens (lane L6/H); started by name so this page builds without it. */
        const val MORE_ACTIVITY = "ai.opencyclone.ui.screens.MoreActivity"
        /** Which page to show: [PAGE_HISTORY]; anything else shows Home. */
        const val EXTRA_PAGE = "home_page"
        const val PAGE_HISTORY = "history"
        /** The working notification: open the running panel over Home. */
        const val EXTRA_OPEN_PANEL = "open_running_panel"

        /** The home page with the history page in front ("History" in the list of screens, "open history"). */
        fun historyIntent(context: Context): Intent =
            Intent(context, ControlPanelActivity::class.java).putExtra(EXTRA_PAGE, PAGE_HISTORY)

        /** The home page with Home in front. */
        fun homeIntent(context: Context): Intent = Intent(context, ControlPanelActivity::class.java)
        const val NOTICE_MS = 3000L
        const val UPDATING_ALPHA = 0.6f
        private const val FOOTER_GAP_DP = 20
        private const val SWIPE_MIN_DP = 80
        private const val SWIPE_MIN_VELOCITY = 800f
        private const val STATE_PANEL_OPEN = "home_panel_open"
        private const val STATE_INPUT_OPEN = "home_input_open"
        private const val STATE_FIRST_RUN_OPENED = "first_run_opened"

        /** Clock of the header, greeting, and wash (tests replace it). */
        @VisibleForTesting internal var clock: () -> Long = System::currentTimeMillis

        /** Questions already spoken in this process, by text. */
        internal val spokenQuestions = HashSet<String>()

        /** Thread items the owner dismissed in this process. */
        internal val dismissed = HashSet<Long>()
    }
}
