package com.ironlog.app

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Optional app lock (Settings): opening Loadout asks for the phone's fingerprint, face or screen-lock PIN/pattern/password,
 * using Android's own prompt. Coming back within a minute (e.g. from the camera or a file picker) doesn't ask again.
 */
object AppLock {
    var locked by mutableStateOf(true)
        private set
    private var leftAt = 0L
    private const val GRACE_MS = 60_000L
    private const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Null when the phone can lock the app; otherwise why not (e.g. no screen lock set up). */
    fun unavailable(a: Activity): String? = when (runCatching { a.getSystemService(BiometricManager::class.java)?.canAuthenticate(ALLOWED) }.getOrNull()) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a screen lock (PIN, pattern, password, fingerprint or face) in your phone's settings first."
        else -> "This phone can't lock apps with its screen lock."
    }

    fun onStop() { leftAt = System.currentTimeMillis() }
    fun onStart() {
        if (!Store.profile.value.appLock) { locked = false; return }
        if (leftAt == 0L || System.currentTimeMillis() - leftAt > GRACE_MS) locked = true
    }

    /** Shows the phone's prompt; [ok] runs when it succeeds. */
    fun prompt(a: Activity, title: String, ok: () -> Unit, failed: (String) -> Unit = {}) {
        runCatching {
            BiometricPrompt.Builder(a).setTitle(title).setSubtitle("Use your fingerprint, face or phone passcode")
                .setAllowedAuthenticators(ALLOWED).build()
                .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { runCatching { ok() } }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { runCatching { failed(errString?.toString() ?: "Cancelled") } }
                })
        }.onFailure { failed(it.message ?: "Couldn't show the unlock prompt") }
    }

    fun unlock(a: Activity) = prompt(a, "Unlock Loadout", { locked = false; leftAt = 0L })
    /** Turning it on: check the prompt works first, so you can't lock yourself out by accident. */
    fun enable(a: Activity, done: (String?) -> Unit) {
        unavailable(a)?.let { done(it); return }
        prompt(a, "Turn on app lock", {
            Store.setProfile(Store.profile.value.copy(appLock = true)); locked = false; done(null)
        }, { done(it) })
    }
}

/** Covers the whole app while it's locked. */
@Composable
fun LockScreen() {
    val p by Store.profile.collectAsState()
    if (!p.appLock || !AppLock.locked) return
    val a = LocalContext.current as? Activity ?: return
    LaunchedEffect(Unit) { AppLock.unlock(a) }
    androidx.activity.compose.BackHandler { a.moveTaskToBack(true) }
    Column(Modifier.fillMaxSize().background(Bg).clickable(enabled = true) {}.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text("🔒", fontSize = 48.sp)
        androidx.compose.material3.Text("Loadout is locked", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedLabel("Unlock", 16.sp, Modifier.clip(RoundedCornerShape(12.dp)).background(Blue).clickable { AppLock.unlock(a) }.padding(horizontal = 28.dp, vertical = 12.dp),
            FontWeight.Medium, bg = Blue)
    }
}
