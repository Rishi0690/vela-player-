package com.hark.velaplayer

import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity

/** Biometric prompt helper shared by the vault screens (androidx.biometric, BIOMETRIC_WEAK, no crypto object). */
object VaultBio {
    fun prompt(a: FragmentActivity, title: String, negative: String, onOk: () -> Unit, onFail: (String?) -> Unit = {}) {
        if (!VaultAuth.biometricAvailable(a)) { onFail("Fingerprint isn't available right now"); return }
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { VaultAuth.onBiometricSuccess(a); onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val user = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED
                onFail(if (user) null else errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setConfirmationRequired(false)
            .build()
        try { p.authenticate(info) } catch (e: Exception) { onFail("Fingerprint isn't available right now") }
    }
}

/**
 * v2.3 shared PIN / pattern flows for the welcome screen and Vault settings. Each step is a [VaultLockPanel]
 * handed to [show]; the caller decides where it goes (the vault's lock area or the settings page).
 */
object VaultSecretFlow {
    private fun word(method: String) = if (method == VaultAuth.PIN) "PIN" else "pattern"

    /** Asks for a new PIN/pattern twice. [onDone] gets the confirmed secret (nothing is saved here). */
    fun enterNew(a: FragmentActivity, method: String, show: (View) -> Unit, onCancel: () -> Unit, onDone: (String) -> Unit, first: String? = null, note: String? = null) {
        val w = word(method)
        val panel = VaultLockPanel(a)
        panel.setTexts(if (first == null) "New $w" else "Confirm $w",
            if (first == null) (if (method == VaultAuth.PIN) "Enter 4 to 12 digits, then tap ✓" else "Connect at least 4 dots") + (note?.let { "\n\n$it" } ?: "")
            else "Enter the same $w again")
        panel.secondary.isVisible = true
        panel.secondary.text = "Cancel"
        panel.secondary.setOnClickListener { onCancel() }
        panel.showInput(method) { s ->
            if (method == VaultAuth.PATTERN && s.split('-').size < 4) { panel.wrong("Connect at least 4 dots"); return@showInput }
            if (first == null) enterNew(a, method, show, onCancel, onDone, s, note)
            else if (s != first) {
                android.widget.Toast.makeText(a, "Didn't match. Try again.", android.widget.Toast.LENGTH_SHORT).show()
                enterNew(a, method, show, onCancel, onDone, null, note)
            } else onDone(s)
        }
        show(panel)
    }

    /**
     * Confirms the current PIN/pattern (or fingerprint when enabled) before a security change. Uses the same
     * failure counter and lockout as the vault's lock screen. Runs [onOk] straight away when nothing is set up.
     */
    fun verify(a: FragmentActivity, reason: String, show: (View) -> Unit, onCancel: () -> Unit, onOk: () -> Unit) {
        if (!VaultAuth.isConfigured(a)) { onOk(); return }
        val method = VaultAuth.method(a); val w = word(method)
        val panel = VaultLockPanel(a)
        panel.setTexts("Confirm it's you", "Enter your current $w $reason.")
        panel.showInput(method) { s ->
            when (val r = VaultAuth.verify(a, s)) {
                VaultAuth.Result.Ok -> onOk()
                is VaultAuth.Result.Wrong -> panel.wrong("Wrong $w · ${r.attemptsLeft} attempt${if (r.attemptsLeft == 1) "" else "s"} left")
                is VaultAuth.Result.LockedOut -> panel.lockFor(r.remainingMs)
            }
        }
        if (VaultAuth.biometricEnabled(a)) {
            panel.bioButton.isVisible = true
            panel.bioButton.setOnClickListener {
                VaultBio.prompt(a, "Confirm it's you", "Use $w", onOk = onOk, onFail = { it?.let { m -> panel.setError(m) } })
            }
        }
        panel.secondary.isVisible = true
        panel.secondary.text = "Cancel"
        panel.secondary.setOnClickListener { onCancel() }
        VaultAuth.lockoutRemaining(a).takeIf { it > 0 }?.let { panel.lockFor(it) }
        show(panel)
    }
}
