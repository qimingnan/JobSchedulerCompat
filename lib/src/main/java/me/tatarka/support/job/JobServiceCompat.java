package me.tatarka.support.job;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.support.v4.net.ConnectivityManagerCompat;

import java.util.List;

/**
 * @hide *
 */
public class JobServiceCompat extends IntentService {
    static final String TAG = "JobServiceCompat";

    private static final String EXTRA_MSG = "EXTRA_MSG";
    private static final String EXTRA_JOB = "EXTRA_JOB";
    private static final String EXTRA_JOB_ID = "EXTRA_JOB_ID";
    private static final String EXTRA_RUN_IMMEDIATELY = "EXTRA_RUN_IMMEDIATELY";
    private static final String EXTRA_START_TIME = "EXTRA_START_TIME";
    private static final String EXTRA_NUM_FAILURES = "EXTRA_NUM_FAILURES";

    private static final int MSG_SCHEDULE_JOB = 0;
    private static final int MSG_RESCHEDULE_JOB = 1;
    private static final int MSG_CANCEL_JOB = 2;
    private static final int MSG_CANCEL_ALL = 3;
    private static final int MSG_REQUIRED_STATE_CHANGED = 4;
    private static final int MSG_CHECK_JOB_READY = 5;
    private static final int MSG_JOBS_FINISHED = 6;
    private static final int MSG_BOOT = 7;

    private AlarmManager am;
    private PowerManager pm;
    private static PowerManager.WakeLock WAKE_LOCK;

