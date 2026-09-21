package dev.leo.manager.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.leo.manager.data.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Android rendering previews; these are explicitly not emulator captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeParityPreviewTest {
    @get:Rule val compose = createComposeRule()

    @org.junit.Before
    fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking { Preferences(app).setOrigin("") }
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            androidx.work.Configuration.Builder()
                .setExecutor(androidx.work.testing.SynchronousExecutor())
                .build(),
        )
    }

    @org.junit.After
    fun finish() {
        androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun phoneConversationEvidenceAndTaskLayout() = preview(false)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tabletTaskInboxAndConversationLayout() = preview(true)

    private fun preview(wide: Boolean) {
        MockWebServer().use { server ->
            val now = System.currentTimeMillis()
            val task =
                Task(
                    "task",
                    "Revue hebdomadaire du projet",
                    "Vérifier les changements et préparer le compte rendu.",
                    MAIN_AGENT_ID,
                    cron = "0 9 * * 1",
                    nextRun = now + 86400000,
                )
            val agent = Agent(MAIN_AGENT_ID, "Agent principal")
            val project = Project("project", "Leo Agent Manager")
            val outcome =
                TaskOutcome(
                    "completed",
                    "Les changements sont prêts.",
                    listOf(
                        "Vérifications réussies et compte rendu préparé.",
                        "La navigation et les messages longs ont été vérifiés.",
                    ),
                    now,
                    "reply",
                )
            val run =
                Run(
                    "run",
                    taskId = task.id,
                    status = "succeeded",
                    taskName = task.name,
                    snapshot = Snapshot(task = task, agent = agent, project = project),
                    outcome = outcome,
                )
            val chat =
                Chat(
                    "chat",
                    "Simplifier l’interface mobile",
                    agentName = agent.name,
                    projectName = project.name,
                    runId = run.id,
                    run = run.copy(trigger = "chat"),
                    updatedAt = now,
                )
            val reply =
                "La nouvelle interface est prête.\n\n- Navigation compacte et explicite\n- Détails disponibles à la demande\n- Plus de place pour la conversation\n\nLes longues adresses restent lisibles : https://example.test/" +
                    "une-longue-adresse-".repeat(7)
            val events =
                listOf(
                    RunEvent(1, now - 60000, "chat.user", "Peux-tu simplifier cette interface ?"),
                    RunEvent(
                        2,
                        now,
                        "item.completed",
                        "",
                        mapOf(
                            "item" to
                                buildJsonObject {
                                    put("id", "reply")
                                    put("type", "agent_message")
                                    put("text", reply)
                                }
                        ),
                    ),
                )
            fun stream(state: LiveState): MockResponse {
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
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body =
                            when (request.path!!.substringBefore('?')) {
                                "/api/session" -> "{\"authenticated\":true,\"csrf\":\"fixture\"}"
                                "/api/agents" ->
                                    wireJson.encodeToString(
                                        listOf(Agent("custom", "Agent spécialisé"), agent)
                                    )
                                "/api/projects" -> wireJson.encodeToString(listOf(project))
                                "/api/tasks" ->
                                    wireJson.encodeToString(
                                        listOf(
                                            task,
                                            task.copy(
                                                id = "next",
                                                name = "Préparer la prochaine version",
                                                cron = null,
                                                nextRun = null,
                                            ),
                                        )
                                    )
                                "/api/tasks/activity" -> wireJson.encodeToString(listOf(run))
                                "/api/chats/stream" ->
                                    return stream(LiveState(chats = listOf(chat)))
                                "/api/chats/chat/stream" ->
                                    return stream(LiveState(chat = chat, run = chat.run))
                                "/api/runs/run/stream" -> return stream(LiveState(run = run))
                                "/api/skills",
                                "/api/mcps" -> "[]"
                                else -> "{}"
                            }
                        return MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(body)
                    }
                }
            val vm =
                LeoViewModel(
                    ApplicationProvider.getApplicationContext<Application>(),
                    MemoryVault(),
                )
            lateinit var activity: ComponentActivity
            compose.setContent {
                activity = LocalActivity.current as ComponentActivity
                LaunchedEffect(Unit) {
                    vm.state.first { it.ready }
                    vm.connect(server.url("/").toString())
                }
                LeoTheme("dark") { LeoApp(vm = vm) }
            }
            try {
                waitText(chat.title)
                if (!wide) {
                    compose.onNodeWithText(chat.title).performClick()
                    waitText("Tâche terminée")
                    compose
                        .onNodeWithTag("conversation-history")
                        .performScrollToNode(hasText("Tâche terminée"))
                    awaitMarkdown(activity, "La nouvelle interface")
                    capture("chat-phone-dark")
                    compose.onNodeWithText("Détails").performClick()
                    compose.onNode(hasText("Rapporté par", substring = true)).performScrollTo()
                    capture("chat-evidence-phone-dark")
                    compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
                    waitText("Tâches")
                }
                compose.onNodeWithText("Tâches").performClick()
                waitText("Conversation")
                compose
                    .onAllNodes(hasScrollToIndexAction())
                    .onLast()
                    .performScrollToNode(hasText("Tâche terminée"))
                awaitMarkdown(activity, "La nouvelle interface")
                if (wide) compose.onNodeWithText("Rechercher une tâche").assertIsDisplayed()
                capture(if (wide) "tasks-tablet-dark" else "task-phone-dark")
                if (wide) {
                    compose.onNode(hasText("Tâches") and hasText(task.name)).performClick()
                    compose.onNode(isDialog()).assertIsDisplayed()
                    capture("task-picker-tablet-dark")
                }
                if (!wide) {
                    compose.onNode(hasText("Tâches") and hasText(task.name)).performClick()
                    compose.onNodeWithText("Rechercher une tâche").assertIsDisplayed()
                    capture("task-inbox-phone-dark")
                    compose.onNodeWithContentDescription("Créer une tâche").performClick()
                    compose
                        .onNode(hasText("Agent principal") and hasClickAction())
                        .assertIsDisplayed()
                    capture("task-editor-phone-dark")
                }
            } finally {
                vm.api.closeStreams()
            }
        }
    }

    private fun waitText(value: String) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitMarkdown(activity: ComponentActivity, text: String) {
        fun textViews(view: View): List<TextView> =
            when (view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
                else -> emptyList()
            }
        compose.waitUntil(15000) {
            compose.onRoot().fetchSemanticsNode()
            textViews(activity.window.decorView).any { it.text.contains(text) }
        }
    }

    private fun capture(name: String) {
        val dir = System.getProperty("leo.screenshots.dir") ?: return
        compose.waitForIdle()
        File(dir).mkdirs()
        val target =
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
                compose.onNode(isDialog())
            else compose.onRoot()
        target
            .captureToImage()
            .asAndroidBitmap()
            .compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                File(dir, "$name.png").outputStream(),
            )
    }
}
