package dev.leo.manager.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import dev.leo.manager.data.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ClaudeDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun claudeLoginCompletesWithAuthorizationCode() {
        MockWebServer().use { server ->
            var pending = false
            var connected = false
            val codes = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val result = when (request.path) {
                        "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                        "/api/claude/connection" -> """{"connected":$connected,"email":"claude@example.test","login":${if (pending) """{"id":"attempt","state":"pending","url":"https://claude.ai/oauth/authorize?fixture=1"}""" else "null"}}"""
                        "/api/claude/login" -> { pending = true; "{}" }
                        "/api/claude/login/code" -> {
                            assertEquals("fixture", request.getHeader("X-CSRF-Token"))
                            codes += request.body.readUtf8()
                            pending = false; connected = true
                            "{}"
                        }
                        "/api/overview", "/api/codex/models", "/api/claude/models" -> "{}"
                        else -> "[]"
                    }
                    return MockResponse().setBody(result)
                }
            }
            server.start()
            val vm = LeoViewModel(ApplicationProvider.getApplicationContext<Application>(), DeviceClaudeVault())
            compose.setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.state.first { it.ready }; vm.connect(server.url("/").toString()) }
                LeoTheme { if (state.session.authenticated) Page { ClaudeConnection(vm, state) } }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Connecter Claude Code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Connecter Claude Code").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Code d’autorisation Claude").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Code d’autorisation Claude").performScrollTo().performTextInput("fixture-code")
            compose.onNodeWithText("Terminer la connexion").performScrollTo().performClick()
            compose.waitUntil(10000) { codes.isNotEmpty() }
            assertTrue(codes.single().contains("fixture-code"))
            assertTrue(codes.single().contains("attempt"))
            compose.waitUntil(10000) { compose.onAllNodesWithText("Reconnecter Claude Code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Connecté").assertExists()
        }
    }
    @Test fun providerSerializationPreservesExistingAgents() {
        val old = wireJson.decodeFromString<Agent>("""{"name":"Existing"}""")
        assertEquals("codex", old.provider)
        val claude = old.copy(provider = "claude", model = "sonnet", reasoning = "high")
        assertEquals(claude, wireJson.decodeFromString<Agent>(wireJson.encodeToString(claude)))
    }
}

private class DeviceClaudeVault : SessionVault {
    private val values = mutableMapOf<String, String>()
    override fun read(origin: String) = values[origin]
    override fun write(origin: String, cookie: String?) {
        if (cookie == null) values.remove(origin) else values[origin] = cookie
    }
}
