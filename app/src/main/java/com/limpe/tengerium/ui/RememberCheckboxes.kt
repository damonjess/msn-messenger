package com.limpe.tengerium.ui

import android.widget.CompoundButton

/**
 * Classic MSN sign-in checkbox chain:
 * "Remember Me" -> "Remember my Password" -> "Sign me in automatically".
 * Each box needs the one before it, so unticking one unticks (and disables) the ones after it.
 */
object RememberCheckboxes {
    fun bind(
        rememberMe: CompoundButton,
        rememberPassword: CompoundButton,
        autoSignIn: CompoundButton
    ) {
        fun refresh() {
            rememberPassword.isEnabled = rememberMe.isChecked
            autoSignIn.isEnabled = rememberMe.isChecked && rememberPassword.isChecked
        }
        rememberMe.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                rememberPassword.isChecked = false
                autoSignIn.isChecked = false
            }
            refresh()
        }
        rememberPassword.setOnCheckedChangeListener { _, checked ->
            if (!checked) autoSignIn.isChecked = false
            refresh()
        }
        autoSignIn.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()
    }
}
