package dev.leo.manager.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.leo.manager.data.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class GithubRepositoryPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun retryPaginationFilterAndSelection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"GitHub indisponible"}"""))
            server.enqueue(MockResponse().setBody("""{"repositories":[{"fullName":"team/existing","name":"existing","imported":true}],"nextPage":2}"""))
            server.enqueue(MockResponse().setBody("""{"repositories":[{"fullName":"team/selected","name":"selected","defaultBranch":"develop","private":true}],"nextPage":null}"""))
            server.start()
            val vault = object : SessionVault {
                override fun read(origin: String): String? = null
                override fun write(origin: String, cookie: String?) {}
            }
            val api = LeoApi(server.url("/"), vault)
            var selection: GithubRepository? = null
            compose.setContent {
                var selected by remember { mutableStateOf("") }
                LeoTheme { Column { GithubRepositoryPicker(api, selected) { selection = it; selected = it.fullName } } }
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Réessayer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Réessayer").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("team/existing").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("team/existing").assertIsNotEnabled()
            compose.onNodeWithText("Charger plus de dépôts").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("team/selected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Filtrer les dépôts chargés").performTextInput("selected")
            compose.onNodeWithText("team/existing").assertDoesNotExist()
            compose.onNodeWithText("team/selected").performClick()
            compose.runOnIdle { assertEquals("develop", selection?.defaultBranch) }
        }
    }
}
