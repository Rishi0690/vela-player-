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
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * v2.3 Hidden Vault settings. Reachable only from inside an open vault (toolbar gear); never from main Settings.
 *
 * - Security: set / change PIN or pattern, fingerprint on top, switch method, or "No password". Every change to an
 *   existing PIN/pattern is verified first (same counter + lockout as the lock screen). Going from no password to a
 *   PIN/pattern needs no verification. Hidden videos are never touched by any of this.
 * - Lock after inactivity (only with security), hide the main-Settings instructions, appearance, data, and the
 *   honest note that hiding isn't encryption. v2.8: the "Storage & uninstalling" and "About hiding" info sections
 *   were removed from this page on request; storage modes and their warnings still show where the user chooses
 *   them (VaultStorage) and in the vault's own note.
 * Leaving the app from here locks a secured vault and closes this screen; a recreated screen whose vault is
 * locked closes at once, so it can't be used to bypass the lock.
 */
class VaultSettingsActivity : ThemedActivity() {

    private lateinit var host: ScrollView
    private lateinit var toolbar: MaterialToolbar
    /** The current method was confirmed in this visit (reset when the app is left). */
    private var verified = false
    private var inSubPanel = false
    private var changed = false
    /** Set while a switch is changed from code, so its listener doesn't run. */
    private var quiet = false

    /** A system permission screen is open (don't lock for that). */
    private var sysOpen = false
    private val storageReq = VaultStorage.Requester(this).also { it.beforeLaunch = { sysOpen = true } }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { if (inSubPanel) showMain() else done() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultAuth.applySecure(window, this)
        VaultAuth.sync(this)
        if (!Vault.isUnlocked()) { finish(); return }
        setContentView(R.layout.vault_settings)
        host = findViewById(R.id.vaultSetHost)
        toolbar = findViewById(R.id.vaultSetToolbar)
        EdgeToEdge.root(host.parent as View) // v2.20 edge to edge
        EdgeToEdge.list(host)
        toolbar.setNavigationOnClickListener { if (inSubPanel) showMain() else done() }
        onBackPressedDispatcher.addCallback(this, back)
        showMain()
    }

