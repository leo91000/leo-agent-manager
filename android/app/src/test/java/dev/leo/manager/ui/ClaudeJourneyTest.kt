package dev.leo.manager.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.*
import dev.leo.manager.data.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class ClaudeJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Application>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun cleanup() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `Claude login sends only the one time code and shows connected state`() {
        MockWebServer().use { server ->
            var pending = false
            var connected = false
            val codes = CopyOnWriteArrayList<String>()
            var maxConcurrent = 4
            val limits = CopyOnWriteArrayList<String>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val result =
                            when (request.path) {
                                "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                                "/api/claude/connection" -> {
                                    if (request.method == "PATCH") {
                                        val payload = request.body.readUtf8()
                                        limits += payload
                                        assertEquals("fixture", request.getHeader("X-CSRF-Token"))
                                        maxConcurrent = 2
                                    }
                                    """{"connected":$connected,"maxConcurrent":$maxConcurrent,"email":"claude@example.test","usage":{"windows":[{"id":"five_hour","label":"5-hour window","usedPercent":25,"resetsAt":1893499200},{"id":"seven_day","label":"Weekly","usedPercent":60,"resetsAt":1894017600}],"stale":false,"checkedAt":1700000000000},"login":${if (pending) """{"id":"attempt","state":"pending","url":"https://claude.ai/oauth/authorize?fixture=1"}""" else "null"}}"""
                                }
                                "/api/claude/login" -> {
                                    pending = true
                                    "{}"
                                }
                                "/api/claude/login/code" -> {
                                    assertEquals("fixture", request.getHeader("X-CSRF-Token"))
                                    codes += request.body.readUtf8()
                                    pending = false
                                    connected = true
                                    "{}"
                                }
                                "/api/overview",
                                "/api/codex/models",
                                "/api/claude/models" -> "{}"
                                else -> "[]"
                            }
                        return MockResponse().setBody(result)
                    }
                }
            server.start()
            val vm =
                LeoViewModel(
                    ApplicationProvider.getApplicationContext<Application>(),
                    MemoryVault(),
                )
            compose.setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    vm.state.first { it.ready }
                    vm.connect(server.url("/").toString())
                }
                LeoTheme { if (state.session.authenticated) Page { ClaudeConnection(vm, state) } }
            }
            compose.waitUntil(15000) {
                compose
                    .onAllNodesWithText("Connecter Claude Code")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Connecter Claude Code").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText("Code d’autorisation Claude")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithText("Code d’autorisation Claude")
                .performScrollTo()
                .performTextInput("fixture-code")
            compose.onNodeWithText("Terminer la connexion").performScrollTo().performClick()
            compose.waitUntil(10000) { codes.isNotEmpty() }
            assertTrue(codes.single().contains("fixture-code"))
            assertTrue(codes.single().contains("attempt"))
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText("Reconnecter Claude Code")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Connecté").assertExists()
            compose
                .onNodeWithText("Conversations Claude simultanées")
                .performScrollTo()
                .performTextReplacement("2")
            compose.onNodeWithText("Enregistrer la limite").performScrollTo().performClick()
            compose.waitUntil(10000) { limits.isNotEmpty() }
            assertTrue(limits.single().contains("\"maxConcurrent\":2"))
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText("Enregistrer la limite")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Enregistrer la limite").assertIsNotEnabled()
            compose.onNodeWithText("Fenêtre de 5 heures · 75 % restants").assertExists()
            compose.onNodeWithText("Semaine · 40 % restants").assertExists()
        }
    }

    @Test
    fun `old agents default to Codex and Claude provider survives serialization`() {
        val old = wireJson.decodeFromString<Agent>("""{"name":"Existing"}""")
        assertEquals("codex", old.provider)
        val claude = old.copy(provider = "claude", model = "sonnet", reasoning = "high")
        assertEquals(claude, wireJson.decodeFromString<Agent>(wireJson.encodeToString(claude)))
    }
}
