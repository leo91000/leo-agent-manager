package dev.leo.manager.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.leo.manager.data.*
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Component timings on the JVM; these are deliberately not device frame-rate assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingCostTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `measure long streamed text through accumulator cache and native markdown`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun markdown() = Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .build()
        fun textView() = TextView(context).apply {
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            includeFontPadding = false
            setTextIsSelectable(true)
        }
        // Disk I/O and JSON are real; this isolated timing excludes hardware Keystore encryption.
        val cache = HistoryCache(directory.root, { _, bytes -> bytes }, { _, bytes -> bytes })
        for ((length, count) in listOf(2000 to 80, 20000 to 80, 100000 to 80, 100000 to 2000)) {
            val accumulator = LiveAccumulator()
            val paragraph = "## Étape\n\nUne **réponse détaillée** avec du texte, une liste et un lien.\n\n- Premier point\n- Deuxième point\n\n"
            var text = paragraph.repeat(length / paragraph.length + 1).take(length)
            var cursor = count.toLong() + 1
            fun message(id: Long, field: String, value: String) = RunEvent(
                id, id, "item.updated", if (field == "text") value else "",
                mapOf("item" to buildJsonObject {
                    put("id", "stream-message")
                    put("type", "agent_message")
                    put(field, value)
                }),
            )
            accumulator.append((1..count).map { RunEvent(it.toLong(), it.toLong(), "output", "Prior output $it") } + message(cursor, "text", text))
            val reduce = mutableListOf<Double>()
            val persist = mutableListOf<Double>()
            val merge = mutableListOf<Double>()
            val timeline = mutableListOf<Double>()
            val creation = mutableListOf<Double>()
            val render = mutableListOf<Double>()
            val layout = mutableListOf<Double>()
            val keys = mutableSetOf<String>()
            repeat(18) { sample ->
                val suffix = " suite $sample"
                text += suffix
                cursor++
                var start = System.nanoTime()
                val rows = accumulator.append(listOf(message(cursor, "delta", suffix)))
                val reduceMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                cache.save("$length-$count", CachedHistory(cursor, "v1:fixture:1", LiveState(), rows))
                val persistMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                val merged = mergeHistory(rows.dropLast(1), rows.takeLast(1))
                val mergeMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                val entries = timelineEntries(merged)
                val timelineMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                // Timeline keys currently change with each accepted event ID, so Compose
                // replaces the message subtree instead of keeping its AndroidView.
                val view = textView()
                val renderer = markdown()
                val creationMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                renderer.setMarkdown(view, text)
                val renderMs = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                view.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, 380, view.measuredHeight)
                val layoutMs = (System.nanoTime() - start) / 1e6
                assertEquals(text, rows.last().text)
                assertEquals(1, entries.count { it.message })
                if (sample >= 3) {
                    reduce.add(reduceMs)
                    persist.add(persistMs)
                    merge.add(mergeMs)
                    timeline.add(timelineMs)
                    creation.add(creationMs)
                    render.add(renderMs)
                    layout.add(layoutMs)
                    keys.add(entries.last().key)
                }
            }
            fun stats(samples: List<Double>) = buildJsonObject {
                val sorted = samples.sorted()
                put("p50_ms", sorted[sorted.size / 2])
                put("p95_ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)])
            }
            println("STREAM_PROFILE " + buildJsonObject {
                put("characters", length)
                put("prior_events", count)
                put("samples", reduce.size)
                put("message_keys", keys.size)
                put("accumulator", stats(reduce))
                put("cache_without_keystore", stats(persist))
                put("history_merge", stats(merge))
                put("timeline", stats(timeline))
                put("view_and_renderer_creation", stats(creation))
                put("markdown", stats(render))
                put("text_layout", stats(layout))
            })
        }
    }
}
