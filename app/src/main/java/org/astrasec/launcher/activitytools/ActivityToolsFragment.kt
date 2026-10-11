package org.astrasec.launcher.activitytools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.ui.DefaultFocusOwner
import org.astrasec.launcher.ui.requestDirectionalFocus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ActivityToolsFragment : Fragment(R.layout.fragment_activity_tools), DefaultFocusOwner {
    private lateinit var repository: ActivityToolsRepository
    private var executor: ExecutorService? = null
    private var loading: Future<*>? = null
    private var loadGeneration = 0
    private var entries = emptyList<ActivityTarget>()
    private var selectedPackage: String? = null
    private var query = ""
    private var favoritesOnly = false
    private var backButton: View? = null
    private var status: TextView? = null
    private var appsAdapter: RowsAdapter? = null
    private var activitiesAdapter: RowsAdapter? = null
    private var dialog: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ActivityToolsRepository(requireContext())
        executor = Executors.newSingleThreadExecutor()
        selectedPackage = savedInstanceState?.getString("selectedPackage")
        query = savedInstanceState?.getString("query").orEmpty()
        favoritesOnly = savedInstanceState?.getBoolean("favoritesOnly") ?: false
        backButton = view.findViewById<View>(R.id.activityToolsBack).apply {
            setOnClickListener { (requireActivity() as LauncherActivity).showSettings() }
        }
        status = view.findViewById(R.id.activityToolsStatus)
        appsAdapter = RowsAdapter()
        activitiesAdapter = RowsAdapter()
        view.findViewById<RecyclerView>(R.id.activityToolsApps).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appsAdapter
        }
        view.findViewById<RecyclerView>(R.id.activityToolsActivities).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = activitiesAdapter
        }
        view.findViewById<EditText>(R.id.activityToolsSearch).apply {
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    render()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, _, _ ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                true
            }
        }
        view.findViewById<CheckBox>(R.id.activityToolsFavorites).apply {
            isChecked = favoritesOnly
            setOnCheckedChangeListener { _, checked ->
                favoritesOnly = checked
                render()
            }
        }
        view.findViewById<View>(R.id.activityToolsManual).setOnClickListener { showManualInput() }
        view.findViewById<View>(R.id.activityToolsRefresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        val root = view ?: return
        loading?.cancel(true)
        val generation = ++loadGeneration
        status?.setText(R.string.activity_tools_loading)
        loading = executor?.submit {
            val result = runCatching { repository.loadActivities() }
            root.post {
                if (view !== root || generation != loadGeneration) return@post
                result.onSuccess {
                    entries = it
                    render()
                }.onFailure {
                    status?.text = getString(R.string.activity_tools_load_failed, it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    private fun render() {
        if (view == null) return
        val favorites = repository.favorites()
        val filtered = entries.filter { it.matches(query) && (!favoritesOnly || it.component in favorites) }
        val groups = filtered.groupBy { it.packageName }
        if (selectedPackage !in groups) selectedPackage = groups.keys.firstOrNull()
        status?.text = if (filtered.isEmpty()) getString(R.string.activity_tools_empty)
            else getString(R.string.activity_tools_count, groups.size, filtered.size)
        appsAdapter?.replace(groups.map { (packageName, targets) ->
            Row(
                key = packageName,
                text = "${if (selectedPackage == packageName) "› " else ""}${targets.first().appLabel} (${targets.size})\n$packageName",
                selected = selectedPackage == packageName,
                onClick = {
                    selectedPackage = packageName
                    render()
                }
            )
        })
        activitiesAdapter?.replace(groups[selectedPackage].orEmpty().map { target ->
            Row(
                key = target.component,
                text = "${if (target.component in favorites) "★ " else ""}${target.label}\n${target.className}\n${availability(target)}",
                onClick = { showDetails(target) }
            )
        })
    }

    private fun availability(target: ActivityTarget): String = when {
        !target.enabled -> getString(R.string.activity_tools_disabled)
        !target.exported -> getString(R.string.activity_tools_not_exported)
        !target.permissionGranted -> getString(R.string.activity_tools_permission_required, target.permission)
        else -> getString(R.string.activity_tools_available)
    }

    private fun showDetails(target: ActivityTarget) {
        dialog?.dismiss()
        val saved = target.component in repository.favorites()
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(target.label)
            .setMessage(getString(
                R.string.activity_tools_details,
                target.appLabel,
                target.component,
                target.exported.toString(),
                target.enabled.toString(),
                target.permission ?: getString(R.string.activity_tools_no_permission),
                availability(target)
            ))
            .setPositiveButton(R.string.activity_tools_launch) { _, _ ->
                repository.launch(target).onFailure {
                    Toast.makeText(context, getString(R.string.activity_tools_failed,
                        it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.activity_tools_copy) { _, _ ->
                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Activity", target.component))
                Toast.makeText(context, R.string.activity_tools_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (saved) R.string.activity_tools_favorite_remove else R.string.activity_tools_favorite_add) { _, _ ->
                repository.toggleFavorite(target)
                render()
            }
            .create().also { detail ->
                detail.setOnShowListener {
                    detail.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = target.canLaunch
                    detail.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                }
                detail.show()
            }
    }

    private fun showManualInput() {
        dialog?.dismiss()
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.activity_tools_manual_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(24.dp, 16.dp, 24.dp, 16.dp)
        }
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.activity_tools_manual)
            .setView(input)
            .setNegativeButton(R.string.activity_tools_cancel, null)
            .setPositiveButton(R.string.activity_tools_manual_lookup, null)
            .create().also { manual ->
                manual.setOnShowListener {
                    manual.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val parsed = parseActivityComponent(input.text.toString())
                        if (parsed == null) {
                            input.error = getString(R.string.activity_tools_manual_invalid)
                        } else {
                            repository.findActivity(parsed).onSuccess { target ->
                                manual.dismiss()
                                showDetails(target)
                            }.onFailure {
                                input.error = getString(R.string.activity_tools_manual_missing)
                            }
                        }
                    }
                }
                manual.show()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedPackage", selectedPackage)
        outState.putString("query", query)
        outState.putBoolean("favoritesOnly", favoritesOnly)
        super.onSaveInstanceState(outState)
    }

    override fun requestDefaultFocus(): Boolean = backButton?.requestDirectionalFocus() == true

    override fun onDestroyView() {
        ++loadGeneration
        loading?.cancel(true)
        loading = null
        executor?.shutdownNow()
        executor = null
        dialog?.dismiss()
        dialog = null
        backButton = null
        status = null
        appsAdapter = null
        activitiesAdapter = null
        super.onDestroyView()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private data class Row(val key: String, val text: String, val selected: Boolean = false, val onClick: () -> Unit)

    private class RowsAdapter : RecyclerView.Adapter<RowsAdapter.Holder>() {
        private var rows = emptyList<Row>()
        private val ids = mutableMapOf<String, Long>()
        private var nextId = 0L

        init { setHasStableIds(true) }

        fun replace(items: List<Row>) {
            rows = items
            items.forEach { ids.getOrPut(it.key) { nextId++ } }
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = ids.getValue(rows[position].key)
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (density * value).toInt()
            return Holder(TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
                minHeight = dp(72)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextColor(context.getColor(R.color.text_primary))
                textSize = 14f
                setBackgroundResource(R.drawable.bg_action_button)
                isFocusable = true
                isClickable = true
            })
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.text.text = row.text
            holder.text.isSelected = row.selected
            holder.text.setTextColor(holder.text.context.getColor(if (row.selected) R.color.accent else R.color.text_primary))
            holder.text.setOnClickListener { row.onClick() }
        }

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
