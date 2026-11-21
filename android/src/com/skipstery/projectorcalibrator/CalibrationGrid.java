package com.skipstery.projectorcalibrator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

final class CalibrationGrid extends View {
  private static final int GRID_COLOR = Color.rgb(25, 27, 32); // #191B20
  private static final int CROSSHAIR_COLOR = Color.rgb(51, 55, 66); // #333742
  private static final int PERIMETER_COLOR = Color.rgb(245, 166, 35); // #F5A623
  private static final int ACCENT_BRIGHT = Color.rgb(255, 184, 77); // #FFB84D
  private static final int GLOW_START = Color.argb(56, 255, 184, 77); // ~22% alpha #FFB84D
  private static final int GLOW_END = Color.argb(0, 255, 184, 77);
  private static final int MAJOR_TICK_COLOR = Color.rgb(228, 224, 216); // #E4E0D8
  private static final int MINOR_TICK_COLOR = Color.rgb(82, 86, 96); // #525660
  private static final float GLOW_DEPTH = 28f;
  private static final float CORNER_ARM = 28f;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int width = 1920, height = 1080;
  private int activeEdge = -1;
  private int cachedGlowEdge = -1, cachedGlowW = -1, cachedGlowH = -1;

  CalibrationGrid(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    glowPaint.setStyle(Paint.Style.FILL);
  }

  void dimensions(int w, int h) {
    if (width != w || height != h) {
      width = w;
      height = h;
      invalidate();
    }
  }

  void activeEdge(int edge) {
    if (activeEdge != edge) {
      activeEdge = edge;
      invalidate();
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (width <= 0 || height <= 0) return;
    canvas.save();
    canvas.scale((float) getWidth() / width, (float) getHeight() / height);

    // Pure black background for OLED TVs and projectors.
    canvas.drawColor(Color.BLACK);

    // Subtle dark neutral calibration grid every 40px.
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1f);
    paint.setColor(GRID_COLOR);
    for (int x = 0; x < width; x += 40) canvas.drawLine(x, 0, x, height, paint);
    for (int y = 0; y < height; y += 40) canvas.drawLine(0, y, width, y, paint);

    // Center crosshairs.
    paint.setColor(CROSSHAIR_COLOR);
    canvas.drawLine(width / 2f, 0, width / 2f, height, paint);
    canvas.drawLine(0, height / 2f, width, height / 2f, paint);

    // Active edge inward amber gradient glow strip.
    if (activeEdge >= 0 && activeEdge <= 3) {
      drawActiveEdgeGlow(canvas);
    }

    // Warm amber outer perimeter.
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2f);
    paint.setColor(PERIMETER_COLOR);
    canvas.drawRect(1, 1, width - 1, height - 1, paint);

    // Crisp amber corner brackets at all 4 corners.
    paint.setStrokeWidth(3f);
    paint.setColor(ACCENT_BRIGHT);
    float inset = 1.5f;
    canvas.drawLine(0, inset, CORNER_ARM, inset, paint);
    canvas.drawLine(inset, 0, inset, CORNER_ARM, paint);
    canvas.drawLine(width - CORNER_ARM, inset, width, inset, paint);
    canvas.drawLine(width - inset, 0, width - inset, CORNER_ARM, paint);
    canvas.drawLine(0, height - inset, CORNER_ARM, height - inset, paint);
    canvas.drawLine(inset, height - CORNER_ARM, inset, height, paint);
    canvas.drawLine(width - CORNER_ARM, height - inset, width, height - inset, paint);
    canvas.drawLine(width - inset, height - CORNER_ARM, width - inset, height, paint);

    // Brighter 4px amber line along the active edge.
    if (activeEdge >= 0 && activeEdge <= 3) {
      paint.setStrokeWidth(4f);
      paint.setColor(ACCENT_BRIGHT);
      switch (activeEdge) {
        case 0:
          canvas.drawLine(2f, 0, 2f, height, paint);
          break;
        case 1:
          canvas.drawLine(width - 2f, 0, width - 2f, height, paint);
          break;
        case 2:
          canvas.drawLine(0, 2f, width, 2f, paint);
          break;
        case 3:
          canvas.drawLine(0, height - 2f, width, height - 2f, paint);
          break;
        default:
          break;
      }
    }

