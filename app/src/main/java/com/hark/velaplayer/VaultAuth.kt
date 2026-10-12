package com.hark.velaplayer

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hidden Vault credentials (v2.2 library side).
 *
 * - Lives in its own private prefs file ("vela_vault_auth"), separate from settings, [Store] and the hidden list,
 *   so "Reset all settings" never touches it.
 * - A PIN or pattern is only ever stored as a salted PBKDF2WithHmacSHA1 hash (API 24 has no SHA256 variant).
 * - Biometric unlock is an optional shortcut on top of a PIN/pattern, which stays the fallback.
 * - After 5 wrong attempts input is blocked for 30 s, doubling with every further 5 failures (max 15 min).
 */
object VaultAuth {
    const val NONE = "none"
    const val PIN = "pin"
    const val PATTERN = "pattern"

    private const val ITERATIONS = 20_000
    private const val FAILS_PER_LOCKOUT = 5

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("vela_vault_auth", Context.MODE_PRIVATE)

    /** NONE, PIN or PATTERN (the knowledge factor). */
    fun method(c: Context): String = sp(c).getString("method", NONE).takeIf { it == PIN || it == PATTERN } ?: NONE
    fun isConfigured(c: Context) = method(c) != NONE && sp(c).contains("hash")

    fun biometricEnabled(c: Context) = isConfigured(c) && sp(c).getBoolean("bio", false) && biometricAvailable(c)
    fun setBiometric(c: Context, on: Boolean) = sp(c).edit().putBoolean("bio", on).apply()

    /** True when the device has a usable, enrolled biometric (fingerprint, face, …). */
    fun biometricAvailable(c: Context): Boolean = try {
        BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Exception) { false }

    /** Hardware exists but nothing enrolled yet (used for an honest hint in settings). */
    fun biometricNeedsEnroll(c: Context): Boolean = try {
        BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    } catch (_: Exception) { false }

    fun describe(c: Context): String = when (method(c)) {
        PIN -> if (biometricEnabled(c)) "Fingerprint / biometric, PIN as backup" else "PIN"
        PATTERN -> if (biometricEnabled(c)) "Fingerprint / biometric, pattern as backup" else "Pattern"
        else -> if (setupDone(c)) "No password (opens without authentication)" else "Not set up"
    }

    // ---------------------------------------------------------------- v2.3 setup choice + vault settings
    // Kept in this private file (not the app settings) so "Reset all settings" never changes vault behaviour.

    /** The welcome choice was made (security set up, or "Use without password"). v2.2 users with a PIN/pattern count as done. */
    fun setupDone(c: Context): Boolean = sp(c).getBoolean("setup_done", false) || isConfigured(c)

    /** "none" | "pin" | "pattern": the stored mode (NONE also covers password-free). */
    fun mode(c: Context): String = if (isConfigured(c)) method(c) else NONE

    /** Password-free vault: the user chose it on purpose (welcome or vault settings). */
    fun isPasswordFree(c: Context) = setupDone(c) && !isConfigured(c)

    /** Finishes the welcome flow; call after a secret was saved or "Use without password" was picked. */
    fun completeSetup(c: Context) { sp(c).edit().putBoolean("setup_done", true).commit(); sync(c) }

    /** Removes the PIN/pattern and biometric (callers verify the current method first). Hidden videos are untouched. */
    fun clearSecret(c: Context) {
        sp(c).edit().remove("method").remove("salt").remove("hash").remove("iter").remove("bio").remove("fails").remove("lock_until")
            .putBoolean("setup_done", true).commit()
        sync(c)
    }

    /** Keeps [Vault]'s in-memory password-free flag in step with the stored choice. Call at start-up and after changes. */
    fun sync(c: Context) { Vault.setOpenWithoutAuth(isPasswordFree(c)) }

    /** "Hide Hidden Vault instructions from main Settings" (default off). Only changeable inside the vault. */
    fun hideInstructions(c: Context) = sp(c).getBoolean("hide_instructions", false)
    fun setHideInstructions(c: Context, on: Boolean) = sp(c).edit().putBoolean("hide_instructions", on).apply()

