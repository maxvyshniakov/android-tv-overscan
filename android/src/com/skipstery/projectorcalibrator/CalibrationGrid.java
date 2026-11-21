package com.skipstery.projectorcalibrator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class CalibrationGrid extends View {
  private static final int GRID_COLOR = Color.rgb(25, 27, 32); // #191B20
  private static final int CROSSHAIR_COLOR = Color.rgb(51, 55, 66); // #333742
  private static final int PERIMETER_COLOR = Color.rgb(245, 166, 35); // #F5A623
  private static final int ACCENT_BRIGHT = Color.rgb(255, 184, 77); // #FFB84D
  private static final int MAJOR_TICK_COLOR = Color.rgb(228, 224, 216); // #E4E0D8
  private static final int MINOR_TICK_COLOR = Color.rgb(82, 86, 96); // #525660

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int width = 1920, height = 1080;
  private int activeEdge = -1;

  CalibrationGrid(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
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

    // Razor-sharp uniform 2px warm amber outer perimeter.
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2f);
    paint.setColor(PERIMETER_COLOR);
    canvas.drawRect(1, 1, width - 1, height - 1, paint);

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

  private void verticalLabel(Canvas canvas, String text, float x, float y) {
    canvas.save();
    canvas.translate(x, y);
    canvas.rotate(-90);
    canvas.drawText(text, 0, 5, paint);
    canvas.restore();
  }
}
