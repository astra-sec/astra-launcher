package org.astrasec.launcher.ui

import android.media.tv.TvInputManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.astrasec.launcher.LauncherActivity
import org.astrasec.launcher.R
import org.astrasec.launcher.device.DeviceControlRepository
import org.astrasec.launcher.device.InputSource

class InputSourcesFragment : Fragment(R.layout.fragment_input_sources), DefaultFocusOwner {
    private lateinit var repository: DeviceControlRepository
    private var backButton: View? = null
    private var firstSource: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = DeviceControlRepository(requireContext().applicationContext)
        val activity = requireActivity() as LauncherActivity
        backButton = view.findViewById(R.id.backButton)
        backButton?.setOnClickListener { activity.showHome() }

        val inputContainer = view.findViewById<LinearLayout>(R.id.inputSourcesContainer)
        val sources = repository.inputSources()
        view.findViewById<View>(R.id.noInputSources).visibility =
            if (sources.isEmpty()) View.VISIBLE else View.GONE
        sources.forEach { source -> inputContainer.addView(createInputButton(source)) }
    }

    private fun createInputButton(source: InputSource): View {
        val state = when (source.state) {
            TvInputManager.INPUT_STATE_CONNECTED -> getString(R.string.input_connected)
            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> getString(R.string.input_standby)
            TvInputManager.INPUT_STATE_DISCONNECTED -> getString(R.string.input_disconnected)
            else -> getString(R.string.input_state_unknown)
        }
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, 72.dp, 1f).also { params ->
                params.marginEnd = 10.dp
                params.bottomMargin = 10.dp
            }
            background = requireContext().getDrawable(R.drawable.bg_action_button)
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            minWidth = 150.dp
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            text = "${source.label}\n$state"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(requireContext().getColor(R.color.text_primary))
            textSize = 16f
            setOnClickListener {
                if (!repository.openInput(source)) {
                    Toast.makeText(context, R.string.input_launch_failed, Toast.LENGTH_LONG).show()
                }
            }
            if (firstSource == null) firstSource = this
        }
    }

    override fun requestDefaultFocus(): Boolean {
        return firstSource?.requestDirectionalFocus() == true ||
            backButton?.requestDirectionalFocus() == true
    }

    override fun onDestroyView() {
        backButton = null
        firstSource = null
        super.onDestroyView()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
