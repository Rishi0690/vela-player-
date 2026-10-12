package com.hark.velaplayer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

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
 * Vault security: verify the current method first, then choose PIN or pattern (entered twice) and, when the
 * device supports it, fingerprint/biometric on top. With [EXTRA_SETUP] and nothing configured yet it goes
 * straight to choosing and returns RESULT_OK once a PIN or pattern is saved.
 */
class VaultSecurityActivity : ThemedActivity() {
    companion object { const val EXTRA_SETUP = "vault_setup" }

    private lateinit var host: ScrollView
    private var verified = false
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.vaultSecToolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.vault_security)
        host = findViewById(R.id.vaultSecHost)
        toolbar.setNavigationOnClickListener { finish() }
        // Rotation keeps a verified state only in memory of this instance; recreating asks again (safe default).
        if (!VaultAuth.isConfigured(this)) { verified = true; showChoices() } else showVerify()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app mid-change: the vault locks, and a change starts over (verification again) next time.
        if (!isChangingConfigurations && !isFinishing) {
            Vault.lock()
            if (VaultAuth.isConfigured(this)) { verified = false; finish() }
        }
    }

    private fun setPanel(v: View) {
        host.removeAllViews()
        host.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showVerify() {
        val panel = VaultLockPanel(this)
        panel.setTexts("Confirm it's you", "Enter your current ${if (VaultAuth.method(this) == VaultAuth.PIN) "PIN" else "pattern"} to change vault security.")
        panel.showInput(VaultAuth.method(this)) { s ->
            when (val r = VaultAuth.verify(this, s)) {
                VaultAuth.Result.Ok -> { verified = true; showChoices() }
                is VaultAuth.Result.Wrong -> panel.wrong("Wrong ${if (VaultAuth.method(this) == VaultAuth.PIN) "PIN" else "pattern"} · ${r.attemptsLeft} attempt${if (r.attemptsLeft == 1) "" else "s"} left")
                is VaultAuth.Result.LockedOut -> panel.lockFor(r.remainingMs)
            }
        }
        if (VaultAuth.biometricEnabled(this)) {
            panel.bioButton.isVisible = true
            panel.bioButton.setOnClickListener {
                VaultBio.prompt(this, "Confirm it's you", "Cancel", onOk = { verified = true; showChoices() }, onFail = { it?.let { m -> panel.setError(m) } })
            }
        }
        VaultAuth.lockoutRemaining(this).takeIf { it > 0 }?.let { panel.lockFor(it) }
        setPanel(panel)
    }

    private fun showChoices() {
        if (!verified) { showVerify(); return }
        toolbar.title = "Vault security"
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt()) }
        fun text(s: String, style: Int, alpha: Float = 1f, top: Int = 12) = TextView(this).apply {
            text = s; TextViewCompat.setTextAppearance(this, style); this.alpha = alpha
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (top * dp).toInt() }
        }
        col.addView(text("Current: " + VaultAuth.describe(this), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, top = 0))
        col.addView(text("Choose how to unlock the Hidden Vault. You'll need it every time the vault is opened after it locks.",
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, 0.75f, 6))

        fun button(label: String, icon: Int, run: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; setIconResource(icon); gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()).apply { topMargin = (12 * dp).toInt() }
            setOnClickListener { run() }
        }
        val m = VaultAuth.method(this)
        col.addView(button(if (m == VaultAuth.PIN) "Change PIN" else "Use a PIN", R.drawable.ic_lock) { enterNew(VaultAuth.PIN, null) })
        col.addView(button(if (m == VaultAuth.PATTERN) "Change pattern" else "Use a pattern", R.drawable.ic_grid) { enterNew(VaultAuth.PATTERN, null) })

        // Biometric: only offered when Android reports it usable. It needs a PIN/pattern as the fallback.
        if (VaultAuth.biometricAvailable(this)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() }
            }
            val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            label.addView(text("Unlock with fingerprint / biometric", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, top = 0))
            label.addView(text(if (VaultAuth.isConfigured(this)) "Your ${if (m == VaultAuth.PIN) "PIN" else "pattern"} stays as the backup."
                else "Set up a PIN or pattern first; it's the backup when fingerprint can't be used.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 2))
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = MaterialSwitch(this)
            sw.isChecked = VaultAuth.biometricEnabled(this)
            sw.isEnabled = VaultAuth.isConfigured(this)
            sw.setOnCheckedChangeListener { s, on ->
                if (!on) { VaultAuth.setBiometric(this, false); return@setOnCheckedChangeListener }
                // Confirm it actually works before relying on it.
                VaultBio.prompt(this, "Confirm fingerprint", "Cancel",
                    onOk = { VaultAuth.setBiometric(this, true); toast("Fingerprint unlock is on") },
                    onFail = { msg -> s.isChecked = false; msg?.let { toast(it) } })
            }
            row.addView(sw)
            col.addView(row)
        } else if (VaultAuth.biometricNeedsEnroll(this)) {
            col.addView(text("To use fingerprint, first add one in Android's Settings › Security.",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 20))
        }

        col.addView(text("Hiding only removes videos from Vela. Files are not encrypted and stay visible in other apps and file managers.",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 0.7f, 28))
        col.addView(MaterialButton(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply { topMargin = (20 * dp).toInt() }
            setOnClickListener { finishOk() }
        })
        setPanel(col)
    }

    /** Enter the new PIN/pattern twice. */
    private fun enterNew(method: String, first: String?) {
        val word = if (method == VaultAuth.PIN) "PIN" else "pattern"
        val panel = VaultLockPanel(this)
        panel.setTexts(if (first == null) "New $word" else "Confirm $word",
            if (first == null) (if (method == VaultAuth.PIN) "Enter 4 to 12 digits, then tap ✓" else "Connect at least 4 dots")
            else "Enter the same $word again")
        panel.secondary.isVisible = true
        panel.secondary.text = "Cancel"
        panel.secondary.setOnClickListener { showChoices() }
        panel.showInput(method) { s ->
            if (method == VaultAuth.PATTERN && s.split('-').size < 4) { panel.wrong("Connect at least 4 dots"); return@showInput }
            if (first == null) enterNew(method, s)
            else if (s != first) { toast("Didn't match. Try again."); enterNew(method, null) }
            else {
                VaultAuth.setSecret(this, method, s)
                toast(if (method == VaultAuth.PIN) "PIN saved" else "Pattern saved")
                if (intent.getBooleanExtra(EXTRA_SETUP, false)) finishOk() else showChoices()
            }
        }
        setPanel(panel)
    }

    private fun finishOk() {
        setResult(if (VaultAuth.isConfigured(this)) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