    fun showThumbnails(c: Context) = sp(c).getBoolean("show_thumbs", true)
    fun setShowThumbnails(c: Context, on: Boolean) = sp(c).edit().putBoolean("show_thumbs", on).apply()

    fun setLockAfterMin(c: Context, min: Int) = sp(c).edit().putString("lock_after", min.toString()).apply()

    // ---------------------------------------------------------------- secret

    /** Saves a new PIN/pattern (replaces any old one). Resets the failure counter. */
    fun setSecret(c: Context, method: String, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(secret, salt, ITERATIONS)
        sp(c).edit()
            .putString("method", method)
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt("iter", ITERATIONS)
            .remove("fails").remove("lock_until")
            .putBoolean("setup_done", true)
            .commit()
        sync(c)
    }

    sealed class Result {
        object Ok : Result()
        data class Wrong(val attemptsLeft: Int) : Result()
        data class LockedOut(val remainingMs: Long) : Result()
    }

    /** Checks [secret] against the stored hash, counting failures. Never throws. */
    fun verify(c: Context, secret: String): Result {
        val wait = lockoutRemaining(c)
        if (wait > 0) return Result.LockedOut(wait)
        val p = sp(c)
        val ok = try {
            val salt = Base64.decode(p.getString("salt", "") ?: "", Base64.NO_WRAP)
            val want = Base64.decode(p.getString("hash", "") ?: "", Base64.NO_WRAP)
            salt.isNotEmpty() && want.isNotEmpty() && MessageDigest.isEqual(derive(secret, salt, p.getInt("iter", ITERATIONS)), want)
        } catch (_: Exception) { false }
        if (ok) { p.edit().remove("fails").remove("lock_until").apply(); return Result.Ok }
        val fails = p.getInt("fails", 0) + 1
        val e = p.edit().putInt("fails", fails)
        if (fails % FAILS_PER_LOCKOUT == 0) {
            val round = fails / FAILS_PER_LOCKOUT - 1
            val ms = (30_000L shl round.coerceAtMost(5)).coerceAtMost(15 * 60_000L)
            e.putLong("lock_until", System.currentTimeMillis() + ms)
            e.apply()
            return Result.LockedOut(ms)
        }
        e.apply()
        return Result.Wrong(FAILS_PER_LOCKOUT - fails % FAILS_PER_LOCKOUT)
    }

    /** A biometric success counts as a correct unlock (clears the counter). */
    fun onBiometricSuccess(c: Context) = sp(c).edit().remove("fails").remove("lock_until").apply()

    fun lockoutRemaining(c: Context): Long {
        val until = sp(c).getLong("lock_until", 0L)
        val now = System.currentTimeMillis()
        // A clock moved far back must not block forever: never wait longer than the max lockout.
        return if (until > now) (until - now).coerceAtMost(15 * 60_000L) else 0L
    }

    private fun derive(secret: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iter, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    // ---------------------------------------------------------------- session / inactivity

    /** "Lock after inactivity" in minutes; 0 = immediately (whenever the vault screen is left, even for the player). */
    fun lockAfterMin(c: Context): Int = (sp(c).getString("lock_after", null) ?: Prefs.sp(c).getString("vault_lock_after", "5"))
        ?.toIntOrNull()?.coerceAtLeast(0) ?: 5

    @Volatile private var lastActive = 0L
    fun touch() { lastActive = SystemClock.elapsedRealtime() }

    /** True when an already unlocked vault may be shown again without asking (within the inactivity period). */
    fun sessionValid(c: Context): Boolean {
        if (isPasswordFree(c)) return true
        if (!Vault.isUnlocked()) return false
        val min = lockAfterMin(c)
        if (min <= 0) return false
        return SystemClock.elapsedRealtime() - lastActive < min * 60_000L
    }

    fun unlockNow() { Vault.markUnlocked(); touch() }
}
