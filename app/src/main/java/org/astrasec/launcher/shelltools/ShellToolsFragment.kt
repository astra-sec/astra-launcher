package org.astrasec.launcher.shelltools

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.ui.DefaultFocusOwner
import org.astrasec.launcher.ui.requestDirectionalFocus
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ShellToolsFragment : Fragment(R.layout.fragment_shell_tools), DefaultFocusOwner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var input: EditText? = null
    private var runButton: View? = null
    private var stopButton: View? = null
    private var outputView: TextView? = null
    private var outputScroll: ScrollView? = null
    private var statusView: TextView? = null
    private var running: ShellCommandRunner.RunningCommand? = null
    private var output = BoundedShellOutput()
    private var generation = 0
    private var activeDialog: AlertDialog? = null
    private val outputDirty = AtomicBoolean(false)

    private val refreshOutput = object : Runnable {
        override fun run() {
            if (outputView == null) return
            if (outputDirty.getAndSet(false)) renderOutput()
            if (running != null) mainHandler.postDelayed(this, 100)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.shellBackButton).setOnClickListener {
            (requireActivity() as LauncherActivity).showSettings()
        }
        view.findViewById<TextView>(R.id.shellScope).text =
            getString(R.string.shell_scope, Process.myUid())
        input = view.findViewById<EditText>(R.id.shellCommand).apply {
            setText(preferences().getString("draft", "id"))
        }
        runButton = view.findViewById<View>(R.id.shellRun).apply {
            setOnClickListener { runCommand() }
        }
        stopButton = view.findViewById<View>(R.id.shellStop).apply {
            isEnabled = false
            setOnClickListener {
                running?.cancel()
                statusView?.setText(R.string.shell_stopping)
            }
        }
        outputView = view.findViewById(R.id.shellOutput)
        outputScroll = view.findViewById(R.id.shellOutputScroll)
        statusView = view.findViewById(R.id.shellStatus)
        view.findViewById<View>(R.id.shellExamples).setOnClickListener { showExamples() }
        view.findViewById<View>(R.id.shellHistory).setOnClickListener { showHistory() }
    }

    private fun runCommand() {
        if (running != null) return
        val command = input?.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) {
            input?.error = getString(R.string.shell_empty_command)
            return
        }
        saveHistory(command)
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input?.windowToken, 0)
        input?.clearFocus()
        output = BoundedShellOutput()
        outputView?.text = ""
        statusView?.setText(R.string.shell_running)
        runButton?.isEnabled = false
        stopButton?.isEnabled = true
        val currentGeneration = ++generation
        val currentOutput = output
        val listener = object : ShellCommandRunner.Listener {
            override fun onOutput(text: String) {
                currentOutput.append(text)
                outputDirty.set(true)
            }

            override fun onFinished(result: ShellCommandRunner.Result) {
                mainHandler.post {
                    if (generation != currentGeneration || outputView == null) return@post
                    running = null
                    mainHandler.removeCallbacks(refreshOutput)
                    renderOutput()
                    runButton?.isEnabled = true
                    stopButton?.isEnabled = false
                    statusView?.text = when {
                        result.cancelled -> getString(R.string.shell_cancelled)
                        result.error != null -> getString(R.string.shell_error, result.error)
                        else -> getString(R.string.shell_exit_code, result.exitCode ?: -1)
                    }
                }
            }
        }
        running = ShellCommandRunner(
            shell = "/system/bin/sh",
            sessionPrefix = if (File("/system/bin/setsid").canExecute()) {
                listOf("/system/bin/setsid")
            } else {
                listOf("/system/bin/toybox", "setsid")
            },
            terminateGroup = { pid -> Os.kill(-pid, OsConstants.SIGKILL) }
        ).start(command, requireContext().filesDir, listener)
        mainHandler.post(refreshOutput)
    }

    private fun renderOutput() {
        val scroll = outputScroll ?: return
        val stickToBottom = !scroll.canScrollVertically(1)
        val (text, truncated) = output.snapshot()
        outputView?.text = if (truncated) getString(R.string.shell_output_truncated) + text else text
        if (stickToBottom) scroll.post { if (outputScroll === scroll) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showExamples() {
        showCommandPicker(getString(R.string.shell_examples), listOf(
            "id", "getprop ro.build.version.release", "getprop ro.product.model", "pwd", "ls -la"
        ))
    }

    private fun showHistory() {
        val history = readHistory()
        if (history.isEmpty()) {
            activeDialog = AlertDialog.Builder(requireContext())
                .setMessage(R.string.shell_history_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        showCommandPicker(getString(R.string.shell_history), history, clearHistory = true)
    }

    private fun showCommandPicker(title: String, commands: List<String>, clearHistory: Boolean = false) {
        activeDialog?.dismiss()
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(commands.toTypedArray()) { _, which ->
                input?.setText(commands[which])
                input?.setSelection(commands[which].length)
                runButton?.requestDirectionalFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (clearHistory) builder.setNeutralButton(R.string.shell_clear_history) { _, _ ->
            preferences().edit().remove("history").apply()
        }
        activeDialog = builder.show()
    }

    private fun preferences() = requireContext().getSharedPreferences("shell_tools", Context.MODE_PRIVATE)

    private fun readHistory(): List<String> = runCatching {
        val values = JSONArray(preferences().getString("history", "[]"))
        (0 until values.length()).map { values.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveHistory(command: String) {
        val history = (listOf(command) + readHistory().filter { it != command }).take(20)
        preferences().edit().putString("history", JSONArray(history).toString()).apply()
    }

    override fun requestDefaultFocus(): Boolean =
        (if (running == null) runButton else stopButton)?.requestDirectionalFocus() == true

    override fun onDestroyView() {
        input?.text?.toString()?.let { preferences().edit().putString("draft", it).apply() }
        generation++
        running?.cancel()
        running = null
        mainHandler.removeCallbacksAndMessages(null)
        activeDialog?.dismiss()
        activeDialog = null
        input = null
        runButton = null
        stopButton = null
        outputView = null
        outputScroll = null
        statusView = null
        super.onDestroyView()
    }
}
