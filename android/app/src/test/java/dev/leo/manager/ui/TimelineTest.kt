package dev.leo.manager.ui

import dev.leo.manager.data.RunEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private fun event(
        id: Long,
        type: String,
        itemId: String = "",
        itemType: String = "command_execution",
        status: String = "in_progress",
    ) =
        RunEvent(
            id,
            id * 1000,
            type,
            "",
            if (itemId.isEmpty()) null
            else
                mapOf(
                    "item" to
                        buildJsonObject {
                            put("id", itemId)
                            put("type", itemType)
                            put("status", status)
                        }
                ),
        )

    @Test
    fun `assistant key survives deltas cache restoration and isolates reused ids`() {
        val accumulator = dev.leo.manager.data.LiveAccumulator()
        val first = accumulator.append(listOf(event(1, "item.started", "answer", "agent_message")))
        val key = timelineEntries(first).single().key
        val updated = accumulator.append(listOf(event(9, "item.updated", "answer", "agent_message")))
        assertEquals(9L, updated.single().id)
        assertEquals(key, timelineEntries(updated).single().key)
        val restored = dev.leo.manager.data.LiveAccumulator()
        val encoded = dev.leo.manager.data.wireJson.encodeToString(updated.single())
        restored.restore(listOf(dev.leo.manager.data.wireJson.decodeFromString<RunEvent>(encoded)), 9)
        val resumed = restored.append(listOf(event(10, "item.completed", "answer", "agent_message")))
        assertEquals(key, timelineEntries(resumed).single().key)
        val nextTurn = restored.append(listOf(event(11, "turn.started"), event(12, "item.started", "answer", "agent_message")))
        val keys = timelineEntries(nextTurn).filter { it.message }.map { it.key }
        assertEquals(2, keys.distinct().size)
        assertEquals(key, keys.first())
    }

    @Test
    fun `tool updates stay in their original group across assistant messages`() {
        val rows =
            timelineEntries(
                listOf(
                    event(1, "item.started", "tool"),
                    event(2, "item.completed", "answer", "agent_message"),
                    event(3, "item.completed", "tool", status = "completed"),
                )
            )
        assertEquals(2, rows.size)
        assertFalse(rows[0].message)
        assertEquals(1, rows[0].events.size)
        assertEquals("completed", rows[0].events.single().item().string("status"))
        assertEquals("activity:1", rows[0].key)
        assertTrue(rows[1].message)
    }

    @Test
    fun `reused tool ids in later turns remain independent`() {
        val rows =
            timelineEntries(
                listOf(
                    event(1, "item.completed", "tool", status = "failed"),
                    event(2, "turn.started"),
                    event(3, "item.started", "tool"),
                )
            )
        assertEquals(3, rows.single().events.size)
        assertEquals("failed", rows.single().events.first().item().string("status"))
    }

    @Test
    fun `user messages separate action groups without losing errors`() {
        val rows =
            timelineEntries(
                listOf(
                    event(1, "turn.failed"),
                    event(2, "chat.user"),
                    event(3, "item.started", "tool"),
                )
            )
        assertEquals(listOf(false, true, false), rows.map { it.message })
        assertEquals("turn.failed", rows.first().events.single().type)
    }

    @Test
    fun `files stay with their response and keep latest version per message`() {
        val entries =
            timelineEntries(
                listOf(
                    event(1, "item.completed", "answer-1", "agent_message"),
                    event(3, "chat.user"),
                    event(5, "item.completed", "answer-2", "agent_message"),
                )
            )
        fun file(id: String, message: String, version: Int, time: Long) =
            dev.leo.manager.data.Deliverable(
                id,
                "run",
                messageId = message,
                key = "report",
                version = version,
                createdAt = time,
            )
        val result =
            deliveryTimeline(
                entries,
                listOf(
                    file("old", "a", 1, 500),
                    file("first", "a", 2, 900),
                    file("second", "b", 1, 4500),
                ),
            )
        assertEquals(
            listOf("message:1", "files:message:1", "message:3", "message:5", "files:message:5"),
            result.map { it.key },
        )
        assertEquals(listOf("first", "second"), result.flatMap { it.files }.map { it.id })
    }

    @Test
    fun `publication before a new user message remains before that message`() {
        val file = dev.leo.manager.data.Deliverable("file", "run", key = "report", createdAt = 500)
        val result = deliveryTimeline(timelineEntries(listOf(event(1, "chat.user"))), listOf(file))
        assertEquals("file", result.first().files.single().id)
        assertTrue(result.last().message)
        assertEquals("file", deliveryTimeline(emptyList(), listOf(file)).single().files.single().id)
    }
}
