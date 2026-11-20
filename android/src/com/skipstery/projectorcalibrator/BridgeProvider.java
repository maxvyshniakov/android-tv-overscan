package com.skipstery.projectorcalibrator;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public final class BridgeProvider extends ContentProvider {
  private final Binder lifetime = new Binder();

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (Binder.getCallingUid() != 2000)
      throw new SecurityException("Only authorized ADB shell may bootstrap calibration");
    Bundle reply = new Bundle();
    if ("status".equals(method)) {
      boolean ready = false;
      try {
        if (BridgeClient.connected()) {
          BridgeClient.transact(null, true);
          ready = true;
        }
      } catch (Exception ignored) {
      }
      reply.putBoolean("ready", ready);
      reply.putInt("protocol", Protocol.VERSION);
      reply.putBoolean("auto_restore", AutoRestore.configured(getContext()));
      return reply;
    }
    if ("autoRestoreStatus".equals(method)) {
      SharedPreferences prefs = AutoRestore.prefs(getContext());
      reply.putBoolean("configured", AutoRestore.configured(getContext()));
      reply.putBoolean(
          "permission",
          getContext().checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
              == PackageManager.PERMISSION_GRANTED);
      reply.putBoolean("ok", prefs.getBoolean("ok", false));
      reply.putLong("time", prefs.getLong("time", 0));
      reply.putString("result", prefs.getString("result", "never run"));
      return reply;
    }
    if ("autoRestoreNow".equals(method)) {
      // Runs as a job: Android 14 freezes a cached app process as soon as this call returns,
      // which would stall a plain background thread. The launcher polls autoRestoreStatus.
      reply.putLong("started", System.currentTimeMillis());
      AutoRestore.runSoon(getContext());
      return reply;
    }
    if ("autoRestoreDisable".equals(method)) {
      AutoRestore.cancel(getContext());
      AdbKey.delete(getContext());
      reply.putBoolean("configured", false);
      return reply;
    }
    if (!"attach".equals(method) || extras == null)
      throw new IllegalArgumentException("Unknown bootstrap operation");
    if (extras.getInt("protocol") != Protocol.VERSION)
      throw new IllegalArgumentException("Incompatible bridge version");
    IBinder control = extras.getBinder("control");
    if (control == null || !control.isBinderAlive())
      throw new IllegalArgumentException("Missing control binder");
    String display = extras.getString("display");
    if (display == null || display.length() > 100)
      throw new IllegalArgumentException("Invalid display identifier");
    Insets initial = Protocol.read(extras);
    Insets saved = new ProfileStore(getContext()).load(display, initial);
    BridgeClient.attach(control, display, saved);
    reply.putBoolean("attached", true);
    reply.putInt("app_uid", Process.myUid());
    reply.putBinder("lifetime", lifetime);
    Protocol.write(reply, saved);
    return reply;
  }

  /**
   * Accepts the paired ADB client key from {@code content write --uri .../adbkey}, which streams
   * it over stdin so it never appears in a command line.
   */
  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (Binder.getCallingUid() != 2000)
      throw new SecurityException("Only authorized ADB shell may import the ADB key");
    if (!"/adbkey".equals(uri.getPath()) || !mode.startsWith("w"))
      throw new FileNotFoundException("Unsupported bridge file");
    ParcelFileDescriptor[] pipe;
    try {
      pipe = ParcelFileDescriptor.createReliablePipe();
    } catch (IOException e) {
      throw new FileNotFoundException(e.getMessage());
    }
    ParcelFileDescriptor source = pipe[0];
    new Thread(
            () -> {
              try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(source)) {
                ByteArrayOutputStream pem = new ByteArrayOutputStream();
                byte[] chunk = new byte[2048];
                int n;
                while ((n = in.read(chunk)) > 0) {
                  pem.write(chunk, 0, n);
                  if (pem.size() > AdbKey.MAX_PEM_BYTES)
                    throw new IOException("ADB key is too large");
                }
                AdbKey.store(getContext(), pem.toByteArray());
                source.close();
              } catch (Exception error) {
                try {
                  source.closeWithError(String.valueOf(error.getMessage()));
                } catch (IOException ignored) {
                }
              }
            },
            "android-tv-overscan-key-import")
        .start();
    return pipe[1];
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType(Uri u) {
    return null;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
