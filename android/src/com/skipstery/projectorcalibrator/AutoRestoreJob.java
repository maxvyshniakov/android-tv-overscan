package com.skipstery.projectorcalibrator;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoRestoreJob extends JobService {
  private static final int MAX_ATTEMPTS = 8;
  private static int attempts = 0;

  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(
            () -> {
              AutoRestore.Result result = AutoRestore.run(this);
              if (result.ok && params.getJobId() == AutoRestore.NOW_JOB) AutoRestore.schedule(this);
              boolean retry;
              synchronized (AutoRestoreJob.class) {
                attempts = result.ok ? 0 : attempts + 1;
                retry =
                    result.retry
                        && params.getJobId() == AutoRestore.BOOT_JOB
                        && attempts < MAX_ATTEMPTS;
              }
              jobFinished(params, retry);
            },
            "android-tv-overscan-restore")
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return params.getJobId() == AutoRestore.BOOT_JOB;
  }

  /** Schedules a restore after boot and after the app itself is updated. */
  public static final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (Intent.ACTION_BOOT_COMPLETED.equals(action)
          || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) AutoRestore.schedule(context);
    }
  }
}
