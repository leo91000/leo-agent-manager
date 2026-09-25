package dev.leo.manager.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dev.leo.manager.data.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

abstract class ConversationLifecycleCases {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeRevealsDeleteWithoutDeletingAndTrashRemainsSecondary() {
        MockWebServer().use { server ->
            val deleted = AtomicBoolean(false)
            val deletes = CopyOnWriteArrayList<String>()
            val restores = CopyOnWriteArrayList<String>()
            val chat = Chat("chat", title = "Conversation à conserver", agentName = "Agent principal", updatedAt = System.currentTimeMillis())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    if (path == "/api/chats/stream") {
                        val chats = if (deleted.get()) emptyList() else listOf(chat)
                        val frame = "event: batch\nid: 0\ndata: ${wireJson.encodeToString(LiveBatch(emptyList(), LiveState(chats = chats), true, false))}\n\n"
                        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frame)
                    }
                    if (path == "/api/chats/chat/stream") {
                        val current = chat.copy(lifecycle = if (deleted.get()) "trash" else "active", purgeAt = if (deleted.get()) System.currentTimeMillis() + 86400000 else null)
                        val frame = "event: batch\nid: 0\ndata: ${wireJson.encodeToString(LiveBatch(emptyList(), LiveState(chat = current), true, false))}\n\n"
                        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frame)
                    }
                    val body = when {
                        path == "/api/chats/chat/restore" && request.method == "POST" -> {
                            restores += request.body.readUtf8()
                            deleted.set(false)
                            wireJson.encodeToString(chat)
                        }
                        path == "/api/chats/chat" && request.method == "DELETE" -> {
                            deletes += request.body.readUtf8()
                            deleted.set(true)
                            "{}"
                        }
                        path == "/api/chats" && request.path!!.contains("view=trash") -> {
                            val trashed = wireJson.encodeToJsonElement(chat).jsonObject.toMutableMap()
                            trashed["lifecycle"] = JsonPrimitive("trash")
                            JsonArray(listOf(JsonObject(trashed))).toString()
                        }
                        path == "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                        path == "/api/agents" -> wireJson.encodeToString(listOf(Agent(MAIN_AGENT_ID, "Agent principal")))
                        path == "/api/overview" -> "{}"
                        path.endsWith("/models") -> """{"models":[]}"""
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val vm = LeoViewModel(ApplicationProvider.getApplicationContext<Application>(), LifecycleVault())
            compose.setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                var selected by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { vm.state.first { it.ready }; if (!vm.state.value.session.authenticated) vm.connect(server.url("/").toString()) }
                LeoTheme {
                    if (state.session.authenticated) {
                        if (selected == null) ChatsScreen(vm, state, open = { selected = it }, create = {})
                        else ChatScreen(vm, state, selected, openChat = { selected = it }, openRun = {}, back = { selected = null })
                    }
                }
            }
            compose.waitUntil(20000) { compose.onAllNodesWithText(chat.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Corbeille").assertDoesNotExist()
            compose.onNodeWithText(chat.title).performTouchInput { swipeLeft() }
            compose.onNodeWithText("Supprimer", substring = false).assertIsDisplayed()
            assertTrue("The swipe must not delete on its own", deletes.isEmpty())
            compose.onNodeWithText("Supprimer", substring = false).performClick()
            compose.waitUntil(10000) { deletes.size == 1 }
            compose.waitUntil(15000) { compose.onAllNodesWithText(chat.title).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Afficher les conversations").performClick()
            compose.onNodeWithText("Corbeille", substring = false).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(chat.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(chat.title).assertIsDisplayed()
            compose.onNodeWithText(chat.title).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Restaurer la conversation").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Opening the trash must not restore it", restores.isEmpty())
            compose.onNodeWithText("Restaurer la conversation").performClick()
            compose.waitUntil(15000) { restores.size == 1 }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Cette conversation est dans la corbeille.").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("conversation-composer").assertIsDisplayed()
        }
    }
}

private class LifecycleVault : SessionVault {
    private val values = mutableMapOf<String, String>()
    override fun read(origin: String) = values[origin]
    override fun write(origin: String, cookie: String?) {
        if (cookie == null) values.remove(origin) else values[origin] = cookie
    }
}
