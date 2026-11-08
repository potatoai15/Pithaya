package com.pithaya.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** A pixel-art eye drawn on a grid. Can blink, glow (recording) or have pixels orbiting it. */
public class PixelEyeView extends View {

    static final int COLS = 27;
    static final int ROWS = 15;

    private final Paint paint = new Paint();
    private boolean blink = false;
    private boolean orbit = false;
    private boolean glow = false;

    public PixelEyeView(Context c) {
        super(c);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setBlinking(boolean b) { blink = b; invalidate(); }
    public void setOrbiting(boolean o) { orbit = o; invalidate(); }
    public void setGlow(boolean g) { glow = g; invalidate(); }

    private static boolean inside(int x, int y, float open) {
        if (x < 0 || y < 0 || x >= COLS || y >= ROWS) return false;
        float nx = (x + 0.5f - COLS / 2f) / (COLS / 2f);
        float ny = (y + 0.5f - ROWS / 2f) / (ROWS / 2f);
        float a = 1f - nx * nx;
        if (a <= 0f) return false;
        float lim = open * (float) Math.pow(a, 0.9);
        return Math.abs(ny) <= lim;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void cell(Canvas c, int ox, int oy, int size, int x, int y, int color) {
        paint.setColor(color);
        float l = ox + x * size;
        float t = oy + y * size;
        c.drawRect(l, t, l + size, t + size, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();

        float open = 1f;
        if (blink) {
            long t = now % 3200;
            if (t < 2900) open = 1f;
            else if (t < 3000) open = 1f - (t - 2900) / 100f;
            else if (t < 3060) open = 0f;
            else open = (t - 3060) / 140f;
        }

        int extra = orbit ? 12 : 2;
        int size = (int) Math.floor(Math.min(
                getWidth() / (float) (COLS + extra),
                getHeight() / (float) (ROWS + extra)));
        if (size < 1) size = 1;
        int ox = (getWidth() - COLS * size) / 2;
        int oy = (getHeight() - ROWS * size) / 2;

        float pulse = glow ? 0.5f + 0.5f * (float) Math.sin(now / 260.0) : 0f;
        int outline = glow ? lerp(0xFFA50E0E, 0xFFFF2A2A, pulse) : 0xFFA50E0E;
        int irisCol = glow ? lerp(0xFFC20A0A, 0xFFFF2020, pulse) : 0xFFC20A0A;
        int ringCol = 0xFF6E0000;
        int sclera = 0xFFE2D6D0;

        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (!inside(x, y, open)) continue;
                boolean edge = !inside(x - 1, y, open) || !inside(x + 1, y, open)
                        || !inside(x, y - 1, open) || !inside(x, y + 1, open);
                int color;
                if (edge) {
                    color = outline;
                } else {
                    float dx = x + 0.5f - COLS / 2f;
                    float dy = y + 0.5f - ROWS / 2f;
                    float d2 = dx * dx + dy * dy;
                    if (x == COLS / 2 - 1 && y == ROWS / 2 - 1) color = 0xFFFFFFFF;
                    else if (d2 <= 1.7f * 1.7f) color = 0xFF000000;
                    else if (d2 <= 4.6f * 4.6f) color = (d2 > 3.8f * 3.8f) ? ringCol : irisCol;
                    else color = sclera;
                }
                cell(canvas, ox, oy, size, x, y, color);
            }
        }

        if (orbit) {
            double base = (now % 2600) / 2600.0 * Math.PI * 2;
            int n = 14;
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < n; i++) {
                    double a = base + j * Math.PI - i * 0.16;
                    int ix = (int) Math.floor(COLS / 2f + 18.0 * Math.cos(a));
                    int iy = (int) Math.floor(ROWS / 2f + 11.0 * Math.sin(a));
                    int alpha = (int) (255 * (1f - i / (float) n));
                    cell(canvas, ox, oy, size, ix, iy, (alpha << 24) | 0x00E01818);
                }
            }
        }

        if (blink || orbit || glow) postInvalidateOnAnimation();
    }
}