    // Rulers count source pixels inward from each edge. Original view is 1:1.
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(17);
    paint.setTextAlign(Paint.Align.CENTER);
    for (int n = 10; n <= 160; n += 10) {
      boolean major = n % 20 == 0;
      int tick = major ? 22 : 12;
      paint.setStrokeWidth(major ? 2f : 1.5f);
      String label = major ? Integer.toString(n) : null;
      for (float y : new float[] {height * .25f, height * .75f}) {
        // Left edge (0)
        paint.setColor(major ? (activeEdge == 0 ? ACCENT_BRIGHT : MAJOR_TICK_COLOR) : MINOR_TICK_COLOR);
        canvas.drawLine(n, y - tick, n, y + tick, paint);
        if (major) verticalLabel(canvas, label, n, y - 52);

        // Right edge (1)
        paint.setColor(major ? (activeEdge == 1 ? ACCENT_BRIGHT : MAJOR_TICK_COLOR) : MINOR_TICK_COLOR);
        canvas.drawLine(width - n, y - tick, width - n, y + tick, paint);
        if (major) verticalLabel(canvas, label, width - n, y - 52);
      }
      for (float x : new float[] {width * .25f, width * .75f}) {
        // Top edge (2)
        paint.setColor(major ? (activeEdge == 2 ? ACCENT_BRIGHT : MAJOR_TICK_COLOR) : MINOR_TICK_COLOR);
        canvas.drawLine(x - tick, n, x + tick, n, paint);
        if (major) canvas.drawText(label, x + 43, n + 6, paint);

        // Bottom edge (3)
        paint.setColor(major ? (activeEdge == 3 ? ACCENT_BRIGHT : MAJOR_TICK_COLOR) : MINOR_TICK_COLOR);
        canvas.drawLine(x - tick, height - n, x + tick, height - n, paint);
        if (major) canvas.drawText(label, x + 43, height - n + 6, paint);
      }
    }
    canvas.restore();
  }

  private void drawActiveEdgeGlow(Canvas canvas) {
    if (cachedGlowEdge != activeEdge || cachedGlowW != width || cachedGlowH != height) {
      Shader shader;
      switch (activeEdge) {
        case 0:
          shader = new LinearGradient(0, 0, GLOW_DEPTH, 0, GLOW_START, GLOW_END, Shader.TileMode.CLAMP);
          break;
        case 1:
          shader = new LinearGradient(width, 0, width - GLOW_DEPTH, 0, GLOW_START, GLOW_END, Shader.TileMode.CLAMP);
          break;
        case 2:
          shader = new LinearGradient(0, 0, 0, GLOW_DEPTH, GLOW_START, GLOW_END, Shader.TileMode.CLAMP);
          break;
        case 3:
          shader = new LinearGradient(0, height, 0, height - GLOW_DEPTH, GLOW_START, GLOW_END, Shader.TileMode.CLAMP);
          break;
        default:
          shader = null;
          break;
      }
      glowPaint.setShader(shader);
      cachedGlowEdge = activeEdge;
      cachedGlowW = width;
      cachedGlowH = height;
    }
    switch (activeEdge) {
      case 0:
        canvas.drawRect(0, 0, GLOW_DEPTH, height, glowPaint);
        break;
      case 1:
        canvas.drawRect(width - GLOW_DEPTH, 0, width, height, glowPaint);
        break;
      case 2:
        canvas.drawRect(0, 0, width, GLOW_DEPTH, glowPaint);
        break;
      case 3:
        canvas.drawRect(0, height - GLOW_DEPTH, width, height, glowPaint);
        break;
      default:
        break;
    }
  }

  private void verticalLabel(Canvas canvas, String text, float x, float y) {
    canvas.save();
    canvas.translate(x, y);
    canvas.rotate(-90);
    canvas.drawText(text, 0, 5, paint);
    canvas.restore();
  }
}
