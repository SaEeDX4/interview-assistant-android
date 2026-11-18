package com.interviewassistant.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.interviewassistant.client.AuthException
import com.interviewassistant.client.OAuthRedirectReceiver
import com.interviewassistant.client.Pkce
import com.interviewassistant.client.ProtectedFileTokenStore
import com.interviewassistant.client.RedirectOutcome
import com.interviewassistant.client.RedirectUriParser
import com.interviewassistant.core.languages.Loc
import com.interviewassistant.core.persistence.IDataProtector
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Android platform implementations behind the Windows behaviour specs (TRANSFER-MAP §6.1, §8).
 * P2 pieces: the OAuth redirect surface (D5), the Keystore secret protection behind the mirrored
 * `IDataProtector` (D3) and the token store built on them, the browser launcher for the interactive
 * sign-in flow, and the `Loc` asset-table installer. Audio/update platform pieces land in P5/P6.
 */

// ---------------------------------------------------------------------------
// D3 — secret protection: Android Keystore AES-GCM behind IDataProtector.
// Windows uses DPAPI CurrentUser with app entropies; the Android equivalent is a Keystore key that
// never leaves the device, with the ciphertext layout IV(12) ‖ ciphertext+tag.
// ---------------------------------------------------------------------------

class KeystoreDataProtector(private val keyAlias: String = DEFAULT_ALIAS) : IDataProtector {
    override fun protect(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unprotect(cipherBytes: ByteArray): ByteArray {
        require(cipherBytes.size > IV_BYTES) { "Ciphertext too short." }
        val iv = cipherBytes.copyOfRange(0, IV_BYTES)
        val data = cipherBytes.copyOfRange(IV_BYTES, cipherBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(data)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Keystore alias for the session token store (the D3 counterpart of the Windows DPAPI scope). */
        const val DEFAULT_ALIAS: String = "interview-assistant.session.v1"
    }
}

/**
 * The token store the Windows composition root builds as
 * `ProtectedFileTokenStore(%APPDATA%/session.dpapi, DpapiProtector)` — here: app-private files,
 * Keystore protection. Same class, same on-disk format, platform protector (§8 D3: no residual gap).
 */
fun sessionTokenStore(context: Context): ProtectedFileTokenStore =
    ProtectedFileTokenStore(File(context.filesDir, "session.bin").absolutePath, KeystoreDataProtector())

// ---------------------------------------------------------------------------
// D5 — OAuth redirect: App Link / custom-scheme delivery into OAuthRedirectReceiver.
// The validation itself (path, state, error, code) is the shared RedirectUriParser in :client;
// this class is only the delivery channel the platform owns.
// ---------------------------------------------------------------------------

/**
 * An [OAuthRedirectReceiver] fed by the activity's VIEW intents (see [OAuthRedirectBus]).
 * [deliver] returns what the URI meant; a code completes the wait, an error aborts it with
 * `AuthException("browser_error", …)`, anything else keeps the wait alive — the C# listener's
 * behaviour for the same inputs.
 */
class AppLinkRedirectReceiver private constructor(
    override val state: String,
    override val redirectUri: String,
) : OAuthRedirectReceiver {
    private val completion = CompletableDeferred<String>()

    @Volatile
    private var closed = false

    fun deliver(uri: String): RedirectOutcome {
        val outcome = RedirectUriParser.evaluate(uri, state)
        when (outcome) {
            is RedirectOutcome.Code -> completion.complete(outcome.code)
            is RedirectOutcome.Error -> completion.completeExceptionally(AuthException("browser_error", outcome.error))
            RedirectOutcome.NotOurs, RedirectOutcome.MissingCode -> Unit // keep waiting, as the C# listener does
        }
        return outcome
    }

    override suspend fun waitForCode(timeout: Duration): String {
        if (closed) throw TimeoutException("Sign-in was cancelled.")
        try {
            return withTimeout(timeout) { completion.await() }
        } catch (e: TimeoutCancellationException) {
            throw TimeoutException("Sign-in was not completed in the browser.")
        } catch (e: ReceiverClosedException) {
            throw TimeoutException("Sign-in was cancelled.")
        }
    }

    override fun close() {
        closed = true
        completion.completeExceptionally(ReceiverClosedException())
    }

    private class ReceiverClosedException : Exception()

    companion object {
        /** Creates a receiver with a fresh PKCE state, mirroring `new LoopbackRedirect(Pkce.CreateState())`. */
        fun create(redirectUriForState: (String) -> String): AppLinkRedirectReceiver {
            val state = Pkce.createState()
            return AppLinkRedirectReceiver(state, redirectUriForState(state))
        }

        /**
         * The app's redirect URI for a state, on the D5 custom-scheme fallback surface. The shape keeps the
         * `/callback` path the shared parser requires (the C#'s loopback path): scheme `interviewassistant`,
         * host `oauth`, path `/callback` — the manifest intent-filter matches exactly this.
         */
        fun defaultRedirectUri(state: String): String = "interviewassistant://oauth/callback?s=$state"
    }
}

/**
 * Routes redirect intents from the activity to the receiver the current sign-in flow registered —
 * the Android counterpart of the loopback listener's socket (the activity is the only entry point).
 */
object OAuthRedirectBus {
    @Volatile
    var active: AppLinkRedirectReceiver? = null
        private set

    fun register(receiver: AppLinkRedirectReceiver) {
        active = receiver
    }

    fun unregister(receiver: AppLinkRedirectReceiver) {
        if (active === receiver) active = null
    }

    /** Delivers an incoming VIEW intent's URI; false when no flow is waiting or the URI was not ours. */
    fun deliver(intent: Intent?): Boolean {
        val uri = intent?.dataString ?: return false
        val receiver = active ?: return false
        return receiver.deliver(uri) != RedirectOutcome.NotOurs
    }
}

/** Opens a URL in the system browser (the C#'s `openBrowser` callback target; Custom Tabs arrive with the P4 UI shell). */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

// ---------------------------------------------------------------------------
// Loc tables: the byte-identical i18n assets in :core, parsed through Loc.parseTable.
// ---------------------------------------------------------------------------

/** Installs the asset-backed [Loc] table loader (call once from `Application.onCreate`). */
fun installLocTables(context: Context) {
    val assets = context.assets
    Loc.tableLoader = { code ->
        try {
            assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { reader ->
                Loc.parseTable(reader.readText())
            }
        } catch (e: Exception) {
            null // no table for this code: Loc falls back to English, as the C# does for a missing resource
        }
    }
}
