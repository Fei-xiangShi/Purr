package life.fxs.purr.feature.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import life.fxs.purr.core.designsystem.component.PurrStatusChip

@Composable
fun HomeScreenRoute(
    onOpenCall: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToCall -> onOpenCall(effect.pairId)
                is HomeEffect.ShowError -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    HomeScreen(
        state = state,
        onStartCall = { viewModel.onIntent(HomeIntent.StartCall) },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeScreen(
    state: HomeState,
    onStartCall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Purr",
            style = MaterialTheme.typography.headlineLarge,
        )
        state.self?.let { self ->
            Text(
                text = "Signed in as ${self.displayName}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = state.partner?.displayName ?: "Waiting for paired partner",
            style = MaterialTheme.typography.titleLarge,
        )
        PurrStatusChip(
            label = if (state.isCallable) "Ready to call" else "Partner unavailable",
        )
        if (state.isLoading) {
            PurrStatusChip(label = "Refreshing pair status")
        }
        Button(
            onClick = onStartCall,
            enabled = !state.isLoading && state.isCallable,
        ) {
            Text("Start call")
        }
        Button(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}
