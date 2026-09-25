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
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class CodexAccountsJourneyTest {
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
    fun `account concurrency above four is saved and restored while invalid input cannot save`() {
        MockWebServer().use { server ->
            val updates = CopyOnWriteArrayList<JsonObject>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val result =
                            when (request.path) {
                                "/api/session" -> """{"authenticated":true,"csrf":"fixture"}"""
                                "/api/codex/accounts" ->
                                    """[{"id":"account","name":"Compte principal","state":"ready","maxConcurrentRuns":${updates.lastOrNull()?.get("maxConcurrentRuns") ?: 4}}]"""
                                "/api/codex/accounts/login" -> "null"
                                "/api/codex/accounts/account" -> {
                                    assertEquals("PUT", request.method)
                                    assertEquals("fixture", request.getHeader("X-CSRF-Token"))
                                    updates +=
                                        wireJson
                                            .parseToJsonElement(request.body.readUtf8())
                                            .jsonObject
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
                LeoTheme { if (state.session.authenticated) Page { CodexAccounts(vm, state) {} } }
            }
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("Modifier").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Modifier").performScrollTo().performClick()
            val input = compose.onNodeWithText("Exécutions parallèles (minimum 1)")
            for (invalid in listOf("", "0", "-1", "1.5")) {
                input.performTextReplacement(invalid)
                compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
            }
            input.performTextReplacement("12")
            compose.onNodeWithText("Enregistrer").assertIsEnabled().performClick()
            compose.waitUntil(10000) { updates.isNotEmpty() }
            assertEquals(12, updates.single()["maxConcurrentRuns"]?.jsonPrimitive?.int)
            compose.waitUntil(10000) {
                compose
                    .onAllNodes(hasText("Modifier") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("0 / 12 exécutions parallèles", substring = true).assertExists()
            compose.onNodeWithText("Modifier").performScrollTo().performClick()
            compose.onNodeWithText("Exécutions parallèles (minimum 1)").assertTextContains("12")
        }
    }
}
