package dev.leo.manager.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.*
import dev.leo.manager.data.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class ArtifactLinksTest {
    @get:Rule val compose = createComposeRule()

    @Before fun initializeWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext<Application>(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }
    @After fun closeWork() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test fun `older artifact links load authenticated metadata and open the native preview`() {
        MockWebServer().use { server ->
            val paths = CopyOnWriteArrayList<String>()
            val file = Deliverable(id = "file", runId = "older", key = "notes", name = "notes.md", kind = "markdown", mediaType = "text/markdown")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path.startsWith("/api/runs/")) {
                        paths.add(path)
                        if (request.getHeader("Cookie") != "leo_session=fixture") return MockResponse().setResponseCode(401).setBody("{\"error\":\"Missing session\"}")
                    }
                    val body = when (path) {
                        "/api/session" -> return MockResponse().setHeader("Set-Cookie", "leo_session=fixture; Path=/; HttpOnly").setBody("{\"authenticated\":true,\"csrf\":\"fixture\"}")
                        "/api/runs/older/artifacts" -> wireJson.encodeToString(listOf(file))
                        "/api/runs/older/artifacts/file?download=1" -> "Document conservé depuis un ancien run."
                        "/api/agents" -> wireJson.encodeToString(listOf(Agent(id = MAIN_AGENT_ID, name = "Leo")))
                        "/api/overview", "/api/codex/models" -> "{}"
                        else -> "[]"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val vm = LeoViewModel(ApplicationProvider.getApplicationContext<Application>(), MemoryVault())
            compose.setContent {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.state.first { it.ready }; vm.connect(server.url("/").toString()) }
                LeoTheme {
                    if (state.agents.isNotEmpty()) ArtifactLinkHost(vm, emptyList()) {
                        val open = LocalArtifactLinks.current
                        TextButton(onClick = { assertTrue(open("/api/runs/older/artifacts/file?download=1")) }) { Text("Ouvrir le document") }
                    }
                }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Ouvrir le document").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Ouvrir le document").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { paths.contains("/api/runs/older/artifacts/file?download=1") }
            compose.onNodeWithContentDescription("Enregistrer").assertIsEnabled()
            compose.onNodeWithContentDescription("Fermer le fichier").performClick()
            assertEquals(listOf("/api/runs/older/artifacts", "/api/runs/older/artifacts/file?download=1"), paths.toList())
        }
    }
}
