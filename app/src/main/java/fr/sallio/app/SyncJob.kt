package fr.sallio.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * Background refresh through the platform JobScheduler (no WorkManager, no
 * dependency). Runs about every hour when a network is there, so the cached
 * timetable is fresh long before the bell rings.
 */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            val result = Repo.get(applicationContext).sync(manual = params.jobId == ONE_SHOT_ID)
            jobFinished(params, result == Repo.SyncResult.FAILED && params.jobId == ONE_SHOT_ID)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = params.jobId == ONE_SHOT_ID

    companion object {
        private const val PERIODIC_ID = 1
        private const val ONE_SHOT_ID = 2
        private val executor = Executors.newSingleThreadExecutor()

        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60_000L, 20 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        }

        /** "Refresh now", queued until a network shows up. */
        fun runNow(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ONE_SHOT_ID, ComponentName(context, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            )
        }

        /** A sync as soon as PRONOTE allows it again (pending ticks waiting for a login). */
        fun runLater(context: Context, delayMs: Long) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ONE_SHOT_ID, ComponentName(context, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(delayMs + 5_000L)
                    .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            )
        }

        fun cancelAll(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancelAll()
        }
    }
}

/** Re-arms the widget clock after a reboot or an app update (jobs persist on their own). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Widgets.refreshAll(context)
        if (Repo.get(context).account() != null) SyncJob.schedulePeriodic(context)
    }
}
