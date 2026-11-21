package com.skipstery.projectorcalibrator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
  private static final int COLOR_PANEL_BG = Color.rgb(14, 14, 18); // #0E0E12
  private static final int COLOR_PANEL_BORDER = Color.rgb(43, 45, 54); // #2B2D36
  private static final int COLOR_TEXT_TITLE = Color.rgb(245, 243, 238); // #F5F3EE
  private static final int COLOR_TEXT_MUTED = Color.rgb(168, 171, 182); // #A8ABB6
  private static final int COLOR_TEXT_HINT = Color.rgb(122, 126, 138); // #7A7E8A
  private static final int COLOR_TEXT_ROW = Color.rgb(220, 218, 212);
  private static final int COLOR_TEXT_BUTTON = Color.rgb(236, 234, 228); // #ECEAE4
  private static final int COLOR_TEXT_PRIMARY_BTN = Color.rgb(18, 12, 4); // #120C04
  private static final int COLOR_ROW_BG = Color.rgb(23, 24, 30); // #17181E
  private static final int COLOR_ROW_FOCUS_BG = Color.rgb(44, 32, 16); // #2C2010
  private static final int COLOR_AMBER = Color.rgb(245, 166, 35); // #F5A623
  private static final int COLOR_AMBER_BRIGHT = Color.rgb(255, 184, 77); // #FFB84D
  private static final int COLOR_AMBER_FOCUS_FILL = Color.rgb(255, 209, 117); // #FFD175
  private static final int COLOR_AMBER_FOCUS_BORDER = Color.rgb(255, 242, 209); // #FFF2D1
  private static final int COLOR_BTN_BG = Color.rgb(27, 28, 36); // #1B1C24
  private static final int COLOR_BTN_BORDER = Color.rgb(52, 55, 69); // #343745
  private static final int COLOR_BTN_FOCUS_BG = Color.rgb(46, 34, 18); // #2E2212
  private static final int COLOR_STATUS_BG = Color.rgb(22, 20, 18); // #161412
  private static final int COLOR_STATUS_BORDER = Color.rgb(107, 74, 22); // #6B4A16
  private static final int COLOR_STATUS_TEXT = Color.rgb(230, 216, 194);
  private static final int COLOR_BADGE_ACTIVE_BG = Color.rgb(40, 29, 12);
  private static final int COLOR_BADGE_PREVIEW_BG = Color.rgb(56, 38, 14);
  private static final int COLOR_BADGE_SETUP_BG = Color.rgb(26, 24, 22);

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
  private volatile boolean wirelessFallbackTriggered = false, awaitingManualRetry = false;
  private volatile long nextAuthAttemptMs = 0;
  private volatile SetupState setupState = SetupState.CHECKING;
  private volatile String setupDetail = null;
  private LinearLayout panel;
  private TextView status, dimensions, badge;
  private Button preview;
  private CalibrationGrid grid;
  private ProfileStore profiles;
  private final Runnable authGraceTimeout =
      () -> {
        if (!destroyed && connecting && setupState == SetupState.AWAITING_AUTH) {
          awaitingManualRetry = true;
          cancelInFlightConnection();
          updateSetupState(SetupState.AUTH_NOT_COMPLETED, null);
        }
      };
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
              handler.removeCallbacks(authGraceTimeout);
              cancelInFlightConnection();
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
          if (active && !destroyed) {
            handler.removeCallbacks(this);
            handler.postDelayed(this, 1000);
          }
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
    panel.setPadding(dp(22), dp(18), dp(22), dp(18));
    panel.setBackground(roundedRect(COLOR_PANEL_BG, COLOR_PANEL_BORDER, 1, 14));
    FrameLayout.LayoutParams box =
        new FrameLayout.LayoutParams(
            dp(510), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    root.addView(panel, box);

    // Header row: title on left, compact status badge pill on right.
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    panel.addView(
        header,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    TextView title = label("Android TV Overscan", 22, COLOR_TEXT_TITLE);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    badge = label("Setup", 11, COLOR_AMBER_BRIGHT);
    badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    badge.setPadding(dp(10), dp(3), dp(10), dp(3));
    badge.setGravity(Gravity.CENTER);
    styleBadge("Setup", COLOR_BADGE_SETUP_BG, COLOR_STATUS_BORDER, COLOR_AMBER_BRIGHT);
    header.addView(badge);

    dimensions = label("Connecting to display...", 13, COLOR_TEXT_MUTED);
    dimensions.setTypeface(Typeface.MONOSPACE);
    dimensions.setPadding(0, dp(4), 0, dp(14));
    panel.addView(dimensions);

    for (int i = 0; i < 4; i++) {
      final int edge = i;
      TextView row = label("", 17, COLOR_TEXT_ROW);
      rows[i] = row;
      row.setId(View.generateViewId());
      row.setTypeface(Typeface.MONOSPACE);
      row.setFocusable(true);
      row.setFocusableInTouchMode(true);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(14), 0, dp(14), 0);
      styleRow(row, false);
      row.setOnFocusChangeListener(
          (view, focused) -> {
            styleRow(row, focused);
            if (focused) {
              grid.activeEdge(edge);
            } else if (!anyRowFocused()) {
              grid.activeEdge(-1);
            }
          });
      row.setOnKeyListener(
          (view, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT)
              return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN)
              change(value.adjust(edge, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
            return true;
          });
      LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(40));
      layout.bottomMargin = dp(6);
      panel.addView(row, layout);
    }
    for (int i = 0; i < 4; i++) {
      rows[i].setNextFocusUpId(rows[Math.max(0, i - 1)].getId());
      if (i < 3) rows[i].setNextFocusDownId(rows[i + 1].getId());
    }

    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams actionsLayout =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    actionsLayout.topMargin = dp(6);
    panel.addView(actions, actionsLayout);

    Button onlyGrid = button(actions, "Grid only", false, true);
    onlyGrid.setOnClickListener(
        v -> {
          panel.setVisibility(View.GONE);
          grid.activeEdge(-1);
        });
    preview = button(actions, "Show original", false, true);
    preview.setOnClickListener(
        v -> {
          original = !original;
          apply(false);
        });
    Button reset = button(actions, "Reset", false, true);
    reset.setOnClickListener(
        v ->
            new AlertDialog.Builder(this)
                .setTitle("Reset all margins?")
                .setMessage("This restores the full picture and saves zero margins.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                    "Reset", (d, w) -> change(Insets.full(value.width, value.height)))
                .show());
    Button done = button(actions, "Done", true, false);
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

    status = label("Checking local ADB on port 5555...", 12, COLOR_STATUS_TEXT);
    status.setBackground(roundedRect(COLOR_STATUS_BG, COLOR_STATUS_BORDER, 1, 8));
    status.setPadding(dp(12), dp(9), dp(12), dp(9));
    status.setLineSpacing(dp(2), 1f);
    LinearLayout.LayoutParams statusLayout =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    statusLayout.topMargin = dp(10);
    panel.addView(status, statusLayout);

    TextView hint = label("▲▼ Select edge  ·  ◄► Adjust 1 px", 11, COLOR_TEXT_HINT);
    hint.setGravity(Gravity.CENTER_HORIZONTAL);
    hint.setPadding(0, dp(10), 0, 0);
    panel.addView(hint);

    setContentView(root);
    rows[0].requestFocus();
  }

  private boolean anyRowFocused() {
    for (TextView row : rows) {
      if (row != null && row.isFocused()) return true;
    }
    return false;
  }

  private void styleRow(TextView row, boolean focused) {
    row.setBackground(
        roundedRect(
            focused ? COLOR_ROW_FOCUS_BG : COLOR_ROW_BG,
            focused ? COLOR_AMBER : Color.TRANSPARENT,
            2,
            8));
    row.setTextColor(focused ? Color.WHITE : COLOR_TEXT_ROW);
  }

  private void styleBadge(String text, int fill, int border, int textColor) {
    badge.setText(text);
    badge.setTextColor(textColor);
    badge.setBackground(roundedRect(fill, border, 1, 999));
  }

  private void change(Insets next) {
    value = next;
    original = false;
    apply(true);
  }

  private boolean apply(boolean save) {
    if (!BridgeClient.connected()) {
      original = false;
      retryLocalAdb();
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

  private void retryLocalAdb() {
    awaitingManualRetry = false;
    nextAuthAttemptMs = 0;
    if (!connecting) {
      setupState = SetupState.CHECKING;
      setupDetail = null;
    }
    renderSetupStatus();
    connectLocalAdb();
  }

  private void cancelInFlightConnection() {
    AutoRestore.cancel(inFlightConnection);
  }

  private void connectLocalAdb() {
    if (connecting || destroyed || awaitingManualRetry || BridgeClient.connected()) return;
    if (System.currentTimeMillis() < nextAuthAttemptMs) return;
    connecting = true;
    inFlightConnection.set(null);
    AutoRestore.registerInteractive(inFlightConnection);
    new Thread(
            () -> {
              try {
                AutoRestore.Result result =
                    AutoRestore.connectLegacyInteractive(
                        getApplicationContext(),
                        60_000,
                        () -> updateSetupState(SetupState.AWAITING_AUTH, null),
                        () -> {
                          handler.removeCallbacks(authGraceTimeout);
                          updateSetupState(SetupState.STARTING, null);
                        },
                        inFlightConnection);
                if (!result.ok && !destroyed && !BridgeClient.connected()) {
                  nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                  updateSetupState(SetupState.FAILED, result.message);
                }
              } catch (AdbConnection.PortClosedException closed) {
                if (!destroyed && !BridgeClient.connected()) {
                  nextAuthAttemptMs = System.currentTimeMillis() + 3000;
                  if (!wirelessFallbackTriggered && AutoRestore.configured(MainActivity.this)) {
                    wirelessFallbackTriggered = true;
                    AutoRestore.runSoon(MainActivity.this, false);
                  }
                  updateSetupState(SetupState.PORT_CLOSED, null);
                }
              } catch (AdbConnection.UnauthorizedException unauthorized) {
                if (!destroyed && !BridgeClient.connected()) {
                  if (unauthorized.prompted) awaitingManualRetry = true;
                  nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                  updateSetupState(SetupState.AUTH_NOT_COMPLETED, null);
                }
              } catch (Exception error) {
                if (!destroyed && !BridgeClient.connected()) {
                  if (inFlightConnection.get() == AdbConnection.CANCELLED
                      && setupState == SetupState.AUTH_NOT_COMPLETED) {
                    // Cancelled by authGraceTimeout after dialog dismissal; keep AUTH_NOT_COMPLETED.
                  } else if (inFlightConnection.get() == AdbConnection.CANCELLED
                      && !AutoRestore.configured(MainActivity.this)) {
                    nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                  } else {
                    nextAuthAttemptMs = System.currentTimeMillis() + 4000;
                    updateSetupState(SetupState.FAILED, message(error));
                  }
                }
              } finally {
                connecting = false;
                if (!destroyed) {
                  handler.removeCallbacks(poll);
                  handler.post(poll);
                }
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
    styleBadge("Setup", COLOR_BADGE_SETUP_BG, COLOR_STATUS_BORDER, COLOR_AMBER_BRIGHT);
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
                + "Press OK on your remote to try again, or toggle USB debugging in Developer"
                + " options.");
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
    if (original) {
      styleBadge("Preview", COLOR_BADGE_PREVIEW_BG, COLOR_AMBER_BRIGHT, COLOR_AMBER_FOCUS_BORDER);
      grid.activeEdge(-1);
    } else {
      styleBadge("Active", COLOR_BADGE_ACTIVE_BG, COLOR_AMBER, COLOR_AMBER_BRIGHT);
    }
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
    if (!BridgeClient.connected()) {
      styleBadge("Setup", COLOR_BADGE_SETUP_BG, COLOR_STATUS_BORDER, COLOR_AMBER_BRIGHT);
    }
    status.setText(message(error));
    status.setVisibility(View.VISIBLE);
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    reapplyOnResume = true;
    wirelessFallbackTriggered = false;
    if (!connecting) {
      awaitingManualRetry = false;
      nextAuthAttemptMs = 0;
    } else if (setupState == SetupState.AWAITING_AUTH) {
      handler.removeCallbacks(authGraceTimeout);
      handler.postDelayed(authGraceTimeout, 2500);
    }
    if (!BridgeClient.connected()) AutoRestore.schedule(this);
    handler.removeCallbacks(poll);
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
    handler.removeCallbacks(authGraceTimeout);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    cancelInFlightConnection();
    AutoRestore.unregisterInteractive(inFlightConnection);
    super.onDestroy();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      int key = event.getKeyCode();
      if (panel.getVisibility() != View.VISIBLE) {
        if (key == KeyEvent.KEYCODE_BACK
            || key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER) {
          panel.setVisibility(View.VISIBLE);
          rows[0].requestFocus();
          return true;
        }
      } else if (!BridgeClient.connected()
          && !connecting
          && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)
          && !(getCurrentFocus() instanceof Button)) {
        retryLocalAdb();
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
  }

  private int dp(int number) {
    return Math.round(number * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private GradientDrawable roundedRect(int fill, int stroke, int strokeDp, int radiusDp) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(fill);
    result.setCornerRadius(dp(radiusDp));
    result.setStroke(dp(strokeDp), stroke);
    return result;
  }

  private StateListDrawable buttonDrawable(boolean primary) {
    StateListDrawable states = new StateListDrawable();
    GradientDrawable focused =
        primary
            ? roundedRect(COLOR_AMBER_FOCUS_FILL, COLOR_AMBER_FOCUS_BORDER, 2, 8)
            : roundedRect(COLOR_BTN_FOCUS_BG, COLOR_AMBER_BRIGHT, 2, 8);
    GradientDrawable normal =
        primary
            ? roundedRect(COLOR_AMBER, COLOR_AMBER, 1, 8)
            : roundedRect(COLOR_BTN_BG, COLOR_BTN_BORDER, 1, 8);
    states.addState(new int[] {android.R.attr.state_focused}, focused);
    states.addState(new int[] {android.R.attr.state_pressed}, focused);
    states.addState(new int[] {}, normal);
    return states;
  }

  private Button button(LinearLayout parent, String title, boolean primary, boolean endGap) {
    Button button = new Button(this);
    button.setId(View.generateViewId());
    button.setText(title);
    button.setTextSize(12);
    button.setAllCaps(false);
    button.setSingleLine(true);
    button.setPadding(dp(6), 0, dp(6), 0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setStateListAnimator(null);
    button.setElevation(0f);
    button.setBackground(buttonDrawable(primary));
    button.setTextColor(primary ? COLOR_TEXT_PRIMARY_BTN : COLOR_TEXT_BUTTON);
    if (primary) button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    button.setOnFocusChangeListener(
        (v, focused) -> {
          if (focused) grid.activeEdge(-1);
        });
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
    if (endGap) params.rightMargin = dp(6);
    parent.addView(button, params);
    return button;
  }
}
