@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.leo.manager.ui

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.leo.manager.data.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android rendering with a local test-only API; never touches a user's server. */
internal class ParityDeviceCases(private val compose: ComposeContentTestRule) {
    private val timestamp = System.currentTimeMillis()
    private val task =
        Task(
            "task",
            "Revue hebdomadaire du projet",
            "Examiner les changements, vérifier les tests et publier le compte rendu.",
            MAIN_AGENT_ID,
            cron = "0 9 * * 1",
            nextRun = timestamp + 86400000,
        )
    private val outcome =
        TaskOutcome(
            "completed",
            "Les modifications ont été vérifiées.",
            listOf(
                "Tests de navigation et de conversation réussis.",
                "Compte rendu disponible dans les fichiers.",
            ),
            timestamp,
            "message",
        )
    private val run =
        Run(
            "run",
            taskId = "task",
            status = "succeeded",
            taskName = task.name,
            summary =
                "## Revue terminée\n\nLes changements sont prêts. La navigation est plus claire et la conversation reste au centre de l’écran.",
            snapshot = Snapshot(task = task, agent = Agent(MAIN_AGENT_ID, "Agent principal")),
            outcome = outcome,
        )
    private val chat =
        Chat(
            "chat",
            "Simplifier l’interface mobile",
            agentName = "Agent principal",
            projectName = "Leo Agent Manager",
            runId = "run",
            run = run.copy(trigger = "chat"),
            updatedAt = timestamp,
        )
    private val events =
        listOf(
            RunEvent(
                1,
                timestamp - 60000,
                "chat.user",
                "Peux-tu simplifier cette interface et vérifier les détails ?",
            ),
            RunEvent(
                2,
                timestamp,
                "item.completed",
                "",
                mapOf(
                    "item" to
                        buildJsonObject {
                            put("id", "reply")
                            put("type", "agent_message")
                            put(
                                "text",
                                "La nouvelle interface est prête.\n\n- Navigation compacte\n- Détails accessibles à la demande\n- Plus de place pour la conversation\n\nLes mots longs restent dans le message : https://example.test/" +
                                    "longue-adresse".repeat(8),
                            )
                        }
                ),
            ),
        )

    private val chats =
        listOf(
            chat,
            chat.copy(
                id = "other",
                title = "Préparer la prochaine version",
                pendingQuestions = 1,
            ),
            chat.copy(
                id = "old",
                title = "Explorer les résultats",
                updatedAt = timestamp - 172800000,
            ),
        )

    private fun stream(state: LiveState): MockResponse {
        val frame =
            "event: batch\nid: 2\ndata: ${wireJson.encodeToString(LiveBatch(events, state, true, false))}\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(frame + ": keepalive\n\n".repeat(100000))
            .throttleBody(
                frame.toByteArray().size.toLong(),
                1,
                java.util.concurrent.TimeUnit.SECONDS,
            )
    }

