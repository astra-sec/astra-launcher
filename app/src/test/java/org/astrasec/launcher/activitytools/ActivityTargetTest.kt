package org.astrasec.launcher.activitytools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTargetTest {
    private val target = ActivityTarget("com.example.settings", "com.example.settings.FactoryActivity",
        "系统设置", "工程菜单", true, true, null, true)

    @Test fun relativeComponentsExpandWithoutAcceptingMalformedInput() {
        assertEquals("com.example" to "com.example.FactoryActivity", parseActivityComponent(" com.example/.FactoryActivity "))
        assertEquals("com.example" to "com.other.FactoryActivity", parseActivityComponent("com.example/com.other.FactoryActivity"))
        listOf("", "com.example", "/.Activity", "com.example/", "com.example/.Activity/extra", "com.example/.Bad Name").forEach {
            assertNull(it, parseActivityComponent(it))
        }
    }

    @Test fun searchCanCombineAppLabelsAndComponentFragments() {
        assertTrue(target.matches("系统 FACTORY"))
        assertTrue(target.matches("example 工程"))
        assertTrue(target.matches("  "))
        assertFalse(target.matches("工程 bluetooth"))
    }

    @Test fun everyLaunchRestrictionBlocksTheLaunchAction() {
        assertTrue(target.canLaunch)
        assertFalse(target.copy(exported = false).canLaunch)
        assertFalse(target.copy(enabled = false).canLaunch)
        assertFalse(target.copy(permission = "android.permission.PRIVILEGED", permissionGranted = false).canLaunch)
    }
}
