package ai.forge.app.academy

import ai.forge.academy.MorningBriefing
import ai.forge.app.ForgeApplication
import ai.forge.app.MainActivity
import ai.forge.memory.MemoryKind
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Briefing quotidien (WorkManager) : notification discrète, calculée sur le téléphone. */
class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ForgeApplication).container
        val due = c.academy.courses().sumOf { c.academy.due(it.id).size }
        val projects = c.memory.list().filter { it.kind == MemoryKind.PROJECT }.take(2).map { it.text }
        val script = c.scripts.all().firstOrNull()?.let { it.script.titles.firstOrNull() ?: it.topic }
        val (title, body) = MorningBriefing.compose(MorningBriefing.Inputs(projects, due, script, LocalTime.now().hour)) ?: return Result.success()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Briefing du matin", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 3, Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PROMPT, MorningBriefing.PROMPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(88, NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true).build())
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "forge_briefing"; private const val NAME = "morning-briefing"
        /** Tous les jours vers 7 h 30. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(7, 30); if (!next.isAfter(now)) next = next.plusDays(1)
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BriefingWorker>(1, TimeUnit.DAYS).setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build())
        }
    }
}
