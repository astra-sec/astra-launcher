package org.astrasec.launcher.ui

import android.view.View

interface DefaultFocusOwner {
    fun requestDefaultFocus(): Boolean
}

fun View.requestDirectionalFocus(): Boolean =
    if (isInTouchMode) requestFocusFromTouch() else requestFocus()
