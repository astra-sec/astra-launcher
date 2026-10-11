package org.astrasec.launcher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.astrasec.launcher.ui.AppDrawerFragment
import org.astrasec.launcher.ui.DefaultFocusOwner
import org.astrasec.launcher.ui.HomeFragment
import org.astrasec.launcher.ui.InputSourcesFragment
import org.astrasec.launcher.ui.SettingsFragment
import org.astrasec.launcher.activitytools.ActivityToolsFragment
import org.astrasec.launcher.shelltools.ShellToolsFragment
import org.astrasec.launcher.apkupload.ApkUploadFragment

class LauncherActivity : AppCompatActivity() {
    private var directionalNavigationActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        if (savedInstanceState == null) {
            showHome()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
    }

    fun showHome() {
        replaceRoot(HomeFragment(), TAG_HOME)
    }

    fun showAllApps() {
        replaceRoot(AppDrawerFragment(), TAG_APP_DRAWER)
    }

    fun showSettings() {
        replaceRoot(SettingsFragment(), TAG_SETTINGS)
    }

    fun showInputSources() {
        replaceRoot(InputSourcesFragment(), TAG_INPUT_SOURCES)
    }

    fun showActivityTools() = replaceRoot(ActivityToolsFragment(), "activity_tools")

    fun showShellTools() = replaceRoot(ShellToolsFragment(), "shell_tools")

    fun showApkUpload() = replaceRoot(ApkUploadFragment(), "apk_upload")

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (supportFragmentManager.findFragmentById(R.id.fragmentContainer)) {
            is HomeFragment -> Unit
            is ActivityToolsFragment, is ShellToolsFragment, is ApkUploadFragment -> showSettings()
            else -> showHome()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode in DIRECTIONAL_KEYS &&
            !directionalNavigationActive
        ) {
            directionalNavigationActive = true
            val focusOwner = supportFragmentManager
                .findFragmentById(R.id.fragmentContainer) as? DefaultFocusOwner
            val focusApplied = focusOwner?.requestDefaultFocus() == true
            if (focusApplied) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            directionalNavigationActive = false
            currentFocus?.clearFocus()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun replaceRoot(fragment: Fragment, tag: String) {
        if (supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return
        directionalNavigationActive = false
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_APP_DRAWER = "app_drawer"
        const val TAG_SETTINGS = "settings"
        const val TAG_INPUT_SOURCES = "input_sources"

        val DIRECTIONAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        )
    }
}
