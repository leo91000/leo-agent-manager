package dev.leo.manager.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatAttachment(
    val id: String,
    val chatId: String = "",
    val name: String,
    val size: Long = 0,
    val mediaType: String = "application/octet-stream",
    val kind: String = "file",
)

@Serializable
data class ChatMessage(
    val id: String,
    val chatId: String = "",
    val text: String = "",
    val model: String = "",
    val reasoning: String = "",
    val mode: String = "queue",
    val status: String = "queued",
    val createdAt: Long = 0,
    val attachments: List<ChatAttachment> = emptyList(),
    val questionId: String? = null,
)

@Serializable data class QuestionOption(val label: String, val description: String = "")

@Serializable
data class QuestionField(
    val id: String,
    val title: String,
    val secret: Boolean = false,
    val options: List<QuestionOption> = emptyList(),
)

@Serializable
data class ChatQuestion(
    val id: String,
    val chatId: String,
    val runId: String = "",
    val blocking: Boolean = false,
    val fields: List<QuestionField> = emptyList(),
    val status: String = "pending",
    val createdAt: Long = 0,
)

@Serializable
data class Chat(
    val id: String,
    val title: String = "Nouvelle conversation",
    val agentId: String = MAIN_AGENT_ID,
    val projectId: String? = null,
    val runId: String? = null,
    val paused: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pendingQuestions: Int = 0,
    val agentName: String = "",
    val projectName: String? = null,
    val status: String = "idle",
    val questions: List<ChatQuestion> = emptyList(),
    val run: Run? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)

@Serializable
data class Deliverable(
    val id: String,
    val runId: String,
    val messageId: String? = null,
    val key: String,
    val version: Int = 1,
    val title: String = "",
    val name: String = "",
    val group: String = "",
    val kind: String = "file",
    val mediaType: String = "application/octet-stream",
    val size: Long = 0,
    val createdAt: Long = 0,
    val previewStatus: String = "none",
    val excerpt: String? = null,
) {
    fun path(preview: Boolean = false) =
        "/runs/${segment(runId)}/artifacts/${segment(id)}" +
            if (preview) "?preview=1" else "?download=1"
}

fun latestArtifacts(items: List<Deliverable>) =
    items
        .groupBy { it.runId to it.key }
        .values
        .map { versions -> versions.maxBy { it.version } }
        .sortedBy { it.createdAt }

@Serializable
data class LiveState(
    val run: Run? = null,
    val chat: Chat? = null,
    val chats: List<Chat>? = null,
    val artifacts: List<Deliverable> = emptyList(),
)

@Serializable
data class LiveBatch(
    val events: List<RunEvent>,
    val state: LiveState? = null,
    val reset: Boolean,
    val more: Boolean,
)

@Serializable data class ReasoningOption(val reasoningEffort: String, val description: String = "")

@Serializable
data class CodexModel(
    val model: String,
    val displayName: String = "",
    val description: String = "",
    val hidden: Boolean = false,
    val isDefault: Boolean = false,
    val defaultReasoningEffort: String = "",
    val supportedReasoningEfforts: List<ReasoningOption> = emptyList(),
)

@Serializable
data class ModelCatalog(
    val models: List<CodexModel> = emptyList(),
    val checkedAt: Long? = null,
    val stale: Boolean = false,
    val error: String = "",
)

@Serializable
data class McpTool(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)

@Serializable
data class Mcp(
    val id: String = "",
    val name: String = "",
    val transport: String = "http",
    val url: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val auth: String = "none",
    val clientId: String = "",
    val scopes: String = "",
    val allowPrivateNetwork: Boolean = false,
    val enabled: Boolean = true,
    val enabledTools: List<String>? = null,
    val state: String = "untested",
    val error: String = "",
    val tools: List<McpTool> = emptyList(),
    val checkedAt: Long? = null,
    val hasToken: Boolean = false,
    val hasClientSecret: Boolean = false,
    val envKeys: List<String> = emptyList(),
    val callbackUrl: String = "",
)

@Serializable data class UrlResult(val url: String)

@Serializable data class Configured(val configured: Boolean = false)

@Serializable
data class UsageWindow(
    val usedPercent: Double,
    val windowDurationMins: Int? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class UsageBucket(
    val limitName: String? = null,
    val primary: UsageWindow? = null,
    val secondary: UsageWindow? = null,
    val rateLimitReachedType: String? = null,
    val spendControlReached: Boolean? = null,
)

@Serializable data class ResetCredits(val availableCount: Int = 0)

@Serializable
data class AccountLimits(
    val ordinaryUsageAllowed: Boolean? = null,
    val rateLimits: UsageBucket = UsageBucket(),
    val rateLimitsByLimitId: Map<String, UsageBucket>? = null,
    val rateLimitResetCredits: ResetCredits? = null,
)

@Serializable
data class CodexAccount(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val email: String? = null,
    val plan: String? = null,
    val state: String = "pending",
    val error: String = "",
    val resetError: String = "",
    val checkedAt: Long? = null,
    val remainingPercent: Double? = null,
    val stale: Boolean = true,
    val activeRunId: String? = null,
    val activeRunIds: List<String>? = null,
    val maxConcurrentRuns: Int = 4,
    val lastUsedAt: Long? = null,
    val exhausted: JsonElement? = null,
    val limits: AccountLimits? = null,
) {
    val runs
        get() = activeRunIds ?: listOfNotNull(activeRunId)

    val blocked
        get() =
            exhausted != null ||
                limits?.ordinaryUsageAllowed == false ||
                limits?.rateLimits?.rateLimitReachedType != null ||
                limits?.rateLimits?.spendControlReached == true
}

@Serializable
data class CodexLogin(
    val accountId: String = "",
    val state: String,
    val phase: String = "starting",
    val code: String? = null,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
data class RunWorkspace(val projectId: String, val path: String, val kind: String = "worktree")

/**
 * Recognize only a delivered file on the connected origin; tool-provided URLs are never fetched.
 */
fun artifactForLink(
    link: String,
    origin: okhttp3.HttpUrl,
    artifacts: List<Deliverable>,
): Deliverable? {
    val url = origin.resolve(link) ?: return null
    if (
        url.scheme != origin.scheme ||
            url.host != origin.host ||
            url.port != origin.port ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty()
    )
        return null
    return artifacts.find { url.encodedPath == "/api" + it.path().substringBefore('?') }
}
