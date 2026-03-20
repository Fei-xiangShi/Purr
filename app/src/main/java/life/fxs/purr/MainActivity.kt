package life.fxs.purr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import life.fxs.purr.core.designsystem.theme.PurrTheme
import life.fxs.purr.core.media.livekit.CallRoomStateProvider
import life.fxs.purr.navigation.PurrNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var callRoomStateProvider: CallRoomStateProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PurrTheme {
                PurrNavHost(roomStateProvider = callRoomStateProvider)
            }
        }
    }
}