    public JobServiceCompat() {
        super("JobServiceCompat");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        if (WAKE_LOCK == null) {
            WAKE_LOCK = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JobServiceCompat");
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        int msg = intent.getIntExtra(EXTRA_MSG, -1);
        switch (msg) {
            case MSG_SCHEDULE_JOB: {
                JobInfo job = intent.getParcelableExtra(EXTRA_JOB);
                handleSchedule(job);
                break;
            }
            case MSG_RESCHEDULE_JOB: {
                JobInfo job = intent.getParcelableExtra(EXTRA_JOB);
                int numFailures = intent.getIntExtra(EXTRA_NUM_FAILURES, 0);
                handleReschedule(job, numFailures);
                break;
            }
            case MSG_CANCEL_JOB: {
                int jobId = intent.getIntExtra(EXTRA_JOB_ID, 0);
                handleCancelJob(jobId);
                break;
            }
            case MSG_CANCEL_ALL: {
                handleCancelAll();
                break;
            }
            case MSG_REQUIRED_STATE_CHANGED: {
                handleRequiredStateChanged(intent);
                break;
            }
            case MSG_CHECK_JOB_READY: {
                JobInfo job = intent.getParcelableExtra(EXTRA_JOB);
                long startTime = intent.getLongExtra(EXTRA_START_TIME, 0);
                int numFailures = intent.getIntExtra(EXTRA_NUM_FAILURES, 0);
                boolean runImmediately = intent.getBooleanExtra(EXTRA_RUN_IMMEDIATELY, false);
                handleCheckJobReady(job, startTime, numFailures, runImmediately);
                break;
            }
            case MSG_BOOT: {
                handleBoot();
            }
            case MSG_JOBS_FINISHED: {
                handleJobsFinished();
            }
        }
    }

    private void handleSchedule(JobInfo job) {
        unscheduleJob(job.getId());
        JobPersister.getInstance(this).addPendingJob(job);
        scheduleJob(job);
    }

    private void scheduleJob(JobInfo job) {
        long startTime = SystemClock.elapsedRealtime();

        if (job.hasEarlyConstraint()) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, startTime + job.getMinLatencyMillis(), toPendingIntent(job, startTime, 0, false));
        } else if (job.hasLateConstraint()) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, startTime + job.getMaxExecutionDelayMillis(), toPendingIntent(job, startTime, 0, true));
        } else {
            // Just create a PendingIntent to store the job.
            toPendingIntent(job, startTime, 0, false);
        }

        if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
            ReceiverUtils.enable(this, NetworkReceiver.class);
        }

        if (job.isRequireCharging()) {
            ReceiverUtils.enable(this, PowerReceiver.class);
        }

        if (job.isPersisted()) {
            ReceiverUtils.enable(this, BootReceiver.class);
        }
    }

    private void handleReschedule(JobInfo job, int numFailures) {
        JobPersister.getInstance(this).addPendingJob(job);

        if (job.isRequireDeviceIdle()) {
            // TODO: different reschedule policy
            throw new UnsupportedOperationException("rescheduling idle tasks is not yet implemented");
        }

        long backoffTime;
        switch (job.getBackoffPolicy()) {
            case JobInfo.BACKOFF_POLICY_LINEAR:
                backoffTime = job.getInitialBackoffMillis() * numFailures;
                break;
            case JobInfo.BACKOFF_POLICY_EXPONENTIAL:
                backoffTime = job.getInitialBackoffMillis() * (long) Math.pow(2, numFailures - 1);
                break;
            default:
                throw new IllegalArgumentException("Unknown backoff policy: " + job.getBackoffPolicy());
        }

        if (backoffTime > 5 * 60 * 60 * 1000 /* 5 hours*/) {
            // We have backed-off too long, give up.
            return;
        }

        long startTime = SystemClock.elapsedRealtime();
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, startTime + backoffTime, toPendingIntent(job, startTime, numFailures, false));
    }

    private void handleCancelJob(int jobId) {
        unscheduleJob(jobId);
        JobSchedulerService.stopJob(this, jobId);
    }

    private void handleCancelAll() {
        List<JobInfo> pendingJobs = JobPersister.getInstance(this).getPendingJobs();
        for (JobInfo job : pendingJobs) {
            unscheduleJob(job.getId());
        }
        JobSchedulerService.stopAll(this);
    }

    private void handleCheckJobReady(final JobInfo job, long startTime, int numFailures, boolean runImmediately) {
        boolean hasRequiredNetwork = JobInfoUtil.hasRequiredNetwork(job, getCurrentNetworkType());
        boolean hasRequiredPowerState = JobInfoUtil.hasRequiredPowerState(job, isCurrentlyCharging());

        if (runImmediately || (hasRequiredNetwork && hasRequiredPowerState)) {
            unscheduleJob(job.getId());
            WAKE_LOCK.acquire();
            JobSchedulerService.startJob(this, job, numFailures, runImmediately);
        } else {
            if (job.hasLateConstraint()) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, startTime + job.getMaxExecutionDelayMillis(), toPendingIntent(job, startTime, numFailures, true));
            } else {
                // Ensure we have a pending intent for when required state changes.
                toPendingIntent(job, startTime, numFailures, false);
            }
        }
    }

    private void handleBoot() {
        List<JobInfo> jobs = JobPersister.getInstance(this).getPendingJobs();
        for (JobInfo job : jobs) {
            if (job.isPersisted()) {
                scheduleJob(job);
            }
        }
    }

    private void handleJobsFinished() {
        // Check if we can turn off any broadcast receivers.
        List<JobInfo> jobs = JobPersister.getInstance(this).getPendingJobs();
        boolean hasNetworkConstraint = false;
        boolean hasPowerConstraint = false;
        boolean hasBootConstraint = false;

        for (JobInfo job : jobs) {
            if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
                hasNetworkConstraint = true;
            }

            if (job.isRequireCharging()) {
                hasPowerConstraint = true;
            }

            if (job.isPersisted()) {
                hasBootConstraint = true;
            }

            if (hasNetworkConstraint && hasPowerConstraint && hasBootConstraint) {
                break;
            }
        }

        if (!hasNetworkConstraint) {
            ReceiverUtils.disable(this, NetworkReceiver.class);
        }

        if (!hasPowerConstraint) {
            ReceiverUtils.disable(this, PowerReceiver.class);
        }

        if (!hasBootConstraint) {
            ReceiverUtils.disable(this, BootReceiver.class);
        }

        // Alright we're done, you can go to sleep now.
        if (WAKE_LOCK.isHeld()) {
            WAKE_LOCK.release();
        }
    }

    private void unscheduleJob(int jobId) {
        JobPersister.getInstance(this).removePendingJob(jobId);
        PendingIntent pendingIntent = toExistingPendingIntent(jobId);
        if (pendingIntent != null) {
            am.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    private int getCurrentNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        boolean connected = netInfo != null && netInfo.isConnectedOrConnecting();
        if (!connected) {
            return JobInfo.NETWORK_TYPE_NONE;
        }

        return ConnectivityManagerCompat.isActiveNetworkMetered(cm) ?
                JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED;
    }

    private boolean isCurrentlyCharging() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        return plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB;
    }

    private void handleRequiredStateChanged(Intent intent) {
        int networkType = getCurrentNetworkType();
        boolean isCharging = isCurrentlyCharging();

        List<JobInfo> pendingJobs = JobPersister.getInstance(this).getPendingJobs();
        boolean firedServiceIntent = false;

        for (JobInfo job : pendingJobs) {
            boolean hasRequiredNetwork = JobInfoUtil.hasRequiredNetwork(job, networkType);
            boolean hasRequiredPowerState = JobInfoUtil.hasRequiredPowerState(job, isCharging);

            if (hasRequiredNetwork && hasRequiredPowerState) {
                PendingIntent pendingIntent = toExistingPendingIntent(job.getId());
                if (pendingIntent != null) {
                    try {
                        pendingIntent.send();
                        firedServiceIntent = true;
                    } catch (PendingIntent.CanceledException e) {
                        // Ignore, has already been canceled.
                    }
                }
            }
        }

        JobSchedulerService.recheckConstraints(this, getCurrentNetworkType(), isCurrentlyCharging());
        if (firedServiceIntent) {
            WAKE_LOCK.acquire();
        }
        WakefulBroadcastReceiver.completeWakefulIntent(intent);
    }

    private PendingIntent toPendingIntent(JobInfo job, long startTime, int numFailures, boolean runImmediately) {
        Intent intent = new Intent(this, JobServiceCompat.class)
                .setAction(Integer.toString(job.getId()))
                .putExtra(EXTRA_MSG, MSG_CHECK_JOB_READY)
                .putExtra(EXTRA_JOB, job)
                .putExtra(EXTRA_START_TIME, startTime)
                .putExtra(EXTRA_NUM_FAILURES, numFailures)
                .putExtra(EXTRA_RUN_IMMEDIATELY, runImmediately);
        return PendingIntent.getService(this, job.getId(), intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private PendingIntent toExistingPendingIntent(int jobId) {
        Intent intent = new Intent(this, JobServiceCompat.class)
                .setAction(Integer.toString(jobId));
        return PendingIntent.getService(this, jobId, intent, PendingIntent.FLAG_NO_CREATE);
    }

    static void schedule(Context context, JobInfo job) {
        context.startService(
                new Intent(context, JobServiceCompat.class)
                        .putExtra(EXTRA_MSG, MSG_SCHEDULE_JOB)
                        .putExtra(EXTRA_JOB, job));
    }

    static void reschedule(Context context, JobInfo job, int numFailures) {
        context.startService(
                new Intent(context, JobServiceCompat.class)
                        .putExtra(EXTRA_MSG, MSG_RESCHEDULE_JOB)
                        .putExtra(EXTRA_JOB, job)
                        .putExtra(EXTRA_NUM_FAILURES, numFailures));
    }

    static void cancel(Context context, int jobId) {
        context.startService(
                new Intent(context, JobServiceCompat.class)
                        .putExtra(EXTRA_MSG, MSG_CANCEL_JOB)
                        .putExtra(EXTRA_JOB_ID, jobId));
    }

    static void cancelAll(Context context) {
        context.startService(
                new Intent(context, JobServiceCompat.class)
                        .putExtra(EXTRA_MSG, MSG_CANCEL_ALL));
    }

    static void jobsFinished(Context context) {
        context.startService(
                new Intent(context, JobServiceCompat.class)
                        .putExtra(EXTRA_MSG, MSG_JOBS_FINISHED));
    }

    static Intent requiredStateChangedIntent(Context context) {
        return new Intent(context, JobServiceCompat.class)
                .putExtra(EXTRA_MSG, MSG_REQUIRED_STATE_CHANGED);
    }

    public static Intent bootIntent(Context context) {
        return new Intent(context, JobServiceCompat.class)
                .putExtra(EXTRA_MSG, MSG_BOOT);
    }
}
