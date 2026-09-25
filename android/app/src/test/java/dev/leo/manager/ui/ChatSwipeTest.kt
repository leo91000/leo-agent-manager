package dev.leo.manager.ui

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.leo.manager.data.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class ChatSwipeTest {
    @get:Rule val compose = createComposeRule()
    private val history get() = compose.onNodeWithTag("conversation-history")

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Application>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After fun cleanup() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test fun swipesNavigateInSelectorOrderPreserveDraftsAndRespectEnds() = withChats {
        open("Chat récent")
        compose.onNode(hasSetTextAction()).performTextInput("Brouillon récent")
        history.performTouchInput { swipeRight() }
        title("Chat récent").assertExists()
        history.performTouchInput { swipeLeft() }
        waitForChat("Chat intermédiaire")
        compose.onNode(hasSetTextAction()).performTextInput("Brouillon intermédiaire")
        history.performTouchInput { swipeLeft() }
        waitForChat("Chat ancien")
        history.performTouchInput { swipeLeft() }
        title("Chat ancien").assertExists()
        history.performTouchInput { swipeRight() }
        waitForChat("Chat intermédiaire")
        compose.onNode(hasSetTextAction() and hasText("Brouillon intermédiaire")).assertExists()
        history.performTouchInput { swipeRight() }
        waitForChat("Chat récent")
        compose.onNode(hasSetTextAction() and hasText("Brouillon récent")).assertExists()
        // Switching replaces the detail route: Back returns directly to Fil.
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithTag("fil").assertExists()
    }

    @Test fun verticalShortCancelledAndComposerGesturesDoNotNavigate() = withChats {
        open("Chat intermédiaire")
        val beforeScroll = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        history.performTouchInput { swipeDown() }
        title("Chat intermédiaire").assertExists()
        Assert.assertTrue(history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < beforeScroll)
        history.performTouchInput { swipe(center, center + Offset(-35f, 0f)) }
        title("Chat intermédiaire").assertExists()
        history.performTouchInput {
            down(Offset(width * .85f, centerY))
            moveTo(Offset(width * .15f, centerY), 300)
            cancel()
        }
        title("Chat intermédiaire").assertExists()
        compose.onNode(hasSetTextAction()).performTouchInput { swipeLeft() }
        title("Chat intermédiaire").assertExists()
        // A cancelled gesture must not leak its accumulated distance into the next one.
        history.performTouchInput { swipeRight() }
        waitForChat("Chat récent")
    }

    @Test fun singleAndNewConversationsDoNotNavigate() = withChats(single = true) {
        open("Chat récent")
        history.performTouchInput { swipeLeft(); swipeRight() }
        title("Chat récent").assertExists()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithContentDescription("Nouvelle conversation").performClick()
        history.performTouchInput { swipeLeft(); swipeRight() }
        title("Nouvelle conversation").assertExists()
    }

    private fun title(name: String) = compose.onNodeWithContentDescription("Changer de conversation : $name")

    private fun waitForChat(name: String) {
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Changer de conversation : $name").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("conversation-history").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value()?.let { it > 0f } == true
        }
        compose.waitForIdle()
    }

    private fun open(name: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performClick()
        waitForChat(name)
    }

    private fun withChats(single: Boolean = false, test: () -> Unit) {
        MockWebServer().use { server ->
            val agent = Agent(MAIN_AGENT_ID, "Agent principal")
            val chats = if (single) listOf(Chat("recent", "Chat récent", updatedAt = 300)) else listOf(
                Chat("old", "Chat ancien", updatedAt = 100),
                Chat("recent", "Chat récent", updatedAt = 300),
                Chat("middle", "Chat intermédiaire", updatedAt = 200),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path.endsWith("/stream")) {
                        val chat = chats.find { path == "/api/chats/${it.id}/stream" }
                        val events = if (chat == null) emptyList() else (1..30).map {
                            RunEvent(it.toLong(), it.toLong(), "item.completed", "", mapOf(
                                "item" to buildJsonObject {
                                    put("id", "answer-$it")
                                    put("type", "agent_message")
                                    put("text", "Réponse **$it** de ${chat.title}. " +
                                        "Du contenu sélectionnable pour faire défiler l’historique. ".repeat(12))
                                },
                            ))
                        }
                        val state = if (chat == null) LiveState(chats = chats) else LiveState(chat = chat)
                        val frame = "event: batch\nid: ${events.lastOrNull()?.id ?: 0}\ndata: ${wireJson.encodeToString(LiveBatch(events, state, false, false))}\n\n"
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(frame + ": keepalive\n\n".repeat(10000))
                            .throttleBody(frame.toByteArray().size.toLong(), 1, TimeUnit.SECONDS)
                    }
                    val body = when (path) {
                        "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                        "/api/agents" -> wireJson.encodeToString(listOf(agent))
                        "/api/codex/models", "/api/claude/models" -> """{"models":[]}"""
                        "/api/overview" -> "{}"
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val vault = object : SessionVault {
                override fun read(origin: String) = "leo_session=fixture; Path=/; Max-Age=3600"
                override fun write(origin: String, cookie: String?) = Unit
            }
            val vm = LeoViewModel(ApplicationProvider.getApplicationContext<Application>(), vault)
            compose.setContent {
                LaunchedEffect(Unit) {
                    vm.state.first { it.ready }
                    vm.connect(server.url("/").toString())
                }
                LeoTheme { LeoApp(vm = vm) }
            }
            try { test() } finally { vm.api.closeStreams() }
        }
    }
}
