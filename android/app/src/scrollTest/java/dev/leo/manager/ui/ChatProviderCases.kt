package dev.leo.manager.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dev.leo.manager.data.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

abstract class ChatProviderCases {
    @get:Rule val compose = createComposeRule()

    @Test fun chatProviderSurvivesRecreationAndResetsIncompatibleModelBeforeSending() {
        MockWebServer().use { server ->
            val sent = CopyOnWriteArrayList<JsonObject>()
            val agent = Agent(MAIN_AGENT_ID, "Agent principal", model = "fixture-deep", reasoning = "ultra")
            val run = Run("run", status = "succeeded", snapshot = Snapshot(agent = agent))
            val chat = Chat("chat", title = "Contexte conservé", runId = "run", agentName = agent.name, run = run)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    if (path == "/api/chats/chat/stream") {
                        val frame = "event: batch\nid: 0\ndata: ${wireJson.encodeToString(LiveBatch(emptyList(), LiveState(chat = chat, run = run), true, false))}\n\n"
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(frame + ": keepalive\n\n".repeat(10000))
                            .throttleBody(frame.toByteArray().size.toLong(), 1, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    val body = when (path) {
                        "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                        "/api/agents" -> wireJson.encodeToString(listOf(agent))
                        "/api/chats/chat/messages" -> { sent += wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject; "{}" }
                        "/api/codex/models" -> """{"models":[{"model":"fixture-deep","displayName":"Deep thinker","supportedReasoningEfforts":[{"reasoningEffort":"ultra"}]}]}"""
                        "/api/claude/models" -> """{"models":[{"model":"opus[1m]","displayName":"Opus 1M","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"high"}]}]}"""
                        "/api/overview" -> "{}"
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val vm = LeoViewModel(ApplicationProvider.getApplicationContext<Application>(), ProviderVault())
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.state.first { it.ready }; if (!vm.state.value.session.authenticated) vm.connect(server.url("/").toString()) }
                LeoTheme { if (state.session.authenticated) ChatScreen(vm, state, "chat", openChat = {}, openRun = {}) }
            }
            compose.waitUntil(20000) { compose.onAllNodesWithText("Deep thinker", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Claude Code").performClick()
            compose.onNodeWithText("Claude Code").assertIsSelected()
            compose.onNodeWithText("Deep thinker", substring = true).assertDoesNotExist()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Opus 1M", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("Continue les modifications existantes")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Claude Code").assertIsSelected()
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("conversation-send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("conversation-send").performClick()
            compose.waitUntil(10000) { sent.size == 1 }
            assertEquals("claude", sent[0]["provider"]?.jsonPrimitive?.content)
            assertEquals("Continue les modifications existantes", sent[0]["text"]?.jsonPrimitive?.content)
            assertEquals("", sent[0]["model"]?.jsonPrimitive?.content)
            assertEquals("", sent[0]["reasoning"]?.jsonPrimitive?.content)
            assertEquals("queue", sent[0]["mode"]?.jsonPrimitive?.content)
            compose.waitUntil(10000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty() }
            compose.onNodeWithText("Codex", substring = false).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Reviens à Codex")
            compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("conversation-send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("conversation-send").performClick()
            compose.waitUntil(10000) { sent.size == 2 }
            assertEquals("codex", sent[1]["provider"]?.jsonPrimitive?.content)
            assertNotEquals(sent[0]["id"], sent[1]["id"])
        }
    }
}

private class ProviderVault : SessionVault {
    private val values = mutableMapOf<String, String>()
    override fun read(origin: String) = values[origin]
    override fun write(origin: String, cookie: String?) {
        if (cookie == null) values.remove(origin) else values[origin] = cookie
    }
}
