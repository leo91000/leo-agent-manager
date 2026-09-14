package dev.leo.manager.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.work.*
import dev.leo.manager.BuildConfig
import dev.leo.manager.MainActivity
import dev.leo.manager.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val QUESTION_CHANNEL = "leo-questions"
private const val QUESTION_WORK = "leo-question-check"
private val enabledKey = booleanPreferencesKey("notifications")
private val seenKey = stringSetPreferencesKey("notified_questions")

class NotificationPreferences(private val context: Context) {
    val enabled = context.dataStore.data.map { it[enabledKey] ?: false }

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[enabledKey] = value }
        schedule(context, value)
    }

    suspend fun seen() = context.dataStore.data.first()[seenKey].orEmpty()

    suspend fun setSeen(ids: Set<String>) {
        context.dataStore.edit { it[seenKey] = ids }
    }
}

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

fun schedule(context: Context, enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (enabled)
        manager.enqueueUniquePeriodicWork(
            QUESTION_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<QuestionWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    else {
        manager.cancelUniqueWork(QUESTION_WORK)
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/**
 * Fetches pending question identifiers only; answers never enter notifications or persistent work
 * data.
 */
class QuestionWorker
@JvmOverloads
constructor(
    context: Context,
    params: WorkerParameters,
    private val vault: SessionVault = KeystoreSessionVault(context),
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = NotificationPreferences(context)
        if (!prefs.enabled.first() || !notificationsAllowed(context)) return Result.success()
        val origin = Preferences(context).origin.first()
        if (origin.isBlank()) return Result.success()
        val originalCookie = vault.read(origin) ?: return Result.success()
        try {
            val api = LeoApi(serverOrigin(origin, BuildConfig.DEBUG), vault)
            val session = api.get<Session>("/session")
            if (!session.authenticated) return Result.success()
            val chats = api.get<List<Chat>>("/chats").filter { it.pendingQuestions > 0 }
            val pending = mutableMapOf<String, Set<String>>()
            for (chat in chats) {
                val detail = api.get<Chat>("/chats/${segment(chat.id)}")
                pending[chat.id] =
                    detail.questions.filter { it.status == "pending" }.map { it.id }.toSet()
            }
            // Logout, server changes and disabling notifications win over an in-flight check.
            if (
                !prefs.enabled.first() ||
                    Preferences(context).origin.first() != origin ||
                    vault.read(origin) != originalCookie
            )
                return Result.success()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    QUESTION_CHANNEL,
                    "Questions des agents",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val previous = prefs.seen()
            val all = pending.values.flatten().toSet()
            manager.activeNotifications
                .filter { it.notification.channelId == QUESTION_CHANNEL && it.tag !in pending.keys }
                .forEach { manager.cancel(it.tag, it.id) }
            for ((chat, questions) in pending) {
                if ((questions - previous).isEmpty()) continue
                val intent =
                    Intent(context, MainActivity::class.java)
                        .setAction("dev.leo.manager.OPEN_CHAT")
                        .setData(
                            "leo-manager://chat/${segment(chat)}?origin=${segment(origin)}".toUri()
                        )
                        .putExtra("chat", chat)
                        .putExtra("origin", origin)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val action =
                    PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val notification =
                    NotificationCompat.Builder(context, QUESTION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_leo)
                        .setContentTitle("Leo attend votre réponse")
                        .setContentText(
                            if (questions.size == 1)
                                "Une question vous attend dans une conversation."
                            else "${questions.size} questions vous attendent dans une conversation."
                        )
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setContentIntent(action)
                        .setAutoCancel(true)
                        .build()
                if (notificationsAllowed(context)) manager.notify(chat, 1, notification)
            }
            prefs.setSeen(all)
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return if (e.status in setOf(401, 403)) Result.success() else Result.retry()
        } catch (_: Exception) {
            return Result.retry()
        }
    }
}
