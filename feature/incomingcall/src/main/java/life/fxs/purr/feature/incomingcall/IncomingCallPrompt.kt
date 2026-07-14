package life.fxs.purr.feature.incomingcall

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import life.fxs.purr.core.designsystem.component.VoiceCallScaffold

@Composable
@SuppressLint("ModifierParameter")
fun IncomingCallPromptRoute(
    partnerName: String,
    partnerAvatarUrl: String?,
    avatarModifier: Modifier = Modifier,
    onOpenCall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    IncomingCallPromptRoute(
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        avatarModifier = avatarModifier,
        onOpenCall = onOpenCall,
        onDismiss = onDismiss,
        viewModel = hiltViewModel(),
    )
}

@Composable
@SuppressLint("ModifierParameter")
internal fun IncomingCallPromptRoute(
    partnerName: String,
    partnerAvatarUrl: String?,
    avatarModifier: Modifier,
    onOpenCall: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: IncomingCallPromptViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is IncomingCallPromptEffect.NavigateToCall -> onOpenCall(effect.pairId)
                is IncomingCallPromptEffect.ShowError -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(state.isReady, state.call) {
        if (state.isReady && state.call == null && !state.isResponding) onDismiss()
    }

    IncomingCallPrompt(
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        avatarModifier = avatarModifier,
        enabled = state.call != null && !state.isResponding,
        onAccept = { viewModel.onIntent(IncomingCallPromptIntent.Accept) },
        onDecline = { viewModel.onIntent(IncomingCallPromptIntent.Decline) },
    )
}

@Composable
@SuppressLint("ModifierParameter")
internal fun IncomingCallPrompt(
    partnerName: String,
    partnerAvatarUrl: String?,
    avatarModifier: Modifier,
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    VoiceCallScaffold(
        partnerName = partnerName,
        partnerAvatarUrl = partnerAvatarUrl,
        status = "语音来电",
        detail = "Purr 语音",
        avatarModifier = avatarModifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            IncomingCallAction(
                label = "拒绝",
                color = Color(0xFFE84B4B),
                enabled = enabled,
                onClick = onDecline,
            ) {
                Icon(Icons.Rounded.CallEnd, contentDescription = null, modifier = Modifier.size(34.dp))
            }
            IncomingCallAction(
                label = "接听",
                color = Color(0xFF35C86F),
                enabled = enabled,
                onClick = onAccept,
            ) {
                Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(34.dp))
            }
        }
    }
}

@Composable
private fun IncomingCallAction(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = color,
                contentColor = Color.White,
                disabledContainerColor = color.copy(alpha = 0.45f),
                disabledContentColor = Color.White.copy(alpha = 0.55f),
            ),
        ) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}