    override fun onStart() {
        super.onStart()
        sysOpen = false
        if (!Vault.isUnlocked()) finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing && !sysOpen) {
            verified = false
            if (VaultAuth.isConfigured(this)) { Vault.lock(); finish() }
        }
    }

    override fun onUserInteraction() { super.onUserInteraction(); if (Vault.isUnlocked()) VaultAuth.touch() }

    private fun done() { setResult(if (changed) Activity.RESULT_OK else Activity.RESULT_CANCELED); finish() }

    private fun setPanel(v: View, sub: Boolean, title: String = "Vault settings") {
        inSubPanel = sub
        toolbar.title = title
        host.removeAllViews()
        host.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.scrollTo(0, 0)
        // v2.8: page swaps (main ↔ security steps) ease in; the lock panel animates itself.
        if (v !is VaultLockPanel) Motion.enter(v, fromY = 8 * dp, duration = Motion.MEDIUM)
    }

    private fun sub(v: View) = setPanel(v, true, "Vault security")

    // ------------------------------------------------------------------ UI helpers

    private val dp get() = resources.displayMetrics.density
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (32 * dp).toInt()) }

    /** v2.12: each header opens one large glass panel; the rows/notes that follow go inside it (with hairlines). */
    private val groups = java.util.WeakHashMap<LinearLayout, LinearLayout>()
    private fun groupOf(parent: LinearLayout): LinearLayout = groups[parent] ?: parent
    private fun addRow(parent: LinearLayout, r: View) {
        val g = groupOf(parent)
        if (g !== parent && g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(Glass.stroke(this@VaultSettingsActivity)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.toInt().coerceAtLeast(1)).apply { marginStart = (20 * dp).toInt(); marginEnd = (20 * dp).toInt() })
        g.addView(r)
    }

    private fun header(parent: LinearLayout, s: String) {
        parent.addView(TextView(this).apply {
            text = s
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Vela_Section)
            setTextColor(ThemeManager.primary(this@VaultSettingsActivity))
            setPadding((28 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        })
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.elevatedPanel(this@VaultSettingsActivity)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        parent.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (16 * dp).toInt(); marginEnd = (16 * dp).toInt() })
        groups[parent] = g
    }

    private fun note(parent: LinearLayout, s: String) = groupOf(parent).addView(TextView(this).apply {
        text = s; alpha = 0.7f
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
    })

    private fun texts(title: String, summary: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = title; TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
        if (!summary.isNullOrBlank()) addView(TextView(context).apply {
            text = summary; alpha = 0.7f
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        })
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (64 * dp).toInt()
        setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
    }

    private fun row(parent: LinearLayout, title: String, summary: String?, danger: Boolean = false, onClick: () -> Unit) {
        val r = rowBase()
        val t = texts(title, summary)
        if (danger) (t.getChildAt(0) as TextView).setTextColor(0xFFE5534B.toInt())
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tv = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        r.setBackgroundResource(tv.resourceId)
        r.isClickable = true
        r.setOnClickListener { onClick() }
        addRow(parent, r)
    }

    private fun switchRow(parent: LinearLayout, title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (MaterialSwitch, Boolean) -> Unit) {
        val r = rowBase()
        r.addView(texts(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(this)
        sw.isChecked = checked; sw.isEnabled = enabled
        sw.contentDescription = title
        sw.setOnCheckedChangeListener { _, on -> if (!quiet) onChange(sw, on) }
        r.setOnClickListener { if (sw.isEnabled) sw.toggle() }
        if (!enabled) r.alpha = 0.5f
        r.addView(sw)
        addRow(parent, r)
    }

    // ------------------------------------------------------------------ main page

    private fun showMain() {
        val c = col()
        val secured = VaultAuth.isConfigured(this)
        val m = VaultAuth.method(this)

        header(c, "Security")
        note(c, "Unlock: " + VaultAuth.describe(this))
        if (secured) {
            row(c, if (m == VaultAuth.PIN) "Change PIN" else "Switch to PIN", "Your current ${word()} is needed first") { verifyThen("to change vault security") { newSecret(VaultAuth.PIN) } }
            row(c, if (m == VaultAuth.PATTERN) "Change pattern" else "Switch to pattern", "Your current ${word()} is needed first") { verifyThen("to change vault security") { newSecret(VaultAuth.PATTERN) } }
        } else {
            row(c, "Set up a PIN", "Ask for a PIN every time the vault opens. Your hidden videos stay as they are.") { newSecret(VaultAuth.PIN) }
            row(c, "Set up a pattern", "Ask for a pattern every time the vault opens. Your hidden videos stay as they are.") { newSecret(VaultAuth.PATTERN) }
        }
        when {
            VaultAuth.biometricAvailable(this) -> switchRow(c, "Unlock with fingerprint / biometric",
                if (secured) "Your ${word()} stays as the backup" else "Set up a PIN or pattern first; it's the backup when fingerprint can't be used",
                VaultAuth.biometricEnabled(this), enabled = secured) { s, on -> toggleBio(s, on) }
            VaultAuth.biometricNeedsEnroll(this) -> note(c, "Fingerprint unlock: add a fingerprint in Android's Settings › Security first. Until then, use a PIN or pattern.")
            else -> note(c, "Fingerprint unlock isn't available on this phone. Use a PIN or pattern instead.")
        }
        if (secured) row(c, "Use without password", "The vault will open without any authentication") {
            verifyThen("to remove vault security") { confirmNoPassword() }
        } else note(c, "The vault currently opens without any authentication. Anyone who holds the Vela Player name on the Home screen for 5 seconds can open it.")

        if (secured) {
            header(c, "Auto-lock")
            val labels = resources.getStringArray(R.array.vault_lock_entries)
            val values = resources.getStringArray(R.array.vault_lock_values)
            val cur = values.indexOf(VaultAuth.lockAfterMin(this).toString()).coerceAtLeast(0)
            row(c, "Lock after inactivity", labels[cur]) {
                VelaDialogBuilder(this).setTitle("Lock after inactivity")
                    .setSingleChoiceItems(labels, cur) { d, i -> VaultAuth.setLockAfterMin(this, values[i].toInt()); d.dismiss(); showMain() }
                    .setNegativeButton("Cancel", null).show()
            }
            note(c, "The vault always locks when you close it or leave the app.")
        }

        header(c, "Privacy")
        switchRow(c, "Allow screenshots",
            "Lets you take screenshots and screen recordings in the vault, vault settings and while playing vault videos. " +
                "When off, they're blocked and the recent-apps preview stays blank.",
            VaultAuth.allowScreenshots(this)) { _, on ->
            VaultAuth.setAllowScreenshots(this, on)
            VaultAuth.applySecure(window, this)
            toast(if (on) "Screenshots allowed in the vault" else "Screenshots blocked in the vault")
        }

        header(c, "(Recommended) Main Settings")
        switchRow(c, "Hide the vault from Settings",
            "Turn this on so nobody sees the word \"Vault\" in Vela's Settings. To open the vault, just hold \"Vela Player\" on the Home screen for 5 seconds.",
            VaultAuth.hideInstructions(this)) { _, on ->
            VaultAuth.setHideInstructions(this, on)
            toast(if (on) "Vault hidden from Settings" else "Vault shown in Settings again")
        }

        setPanel(c, false)
    }

    private fun word() = if (VaultAuth.method(this) == VaultAuth.PIN) "PIN" else "pattern"

    // ------------------------------------------------------------------ actions

    private fun verifyThen(reason: String, then: () -> Unit) {
        if (verified || !VaultAuth.isConfigured(this)) { then(); return }
        VaultSecretFlow.verify(this, reason, ::sub, onCancel = { showMain() }, onOk = { verified = true; then() })
    }

    private fun newSecret(method: String) {
        VaultSecretFlow.enterNew(this, method, ::sub, onCancel = { showMain() }, onDone = { s ->
            val wasFree = !VaultAuth.isConfigured(this)
            // Stay inside the vault: it's unlocked now and simply asks next time.
            VaultAuth.unlockNow()
            VaultAuth.setSecret(this, method, s)
            VaultAuth.unlockNow()
            verified = true; changed = true
            toast(if (method == VaultAuth.PIN) "PIN saved" else "Pattern saved")
            if (wasFree && VaultAuth.biometricAvailable(this)) offerBio() else showMain()
        })
    }

    private fun offerBio() {
        VelaDialogBuilder(this).setTitle("Also unlock with fingerprint?")
            .setMessage("Your ${word()} stays as the backup when fingerprint can't be used.")
            .setPositiveButton("Use fingerprint") { _, _ ->
                VaultBio.prompt(this, "Confirm fingerprint", "Cancel",
                    onOk = { VaultAuth.setBiometric(this, true); toast("Fingerprint unlock is on"); showMain() },
                    onFail = { msg -> msg?.let { toast(it) }; showMain() })
            }
            .setNegativeButton("Not now") { _, _ -> showMain() }
            .setOnCancelListener { showMain() }
            .show()
    }

    private fun toggleBio(s: MaterialSwitch, on: Boolean) {
        quiet = true; s.isChecked = !on; quiet = false // only changes after verification
        verifyThen(if (on) "to turn on fingerprint unlock" else "to turn off fingerprint unlock") {
            if (!on) { VaultAuth.setBiometric(this, false); toast("Fingerprint unlock is off"); showMain(); return@verifyThen }
            VaultBio.prompt(this, "Confirm fingerprint", "Cancel",
                onOk = { VaultAuth.setBiometric(this, true); toast("Fingerprint unlock is on"); showMain() },
                onFail = { msg -> msg?.let { toast(it) }; showMain() })
        }
    }

    private fun confirmNoPassword() {
        showMain()
        VelaDialogBuilder(this).setTitle("Use the vault without a password?")
            .setMessage("The Hidden Vault will open without any authentication, so anyone with your phone who holds the Vela Player name on the Home screen can see your hidden videos. Your hidden videos stay in the vault. You can set up security again here at any time.")
            .setPositiveButton("Remove security") { _, _ ->
                VaultAuth.clearSecret(this)
                changed = true
                toast("The vault now opens without a password")
                showMain()
            }
            .setNegativeButton("Keep security", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
