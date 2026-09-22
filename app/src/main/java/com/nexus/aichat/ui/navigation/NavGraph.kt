package com.nexus.aichat.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexus.aichat.ui.chat.ChatScreen
import com.nexus.aichat.ui.conversations.ConversationsScreen
import com.nexus.aichat.ui.providers.ProviderSetupScreen
import com.nexus.aichat.ui.providers.ProvidersScreen
import com.nexus.aichat.ui.settings.AppearanceScreen
import com.nexus.aichat.ui.settings.BehaviorScreen
import com.nexus.aichat.ui.settings.RulesScreen
import com.nexus.aichat.ui.settings.SettingsScreen
import com.nexus.aichat.ui.settings.ToolsScreen

/**
 * The app's navigation graph.
 *
 * Shape decisions worth stating, because they are what make the app feel like Gemini/Claude rather than
 * a settings-heavy Android app:
 *
 *  - **Chat is the start destination**, not a list. A returning user lands where they left off; the
 *    conversation drawer is a slide-over, not a full screen you have to come back from.
 *  - **`chat/{conversationId}` is the single chat route.** The id is in the path, so a deep link, a
 *    process-death restore and a fresh tap all resolve identically.
 *  - **Settings is a hub with sub-routes**, each independently deep-linkable - appearance, behaviour,
 *    tools, personas, providers.
 *  - Transitions are lateral slides with a short fade. Vertical navigation means "up the hierarchy",
 *    which is not what these screens are: they are siblings you move between.
 */
object Routes {
    const val CHAT = "chat"
    const val CHAT_ARG_ID = "conversationId"
    const val CHAT_PATH = "$CHAT/{$CHAT_ARG_ID}"

    const val CONVERSATIONS = "conversations"
    const val NEW_CHAT = "new"

    const val PROVIDERS = "providers"
    const val PROVIDER_SETUP = "providers/setup"
    const val PROVIDER_SETUP_ARG_ID = "providerId"
    const val PROVIDER_SETUP_PATH = "$PROVIDER_SETUP/{$PROVIDER_SETUP_ARG_ID}"

    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_BEHAVIOR = "settings/behavior"
    const val SETTINGS_TOOLS = "settings/tools"
    const val SETTINGS_RULES = "settings/rules"

    fun chat(conversationId: String) = "$CHAT/$conversationId"
    fun providerSetup(providerId: String?) = providerId?.let { "$PROVIDER_SETUP/$it" } ?: "$PROVIDER_SETUP/new"
}

private const val TRANSITION_MS = 220

@Composable
fun NexusNavGraph(
    navController: NavHostController,
    startConversationId: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.chat(startConversationId),
        modifier = modifier,
        enterTransition = { slideInHorizontally(tween(TRANSITION_MS)) { it / 6 } + fadeIn(tween(TRANSITION_MS)) },
        exitTransition = { slideOutHorizontally(tween(TRANSITION_MS)) { -it / 8 } + fadeOut(tween(TRANSITION_MS / 2)) },
        popEnterTransition = { slideInHorizontally(tween(TRANSITION_MS)) { -it / 6 } + fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { slideOutHorizontally(tween(TRANSITION_MS)) { it / 8 } + fadeOut(tween(TRANSITION_MS / 2)) },
    ) {
        composable(
            route = Routes.CHAT_PATH,
            arguments = listOf(navArgument(Routes.CHAT_ARG_ID) { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                conversationId = entry.arguments?.getString(Routes.CHAT_ARG_ID).orEmpty(),
                onOpenConversations = { navController.navigate(Routes.CONVERSATIONS) },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onSwitchConversation = { id ->
                    navController.navigate(Routes.chat(id)) {
                        // Switching chats replaces the entry: back should leave the app, not walk a
                        // history of every chat the user peeked at.
                        popUpTo(Routes.CHAT_PATH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CONVERSATIONS) { entry ->
            ConversationsScreen(
                onOpenConversation = { id ->
                    navController.navigate(Routes.chat(id)) { popUpTo(Routes.CHAT_PATH) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                onAddProvider = { navController.navigate(Routes.providerSetup(null)) },
                onEditProvider = { id -> navController.navigate(Routes.providerSetup(id)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PROVIDER_SETUP_PATH,
            arguments = listOf(navArgument(Routes.PROVIDER_SETUP_ARG_ID) { type = NavType.StringType }),
        ) { entry ->
            ProviderSetupScreen(
                providerId = entry.arguments?.getString(Routes.PROVIDER_SETUP_ARG_ID).orEmpty(),
                onFinished = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenBehavior = { navController.navigate(Routes.SETTINGS_BEHAVIOR) },
                onOpenTools = { navController.navigate(Routes.SETTINGS_TOOLS) },
                onOpenRules = { navController.navigate(Routes.SETTINGS_RULES) },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_APPEARANCE) { AppearanceScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS_BEHAVIOR) { BehaviorScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS_TOOLS) { ToolsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS_RULES) { RulesScreen(onBack = { navController.popBackStack() }) }
    }
}
