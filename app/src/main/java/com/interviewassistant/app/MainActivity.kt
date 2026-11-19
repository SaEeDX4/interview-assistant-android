package com.interviewassistant.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.interviewassistant.app.platform.OAuthRedirectBus
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.InterviewAssistantTheme
import com.interviewassistant.app.viewmodels.MainViewModel

/**
 * Mirror target of `InterviewAssistant.App/Program.cs` entry point (TRANSFER-MAP §2, layer: Platform).
 * Single-activity host for the Compose NavHost that lands in Phase P4.
 *
 * P2 slice: OAuth redirect intents (the §8 D5 substitute for the Windows loopback listener) are routed
 * to the waiting sign-in flow through [OAuthRedirectBus], on cold start and on re-delivery alike.
 *
 * P4-C2: the activity obtains the composition root (and, through it, the main view model) from the
 * Application — the C#'s `new MainWindow()` reading the app-wide state. The view model itself is
 * constructed lazily by the composition and first touched by the Live UI in P4-G.
 */
class MainActivity : ComponentActivity() {

    /** The composition root built by [InterviewAssistantApplication.onCreate]. */
    val composition: AppComposition
        get() = (application as InterviewAssistantApplication).composition

    /** The main view model from the composition (lazy — see [AppComposition]). */
    val viewModel: MainViewModel by lazy { composition.mainViewModel }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OAuthRedirectBus.deliver(intent)
        setContent {
            InterviewAssistantTheme {
                P1SkeletonScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OAuthRedirectBus.deliver(intent)
    }
}

/** P1-only placeholder screen. Replaced by the §3.2 destination graph in Phase P4. */
@Composable
private fun P1SkeletonScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = IaTokens.Bg,
    ) {
        Text(
            text = "Interview Assistant — Android P1 skeleton",
            color = IaTokens.Text,
        )
    }
}
