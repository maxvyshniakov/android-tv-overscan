package com.skipstery.projectorcalibrator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.Closeable;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
  private enum SetupState {
    CHECKING,
    PORT_CLOSED,
    AWAITING_AUTH,
    AUTH_NOT_COMPLETED,
    STARTING,
    FAILED
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final TextView[] rows = new TextView[4];
  private final String[] names = {"Left", "Right", "Top", "Bottom"};
  private final AtomicReference<Closeable> inFlightConnection = new AtomicReference<>();
  private Insets value = Insets.full(1920, 1080);
  private boolean original = false, active = false, reapplyOnResume = true;
  private volatile boolean destroyed = false, connecting = false;
  private volatile long nextAuthAttemptMs = 0;
  private volatile SetupState setupState = SetupState.CHECKING;
  private volatile String setupDetail = null;
  private LinearLayout panel;
  private TextView status, dimensions;
  private Button preview;
  private CalibrationGrid grid;
  private ProfileStore profiles;
  private final Runnable poll =
      new Runnable() {
        @Override
        public void run() {
          if (!active || destroyed) return;
          try {
            if (!BridgeClient.connected()) {
              renderSetupStatus();
              connectLocalAdb();
            } else {
              value = BridgeClient.transact(null, true);
              if (reapplyOnResume) {
                value = BridgeClient.transact(value, true);
                reapplyOnResume = false;
              }
              render();
              status.setVisibility(View.GONE);
            }
          } catch (Exception error) {
            showError(error);
          }
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    profiles = new ProfileStore(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    FrameLayout root = new FrameLayout(this);
    grid = new CalibrationGrid(this);
    root.addView(grid);
    panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(22), dp(16), dp(22), dp(16));
    panel.setBackground(background(Color.rgb(16, 25, 35), Color.rgb(52, 70, 84)));
    FrameLayout.LayoutParams box =
        new FrameLayout.LayoutParams(
            dp(490), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    root.addView(panel, box);
    TextView title = label("Android TV Overscan", 23);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    panel.addView(title);
    dimensions = label("Connecting to display...", 13);
    panel.addView(dimensions);
    dimensions.setPadding(0, 0, 0, dp(14));
    for (int i = 0; i < 4; i++) {
      final int edge = i;
      TextView row = label("", 17);
      rows[i] = row;
      row.setId(View.generateViewId());
      row.setTypeface(Typeface.MONOSPACE);
      row.setFocusable(true);
      row.setFocusableInTouchMode(true);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(14), 0, dp(14), 0);
      row.setBackground(background(Color.rgb(25, 37, 50), Color.TRANSPARENT));
      row.setOnFocusChangeListener(
          (view, focused) ->
              row.setBackground(
                  background(
                      focused ? Color.rgb(37, 73, 76) : Color.rgb(25, 37, 50),
                      focused ? Color.rgb(96, 242, 192) : Color.TRANSPARENT)));
      row.setOnKeyListener(
          (view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT)
              return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN)
              change(value.adjust(edge, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
            return true;
          });
      LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(39));
      layout.bottomMargin = dp(5);
      panel.addView(row, layout);
    }
    for (int i = 0; i < 4; i++) {
      rows[i].setNextFocusUpId(rows[Math.max(0, i - 1)].getId());
      if (i < 3) rows[i].setNextFocusDownId(rows[i + 1].getId());
    }
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    panel.addView(actions);
    Button onlyGrid = button(actions, "Grid only");
    onlyGrid.setOnClickListener(v -> panel.setVisibility(View.GONE));
    preview = button(actions, "Show original");
    preview.setOnClickListener(
        v -> {
          original = !original;
          apply(false);
        });
    Button reset = button(actions, "Reset");
    reset.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setTitle("Reset all margins?")
                .setMessage("This restores the full picture and saves zero margins.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                    "Reset", (d, w) -> change(Insets.full(value.width, value.height)))
                .show());
    Button done = button(actions, "Done");
    done.setBackgroundTintList(
        new ColorStateList(
            new int[][] {
              new int[] {android.R.attr.state_focused},
              new int[] {android.R.attr.state_pressed},
              new int[] {}
            },
            new int[] {
              Color.rgb(190, 255, 231), Color.rgb(155, 255, 219), Color.rgb(96, 242, 192)
            }));
    done.setTextColor(Color.rgb(9, 30, 26));
    done.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    done.setOnClickListener(
        v -> {
          original = false;
          if (apply(true)) {
            startActivity(
                new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finishAndRemoveTask();
          }
        });
    rows[3].setNextFocusDownId(done.getId());
    status = label("Run android_tv_overscan.py on your computer to connect", 12);
    status.setPadding(0, dp(7), 0, 0);
    panel.addView(status);
    setContentView(root);
    rows[0].requestFocus();
  }

  private void change(Insets next) {
    value = next;
    original = false;
    apply(true);
  }

  private boolean apply(boolean save) {
    if (!BridgeClient.connected()) {
      original = false;
      nextAuthAttemptMs = 0;
      renderSetupStatus();
      connectLocalAdb();
      return false;
    }
    boolean success = false;
    try {
      value = BridgeClient.transact(value, !original);
      if (save) profiles.save(BridgeClient.display, value);
      status.setVisibility(View.GONE);
      success = true;
    } catch (Exception error) {
      original = false;
      showError(error);
      value = BridgeClient.current;
    }
    render();
    return success;
  }

  private void connectLocalAdb() {
    if (connecting || destroyed || BridgeClient.connected()) return;
    if (System.currentTimeMillis() < nextAuthAttemptMs) return;
    connecting = true;
    new Thread(
            () -> {
              try {
                AutoRestore.Result result =
                    AutoRestore.connectLegacyInteractive(
                        getApplicationContext(),
                        60_000,
                        () -> updateSetupState(SetupState.AWAITING_AUTH, null),
                        () -> updateSetupState(SetupState.STARTING, null),
                        inFlightConnection);
                if (!result.ok) {
                  nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                  updateSetupState(SetupState.FAILED, result.message);
                }
              } catch (AdbConnection.PortClosedException closed) {
                if (AutoRestore.configured(MainActivity.this)) {
                  AutoRestore.runSoon(MainActivity.this);
                }
                updateSetupState(SetupState.PORT_CLOSED, null);
              } catch (AdbConnection.UnauthorizedException unauthorized) {
                nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                updateSetupState(SetupState.AUTH_NOT_COMPLETED, null);
              } catch (Exception error) {
                if (!destroyed) {
                  nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                  updateSetupState(SetupState.FAILED, message(error));
                }
              } finally {
                connecting = false;
                if (!destroyed) handler.post(poll);
              }
            },
            "android-tv-overscan-setup")
        .start();
  }

  private void updateSetupState(SetupState state, String detail) {
    setupState = state;
    setupDetail = detail;
    if (!destroyed) {
      handler.post(
          () -> {
            if (!destroyed && !BridgeClient.connected()) renderSetupStatus();
          });
    }
  }

  private void renderSetupStatus() {
    switch (setupState) {
      case PORT_CLOSED:
        if (AutoRestore.configured(this)) {
          dimensions.setText("Restoring display control...");
          status.setText(
              "Port 5555 is closed; restoring via Wireless debugging.\n"
                  + "For computer-free setup, enable Settings > System > Developer options > USB"
                  + " debugging.");
        } else {
          dimensions.setText("Setup required — enable USB debugging");
          status.setText(
              "Set up directly on the TV with your remote:\n"
                  + "1. Open Settings > System > About and press Android TV OS build 7 times.\n"
                  + "2. Open Settings > System > Developer options and enable USB debugging.\n"
                  + "3. Return here and approve the prompt.\n"
                  + "Or run android_tv_overscan.py on your computer.");
        }
        break;
      case AWAITING_AUTH:
        dimensions.setText("Approve USB debugging with your remote");
        status.setText(
            "On the \"Allow USB debugging?\" prompt, check \"Always allow from this computer\" and"
                + " select Allow with your remote.");
        break;
      case AUTH_NOT_COMPLETED:
        dimensions.setText("Waiting for USB debugging authorization");
        status.setText(
            "Select \"Always allow from this computer\" and Allow on the TV prompt.\n"
                + "If no prompt appears, toggle USB debugging off and on in Developer options.");
        break;
      case STARTING:
        dimensions.setText("Starting display control...");
        status.setText("USB debugging authorized. Starting display controller...");
        break;
      case FAILED:
        dimensions.setText("Could not start display control");
        status.setText(
            setupDetail != null
                ? setupDetail
                : "Check USB debugging in Developer options, or run android_tv_overscan.py on your"
                    + " computer.");
        break;
      case CHECKING:
      default:
        dimensions.setText("Connecting to display...");
        status.setText("Checking local ADB on port 5555...");
        break;
    }
    status.setVisibility(View.VISIBLE);
  }

  private void render() {
    Insets shown = original ? Insets.full(value.width, value.height) : value;
    dimensions.setText(
        "Output "
            + value.width
            + " × "
            + value.height
            + "   ·   Picture "
            + shown.contentWidth()
            + " × "
            + shown.contentHeight());
    for (int i = 0; i < 4; i++) {
      rows[i].setText(String.format(Locale.US, "%-8s        ‹ %4d px ›", names[i], shown.edge(i)));
      rows[i].setEnabled(!original);
      rows[i].setFocusable(!original);
      rows[i].setAlpha(original ? 0.5f : 1f);
    }
    preview.setText(original ? "Restore" : "Show original");
    grid.dimensions(value.width, value.height);
  }

  private String message(Exception error) {
    return error.getMessage() == null
        ? "Connection lost. Run the utility again."
        : error.getMessage();
  }

  private void showError(Exception error) {
    status.setText(message(error));
    status.setVisibility(View.VISIBLE);
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    reapplyOnResume = true;
    if (!connecting) nextAuthAttemptMs = 0;
    if (!BridgeClient.connected()) AutoRestore.schedule(this);
    handler.post(poll);
  }

  @Override
  protected void onPause() {
    // Do NOT cancel inFlightConnection here: the system "Allow USB debugging?" dialog overlays
    // MainActivity and triggers onPause() while adbd is waiting for the user's approval.
    if (original && BridgeClient.connected()) {
      original = false;
      apply(false);
    }
    active = false;
    handler.removeCallbacks(poll);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    Closeable connection = inFlightConnection.getAndSet(null);
    if (connection != null) {
      try {
        connection.close();
      } catch (IOException ignored) {
      }
    }
    super.onDestroy();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (panel.getVisibility() != View.VISIBLE && event.getAction() == KeyEvent.ACTION_DOWN) {
      int key = event.getKeyCode();
      if (key == KeyEvent.KEYCODE_BACK
          || key == KeyEvent.KEYCODE_DPAD_CENTER
          || key == KeyEvent.KEYCODE_ENTER) {
        panel.setVisibility(View.VISIBLE);
        rows[0].requestFocus();
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }

  private int dp(int number) {
    return Math.round(number * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(Color.rgb(229, 241, 247));
    return view;
  }

  private GradientDrawable background(int fill, int stroke) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(fill);
    result.setCornerRadius(dp(7));
    result.setStroke(dp(1), stroke);
    return result;
  }

  private Button button(LinearLayout parent, String title) {
    Button button = new Button(this);
    button.setId(View.generateViewId());
    button.setText(title);
    button.setTextSize(12);
    button.setAllCaps(false);
    button.setPadding(0, 0, 0, 0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    parent.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
    return button;
  }
}
