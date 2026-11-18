package ai.forge.app.library

import ai.forge.app.ForgeApplication
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Indexation en arrière-plan (WorkManager) : continue même si vous quittez Forge, avec une
 * notification de progression. Les fichiers restent sur le téléphone.
 */
class LibraryIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse).orEmpty()
        val container = (applicationContext as ForgeApplication).container
        uris.forEachIndexed { i, uri ->
            val name = applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "fichier"
            setForeground(info("Indexation ${i + 1}/${uris.size} : $name"))
            runCatching { container.library.indexLocal(applicationContext.contentResolver, uri, name) { p, n -> } }
        }
        container.refreshTools()
        return Result.success()
    }

    override suspend fun getForegroundInfo() = info("Indexation de la bibliothèque…")

    private fun info(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bibliothèque", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Forge").setContentText(text).setOngoing(true).setProgress(0, 0, true).build()
        return ForegroundInfo(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val KEY_URIS = "uris"; private const val CHANNEL = "forge_library"
        fun enqueue(context: Context, uris: List<Uri>) {
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            WorkManager.getInstance(context).enqueueUniqueWork("library-index", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<LibraryIndexWorker>().setInputData(workDataOf(KEY_URIS to uris.map { it.toString() }.toTypedArray())).build())
        }
    }
}
