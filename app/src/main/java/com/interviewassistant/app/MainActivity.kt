package com.interviewassistant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.interviewassistant.app.ui.theme.IaTokens
import com.interviewassistant.app.ui.theme.InterviewAssistantTheme

/**
 * Mirror target of `InterviewAssistant.App/Program.cs` entry point (TRANSFER-MAP §2, layer: Platform).
 * Single-activity host for the Compose NavHost that lands in Phase P4.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InterviewAssistantTheme {
                P1SkeletonScreen()
            }
        }
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