    private fun fixture(server: MockWebServer): LeoViewModel {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    if (
                        path.startsWith("/api/chats/") &&
                            path.endsWith("/stream") &&
                            path != "/api/chats/stream"
                    )
                        return stream(
                            LiveState(
                                chat = chats.single { it.id == path.split('/')[3] },
                                run = chat.run,
                            )
                        )
                    when (path) {
                        "/api/chats/stream" ->
                            return stream(
                                LiveState(chats = chats)
                            )
                        "/api/runs/run/stream" -> return stream(LiveState(run = run))
                    }
                    val body =
                        when (path) {
                            "/api/session" -> "{\"authenticated\":true,\"csrf\":\"fixture\"}"
                            "/api/agents" ->
                                wireJson.encodeToString(
                                    listOf(Agent(MAIN_AGENT_ID, "Agent principal"))
                                )
                            "/api/tasks" -> wireJson.encodeToString(listOf(task))
                            "/api/tasks/activity" -> wireJson.encodeToString(listOf(run))
                            "/api/projects" ->
                                wireJson.encodeToString(
                                    listOf(Project("project", "Leo Agent Manager"))
                                )
                            "/api/skills",
                            "/api/mcps" -> "[]"
                            else -> "{}"
                        }
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(body)
                }
            }
        val application = ApplicationProvider.getApplicationContext<Application>()
        runBlocking { Preferences(application).setOrigin("") }
        return LeoViewModel(application).also { vm ->
            runBlocking {
                vm.state.first { it.ready }
                vm.forget()
                vm.connect(server.url("/").toString())
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir =
            File(instrumentation.targetContext.filesDir, "parity-screenshots").apply { mkdirs() }
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { image ->
            File(dir, "$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    private fun withSoftwareKeyboard(block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = "show_ime_with_hard_keyboard"
        val original = Settings.Secure.getString(resolver, setting)
        fun restoreSetting(value: String?) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.putString(resolver, setting, value))
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
        // CI emulators expose a hardware keyboard. Exercise a real IME, and restore
        // the device preference afterward so standalone device runs leave no change.
        try {
            restoreSetting("1")
            block()
        } finally {
            restoreSetting(original)
        }
    }

    fun compactChatEvidenceAndSwitcher() = withSoftwareKeyboard {
        MockWebServer().use { server ->
            val vm = fixture(server)
            try {
                var dark by mutableStateOf(true)
                var fontScale by mutableFloatStateOf(1f)
                var imeVisible = false
                var keyboard: SoftwareKeyboardController? = null
                compose.setContent {
                    val visible = WindowInsets.isImeVisible
                    val controller = LocalSoftwareKeyboardController.current
                    SideEffect {
                        imeVisible = visible
                        keyboard = controller
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale)
                    ) {
                        LeoTheme(if (dark) "dark" else "light") { LeoApp(vm = vm) }
                    }
                }
                compose.waitUntil(30000) {
                    compose.onAllNodesWithText(chat.title).fetchSemanticsNodes().isNotEmpty()
                }
                capture("conversation-list-dark")
                compose.onNodeWithText(chat.title).performClick()
                compose.waitUntil(30000) {
                    compose.onAllNodesWithText("Tâche terminée").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Tâche terminée").performScrollTo()
                compose.onNodeWithText("Détails").performClick()
                capture("chat-evidence-dark")
                compose.onNodeWithText("Conversations").performClick()
                compose.waitUntil(15000) {
                    compose
                        .onAllNodesWithText("Préparer la prochaine version")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                capture("conversation-switcher-dark")
                compose.onNodeWithText("Rechercher une conversation").performTextInput("prochaine")
                compose.onNodeWithText("Explorer les résultats").assertDoesNotExist()
                compose.onNodeWithText("Préparer la prochaine version").performClick()
                compose.waitUntil(30000) {
                    compose.onAllNodesWithText("Rechercher une conversation").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText("Préparer la prochaine version").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Tâche terminée").fetchSemanticsNodes().isNotEmpty()
                }
                compose.runOnIdle {
                    dark = false
                    fontScale = 1.3f
                }
                capture("chat-large-text-light")
                compose
                    .onNode(hasSetTextAction())
                    .performClick()
                    .performTextInput("Un brouillon sur téléphone")
                compose.runOnIdle { checkNotNull(keyboard).show() }
                // A fresh Android 16 CI image can spend over 15 seconds initializing
                // Gboard. Still require a genuinely visible IME before checking layout.
                compose.waitUntil(30000) {
                    compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
                    imeVisible
                }
                compose.onNodeWithContentDescription("Envoyer").assertIsDisplayed()
                capture("chat-keyboard-light")
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
                    !imeVisible
                }
                compose
                    .onNode(hasSetTextAction() and hasText("Un brouillon sur téléphone"))
                    .assertIsDisplayed()
                compose.onNodeWithContentDescription("Options de la conversation").performClick()
                compose.onNodeWithText("Plein écran").performClick()
                compose.waitUntil(10000) {
                    compose.onAllNodesWithContentDescription("Quitter le plein écran")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasSetTextAction()).assertDoesNotExist()
                capture("chat-fullscreen-light")
                InstrumentationRegistry.getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose
                    .onNode(hasSetTextAction() and hasText("Un brouillon sur téléphone"))
                    .assertExists()
                compose.onNodeWithContentDescription("Options de la conversation").performClick()
                compose.onNodeWithText("Détails de la conversation").performClick()
                compose.onAllNodesWithText("Agent principal").onLast().assertExists()
                capture("chat-details-light")
            } finally {
                vm.api.closeStreams()
            }
        }
    }

    fun adaptiveTaskLayout() {
        MockWebServer().use { server ->
            val vm = fixture(server)
            compose.setContent { LeoTheme("dark") { LeoApp(vm = vm) } }
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("Tâches").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Tâches").performClick()
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("Conversation").fetchSemanticsNodes().isNotEmpty()
            }
            val width =
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .resources
                    .configuration
                    .screenWidthDp
            if (width >= 940) compose.onNodeWithText("Rechercher une tâche").assertIsDisplayed()
            capture("task-adaptive-dark")
            vm.api.closeStreams()
        }
    }

    fun taskConversationAndManagement() {
        MockWebServer().use { server ->
            val vm = fixture(server)
            compose.setContent { LeoTheme("dark") { LeoApp(vm = vm) } }
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("Tâches").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Tâches").performClick()
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("Conversation").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Tâche terminée"))
            capture("task-conversation-dark")
            compose.onNode(hasText("Tâches") and hasText(task.name)).performClick()
            capture("task-inbox-dark")
            compose
                .onNode(hasText(task.name) and hasText("Prochaine :", substring = true))
                .performClick()
            compose.onNodeWithContentDescription("Options de l’exécution").performClick()
            compose.onNodeWithText("Détails de la tâche").performClick()
            capture("task-details-dark")
            compose.onNodeWithText("Modifier").performClick()
            compose.onNodeWithText("Enregistrer").assertIsEnabled()
            capture("task-editor-dark")
            vm.api.closeStreams()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ParityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactChatEvidenceAndSwitcher() =
        ParityDeviceCases(compose).compactChatEvidenceAndSwitcher()

    @Test
    fun taskConversationAndManagement() = ParityDeviceCases(compose).taskConversationAndManagement()

    @Test fun adaptiveTaskLayout() = ParityDeviceCases(compose).adaptiveTaskLayout()
}
