package app.fluxshare.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.fluxshare.android.ui.FluxShareScreen
import app.fluxshare.android.ui.theme.FluxShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FluxShareTheme {
                FluxShareScreen()
            }
        }
    }
}
