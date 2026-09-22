package com.nexus.aichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.nexus.aichat.core.theme.NexusAppTheme
import com.nexus.aichat.domain.repository.ChatRepository
import com.nexus.aichat.domain.repository.ProviderRepository
import com.nexus.aichat.data.local.datastore.NexusSettings
import com.nexus.aichat.domain.repository.SettingsRepository
import com.nexus.aichat.ui.navigation.NexusNavGraph
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single activity.
 *
 * Two jobs: resolve *which* conversation to open, and wrap everything in the app theme. Both happen before
 * the nav graph is composed, because a chat screen that has to bootstrap itself would flash an empty feed
 * and then jump - the kind of seam that makes an app feel unfinished.
 *
 * Edge-to-edge is enabled unconditionally: the feed, the glass top bar and the composer all handle their
 * own insets, which is what lets content scroll *under* the chrome.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val shell: ShellViewModel = hiltViewModel()
            val settings by shell.settings.collectAsStateWithLifecycle()
            val startState by shell.startState.collectAsStateWithLifecycle()

            NexusAppTheme(appearance = settings.appearance) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    when (val state = startState) {
                        is ShellStart.Ready -> {
                            val navController = rememberNavController()
                            NexusNavGraph(
                                navController = navController,
                                startConversationId = state.conversationId,
                            )
                        }
                        ShellStart.Loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Where the shell has got to: resolving the opening conversation. */
sealed interface ShellStart {
    data object Loading : ShellStart
    data class Ready(val conversationId: String) : ShellStart
}

/**
 * Resolves the opening conversation exactly once.
 *
 * Rules, in order:
 *  1. the most recently updated conversation, if there is one - a returning user resumes;
 *  2. otherwise a new conversation bound to the first enabled provider that already has a model;
 *  3. otherwise a new, provider-less conversation, so a first-run user sees the app and the one thing
 *     they have to do (add a provider) instead of a splash screen.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _startState = MutableStateFlow<ShellStart>(ShellStart.Loading)
    val startState: StateFlow<ShellStart> = _startState.asStateFlow()

    /**
     * Appearance as a StateFlow. The shell needs the theme *before* the nav graph exists, so it collects
     * settings here rather than handing the screen a flow it would have to collect itself.
     */
    val settings: StateFlow<NexusSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, NexusSettings())

    init {
        viewModelScope.launch {
            val existing = chatRepository.latestConversation()
            if (existing != null) {
                _startState.value = ShellStart.Ready(existing.id)
                return@launch
            }

            val provider = providerRepository.observeEnabledProviders().first().firstOrNull()
            val model = provider?.models?.firstOrNull { it.id == provider.selectedModelId }
                ?: provider?.models?.firstOrNull()
                ?: provider?.selectedModelId?.let { modelId ->
                    com.nexus.aichat.core.model.ModelInfo(
                        id = modelId,
                        providerId = provider.id,
                        source = com.nexus.aichat.core.model.ModelSource.MANUAL,
                    )
                }

            val created = chatRepository.createConversation(provider = provider, model = model)
            _startState.value = ShellStart.Ready(created.id)
        }
    }
}
