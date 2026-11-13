package ai.opencyclon.ui.home

import ai.opencyclon.R
import ai.opencyclon.tasks.OwnScreen
import ai.opencyclon.ui.theme.fadeIn
import ai.opencyclon.ui.widget.AppIcons
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One launchable activity of one profile of the owner. */
data class LaunchableApp(val label: String, val packageName: String, val component: ComponentName, val user: UserHandle)

/**
 * The app search of home mode (UI.md section 9 item 6; the design has no app grid): a sheet over the page with
 * one text field in a `surface` pill and a plain list of installed apps, filtered as the owner types. A tap
 * starts the app on the main screen and closes the sheet. It is in the view tree only while open.
 *
 * [tryOpen] is the "open <name>" rule of the type input, used in every mode.
 */
class AppSearch(private val root: ConstraintLayout, private val onClosed: () -> Unit = {}) {

    private var view: View? = null
    private var apps: List<LaunchableApp> = emptyList()
    private val adapter = Adapter()

    val isOpen: Boolean get() = view != null

    /** The field while open (tests). */
    val field: EditText? get() = view?.findViewById(R.id.app_search_field)

    /** Rows shown now (tests). */
    @VisibleForTesting internal val shown: List<LaunchableApp> get() = adapter.items

    fun open() {
        if (view != null) return
        val context = root.context
        apps = loader(context)
        val v = LayoutInflater.from(context).inflate(R.layout.view_app_search, root, false)
        v.layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        ViewCompat.setOnApplyWindowInsetsListener(v) { sheet, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sheet.setPadding(sheet.paddingLeft, bars.top, sheet.paddingRight, maxOf(bars.bottom, ime.bottom))
            insets
        }
        val list = v.findViewById<RecyclerView>(R.id.app_search_list)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        val edit = v.findViewById<EditText>(R.id.app_search_field)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = show(s?.toString().orEmpty())
        })
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { adapter.items.firstOrNull()?.let(::launch); true } else false
        }
        root.addView(v)
        view = v
        show("")
        ViewCompat.requestApplyInsets(v)
        v.fadeIn()
        edit.requestFocus()
        edit.post { imm()?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun close() {
        val v = view ?: return
        view = null
        imm()?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
        root.removeView(v)
        onClosed()
    }

    private fun show(query: String) {
        val v = view ?: return
        adapter.submit(filter(apps, query))
        v.findViewById<View>(R.id.app_search_empty).visibility =
            if (adapter.items.isEmpty() && query.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun launch(app: LaunchableApp) {
        starter(root.context, app)
        close()
    }

    private fun imm(): InputMethodManager? =
        root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<LaunchableApp> = emptyList()
            private set

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<LaunchableApp>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.name.text = app.label
            AppIcons.into(holder.icon, app.packageName, app.label)
            holder.itemView.contentDescription = holder.itemView.context.getString(R.string.home_open_app, app.label)
            holder.itemView.setOnClickListener { launch(app) }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.app_row_icon)
        val name: TextView = v.findViewById(R.id.app_row_name)
    }

    companion object {
        private const val TAG = "AppSearch"

        /** Names the request router keeps for our own screens (plan decision 9): never a direct app start. */
        val RESERVED: Set<String> = OwnScreen.phrases

        private val OPEN = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE)

        /** Installed apps (tests replace it). */
        @VisibleForTesting internal var loader: (Context) -> List<LaunchableApp> = ::installedApps

        /** Starts an app on the main screen (tests replace it). */
        @VisibleForTesting internal var starter: (Context, LaunchableApp) -> Unit = ::startApp

        /**
         * Apps whose label contains [query] (case-insensitive), the ones that start with it first, each group in
         * label order. An empty query lists every app.
         */
        fun filter(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
            val q = query.trim().lowercase()
            val sorted = apps.sortedBy { it.label.lowercase() }
            if (q.isEmpty()) return sorted
            val (prefix, rest) = sorted.filter { it.label.lowercase().contains(q) }
                .partition { it.label.lowercase().startsWith(q) }
            return prefix + rest
        }

        /**
         * The app that typed text "open <name>" names: exactly one app whose label equals <name>, ignoring case.
         * Null when the text has another form, the name is one of [RESERVED], or no app or several apps match.
         */
        fun directTarget(text: String, apps: List<LaunchableApp>): LaunchableApp? {
            val name = OPEN.find(text.trim())?.groupValues?.get(1)?.trim() ?: return null
            if (name.lowercase() in RESERVED) return null
            return apps.filter { it.label.equals(name, ignoreCase = true) }.singleOrNull()
        }

        /**
         * The "open <name>" rule of the type input: when it names one installed app, start that app on the main
         * screen (the agent's own "open app" works on the hidden display, which is not what a person at the
         * launcher wants) and return true; the text is then not sent to the agent.
         */
        fun tryOpen(context: Context, text: String): Boolean {
            if (OPEN.find(text.trim()) == null) return false
            // ponytail: lists the apps on the main thread when the text starts with "open"; one IPC per profile
            val target = directTarget(text, loader(context)) ?: return false
            starter(context, target)
            return true
        }

        /** Launchable activities of every profile of the owner, without this app. */
        fun installedApps(context: Context): List<LaunchableApp> {
            val launcher = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
            val users = runCatching { context.getSystemService(UserManager::class.java)?.userProfiles }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: listOf(Process.myUserHandle())
            return users.flatMap { user ->
                runCatching { launcher.getActivityList(null, user) }.getOrElse {
                    Log.w(TAG, "Cannot list apps of $user", it)
                    emptyList()
                }.map { LaunchableApp(it.label.toString(), it.componentName.packageName, it.componentName, user) }
            }.filter { it.packageName != context.packageName }
        }

        fun startApp(context: Context, app: LaunchableApp) {
            try {
                context.getSystemService(LauncherApps::class.java)?.startMainActivity(app.component, app.user, null, null)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot start ${app.component}", e)
            }
        }
    }
}
