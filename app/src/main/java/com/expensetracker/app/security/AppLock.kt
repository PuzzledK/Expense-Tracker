package com.expensetracker.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-lock settings live in the same SharedPreferences as the rest ("settings"). */
object AppLock {
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("appLock", false)
    fun timeoutSeconds(ctx: Context) = prefs(ctx).getInt("lockTimeoutSec", 30)

    /** True when the phone has a PIN, pattern, password (and so possibly fingerprint/face) set. */
    fun isDeviceSecure(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

/**
 * Locks the app behind the phone's own screen lock (fingerprint/face, or PIN/pattern/password),
 * using the system prompt — no app-specific PIN.
 *
 * Create it as a property of the activity (it registers an activity-result launcher, which must
 * happen before the activity starts) and forward onStart/onStop.
 */
class AppLockController(private val activity: ComponentActivity) {

    /** Compose reads this: when true the app content is replaced by the lock screen. */
    var locked by mutableStateOf(false)
        private set

    private var backgroundedAt = 0L
    private var authInProgress = false
    private var pending: ((Boolean) -> Unit)? = null

    /** Android 8–9: the system "confirm your PIN/pattern" screen. */
    private val legacyLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        finish(r.resultCode == Activity.RESULT_OK)
    }

    /** Call from onCreate: a cold start is always locked. */
    fun onCreate() {
        locked = shouldLock()
    }

    fun onStart() {
        if (authInProgress) return
        if (backgroundedAt != 0L && shouldLock()) {
            val away = (SystemClock.elapsedRealtime() - backgroundedAt) / 1000
            if (away >= AppLock.timeoutSeconds(activity)) locked = true
        }
        backgroundedAt = 0L
        applyRecentsPrivacy()
    }

    fun onStop() {
        // The PIN screen is its own activity; leaving for it must not count as "going away".
        if (authInProgress || activity.isChangingConfigurations) return
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        if (!AppLock.isDeviceSecure(activity)) {
            // Screen lock was removed from the phone: there's nothing to check against, don't trap the user.
            locked = false
            return
        }
        authenticate("Unlock Expenses", "Use your fingerprint, face, or screen lock") { ok -> if (ok) locked = false }
    }

    /** Asks for the phone's unlock; [onResult] gets true on success. Used for unlocking and for toggling the lock. */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (authInProgress) return
        if (!AppLock.isDeviceSecure(activity)) {
            onResult(false)
            return
        }
        authInProgress = true
        pending = onResult
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                builder.build().authenticate(
                    CancellationSignal(),
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false)
                        // onAuthenticationFailed = one bad finger; the prompt stays open, so nothing to do.
                    },
                )
            }
            else -> {
                val km = activity.getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                val intent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) finish(false) else legacyLauncher.launch(intent)
            }
        }
    }

    private fun finish(ok: Boolean) {
        authInProgress = false
        backgroundedAt = 0L
        val cb = pending
        pending = null
        cb?.invoke(ok)
    }

    /** Called after the setting changes so the lock takes effect without restarting. */
    fun refresh() {
        if (!AppLock.isEnabled(activity)) locked = false
        applyRecentsPrivacy()
    }

    private fun shouldLock() = AppLock.isEnabled(activity) && AppLock.isDeviceSecure(activity)

    /** Android 13+: show a blank card in Recents instead of your expenses while the lock is on. */
    private fun applyRecentsPrivacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!AppLock.isEnabled(activity))
        }
    }
}
