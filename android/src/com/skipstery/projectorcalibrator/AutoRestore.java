package com.skipstery.projectorcalibrator;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Process;
import android.provider.Settings;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Restarts the privileged display helper without a computer. The app connects to the streamer's
 * own adbd through Wireless debugging with a key paired once by the launcher, and runs the same
 * helper command as android_tv_overscan.py.
 */
final class AutoRestore {
  static final int BOOT_JOB = 41, DAILY_JOB = 42, NOW_JOB = 43;
  static final String LOG = "/data/local/tmp/android-tv-overscan-bridge.log";
  private static final String WIFI_SETTING = "adb_wifi_enabled";
  private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
  private static final Object LOCK = new Object();

  static final class Result {
    final boolean ok, retry;
    final String message;

    Result(boolean ok, boolean retry, String message) {
      this.ok = ok;
      this.retry = retry;
      this.message = message;
    }
  }

  static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("auto_restore", Context.MODE_PRIVATE);
  }

  static boolean configured(Context context) {
    return AdbKey.exists(context);
  }

  static void schedule(Context context) {
    if (!configured(context)) return;
    JobScheduler jobs = context.getSystemService(JobScheduler.class);
    ComponentName service = new ComponentName(context, AutoRestoreJob.class);
    // Rescheduling a running job would stop it, so leave a pending restore alone.
    if (jobs.getPendingJob(BOOT_JOB) == null)
      jobs.schedule(
        new JobInfo.Builder(BOOT_JOB, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(3000)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build());
    if (jobs.getPendingJob(DAILY_JOB) == null)
      jobs.schedule(
          new JobInfo.Builder(DAILY_JOB, service)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
              .setPersisted(true)
              .build());
  }

  /** Runs one restore attempt immediately, for the launcher's setup check. */
  static void runSoon(Context context) {
    context
        .getSystemService(JobScheduler.class)
        .schedule(
            new JobInfo.Builder(NOW_JOB, new ComponentName(context, AutoRestoreJob.class))
                .setOverrideDeadline(0)
                .build());
  }

  static void cancel(Context context) {
    JobScheduler jobs = context.getSystemService(JobScheduler.class);
    jobs.cancel(BOOT_JOB);
    jobs.cancel(DAILY_JOB);
  }

  /**
   * Ensures the helper runs. A successful connection also refreshes adbd's last-use time for
   * this key, so Android's ADB authorization timeout does not revoke it while the TV stays on.
   */
  static Result run(Context context) {
    synchronized (LOCK) {
      Result result = attempt(context);
      prefs(context)
          .edit()
          .putLong("time", System.currentTimeMillis())
          .putBoolean("ok", result.ok)
          .putString("result", result.message)
          .apply();
      return result;
    }
  }

  private static Result attempt(Context context) {
    AdbKey key;
    try {
      if (!configured(context)) return new Result(false, false, "not configured");
      key = AdbKey.load(context);
    } catch (Exception error) {
      return new Result(false, false, "stored ADB key is unusable: " + error);
    }
    try {
      if (Settings.Global.getInt(context.getContentResolver(), WIFI_SETTING, 0) != 1)
        Settings.Global.putInt(context.getContentResolver(), WIFI_SETTING, 1);
    } catch (SecurityException error) {
      return new Result(false, false, "WRITE_SECURE_SETTINGS was not granted; rerun the utility");
    }
    List<Integer> ports = discoverPorts(context, 30_000);
    if (ports.isEmpty())
      return new Result(
          false, true, "Wireless debugging did not start; check Wi-Fi and Developer options");
    Exception last = null;
    List<Integer> attempts = new ArrayList<>();
    // adbd occasionally drops a fresh TLS session (seen right after pairing): try each port 3 times.
    for (int port : ports) {
      attempts.add(port);
      attempts.add(port);
      attempts.add(port);
    }
    for (int port : attempts) {
      if (last != null) {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      try (AdbConnection adb = AdbConnection.open(port, key.privateKey, key.certificate, 10_000)) {
        prefs(context).edit().putInt("port", port).apply();
        if (BridgeClient.connected()) {
          adb.shell("true");
          return new Result(true, false, "helper already running; key refreshed on port " + port);
        }
        // The launcher may be starting a helper right now; do not start a second one.
        if (adb.shell("pidof android-tv-overscan-bridge").trim().isEmpty())
          adb.shell(helperCommand(context));
        for (int i = 0; i < 30 && !BridgeClient.connected(); i++) Thread.sleep(500);
        if (!BridgeClient.connected()) {
          String log = adb.shell("tail -n 20 " + LOG);
          return new Result(false, true, "helper did not attach:\n" + log);
        }
        return new Result(true, false, "display control restored via port " + port);
      } catch (Exception error) {
        last = error;
        if (prefs(context).getInt("port", 0) == port) prefs(context).edit().remove("port").apply();
      }
    }
    String reason = String.valueOf(last);
    String lower = reason.toLowerCase(java.util.Locale.ROOT);
    boolean rejected = lower.contains("certificate") || lower.contains("accept this app's key");
    return new Result(
        false,
        !rejected,
        rejected
            ? "ADB no longer trusts this app (authorization expired?); rerun the utility: " + reason
            : "could not connect to Wireless debugging: " + reason);
  }

  static String helperCommand(Context context) {
    String apk = context.getApplicationInfo().sourceDir;
    if (apk == null || !apk.matches("/[A-Za-z0-9._/=+~-]+\\.apk"))
      throw new IllegalStateException("Unexpected APK path");
    int user = Process.myUid() / 100000;
    return "CLASSPATH="
        + apk
        + " nohup app_process /system/bin --nice-name=android-tv-overscan-bridge "
        + Protocol.PACKAGE
        + ".ShellBridge "
        + user
        + " > "
        + LOG
        + " 2>&1 < /dev/null &"
        // A legacy shell: session that closes while app_process is still starting kills it,
        // so keep the session open until the helper reports READY (at most 15 s).
        + " i=0; while [ $i -lt 30 ] && ! grep -q READY "
        + LOG
        + "; do sleep 0.5; i=$((i+1)); done";
  }

  /** Finds this device's Wireless debugging port via local DNS-SD, last known port first. */
  private static List<Integer> discoverPorts(Context context, long timeoutMs) {
    Set<Integer> candidates = new LinkedHashSet<>();
    long deadline = System.currentTimeMillis() + timeoutMs;
    NsdManager nsd = context.getSystemService(NsdManager.class);
    List<NsdServiceInfo> found = new ArrayList<>();
    CountDownLatch first = new CountDownLatch(1);
    NsdManager.DiscoveryListener listener =
        new NsdManager.DiscoveryListener() {
          @Override
          public void onServiceFound(NsdServiceInfo info) {
            synchronized (found) {
              found.add(info);
            }
            first.countDown();
          }

          @Override
          public void onServiceLost(NsdServiceInfo info) {}

          @Override
          public void onDiscoveryStarted(String type) {}

          @Override
          public void onDiscoveryStopped(String type) {}

          @Override
          public void onStartDiscoveryFailed(String type, int code) {
            first.countDown();
          }

          @Override
          public void onStopDiscoveryFailed(String type, int code) {}
        };
    try {
      int last = prefs(context).getInt("port", 0);
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
      while (candidates.isEmpty() && System.currentTimeMillis() < deadline) {
        if (last > 0 && listening(last)) candidates.add(last);
        first.await(2, TimeUnit.SECONDS);
        List<NsdServiceInfo> batch;
        synchronized (found) {
          batch = new ArrayList<>(found);
          found.clear();
        }
        for (NsdServiceInfo info : batch) {
          int port = resolve(nsd, info);
          // adbd listens on all interfaces; a port open on loopback belongs to this device.
          if (port > 0 && listening(port)) candidates.add(port);
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        nsd.stopServiceDiscovery(listener);
      } catch (RuntimeException ignored) {
      }
    }
    return new ArrayList<>(candidates);
  }

  @SuppressWarnings("deprecation")
  private static int resolve(NsdManager nsd, NsdServiceInfo info) throws InterruptedException {
    int[] port = {0};
    CountDownLatch done = new CountDownLatch(1);
    nsd.resolveService(
        info,
        new NsdManager.ResolveListener() {
          @Override
          public void onResolveFailed(NsdServiceInfo service, int code) {
            done.countDown();
          }

          @Override
          public void onServiceResolved(NsdServiceInfo service) {
            port[0] = service.getPort();
            done.countDown();
          }
        });
    done.await(5, TimeUnit.SECONDS);
    return port[0];
  }

  private static boolean listening(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
      return true;
    } catch (Exception error) {
      return false;
    }
  }
}
