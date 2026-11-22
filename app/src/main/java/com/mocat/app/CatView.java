package com.mocat.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Iterator;

/** 悬浮层里的猫：画猫、爪印、涟漪、小红心，并处理触摸。坐标一律用屏幕坐标。 */
@SuppressLint("ViewConstructor")
public class CatView extends View {

    public interface Host {
        void onCatTap();
        void onCatDoubleTap();
        void onCatLongPress();
        void onStageTap(float x, float y);
        void onStageLongPress(float x, float y);
        void onDockDrag(float x, float y, boolean end);
        void onPet();
        void onFrame();
    }

    private static final int PAW = 0, RIPPLE = 1, HEART = 2;
    private static final long[] LIFE = {3400, 700, 1400};

    private static final class Fx {
        final int kind;
        final float x, y, a, dx;
        final long at;

        Fx(int kind, float x, float y, float a, float dx, long at) {
            this.kind = kind; this.x = x; this.y = y; this.a = a; this.dx = dx; this.at = at;
        }
    }

    final CatArt art = new CatArt();
    final CatArt.Pose pose = new CatArt.Pose();
    final float dp, catW, slop;

    float x, y, face = -1;
    boolean moving, sleeping, docked = true, tint;
    Host host;

    private float tx, ty, speed = 240;
    private Runnable onArrive;
    private long hopAt = -99999, danceAt = -99999, shakeAt = -99999;
    private long last = SystemClock.uptimeMillis();
    private final long t0 = last;
    private final ArrayList<Fx> fx = new ArrayList<>();
    private final int[] loc = new int[2];
    private float stepAcc;
    private int alt = 1;

    private final Paint fxPaint = CatArt.paint(CatArt.INK, Paint.Style.FILL);
    private final Paint ringPaint = CatArt.paint(CatArt.INK, Paint.Style.STROKE);
    private final Paint heartPaint = CatArt.paint(CatArt.SEAL, Paint.Style.FILL);
    private final Path pawPath = new Path();
    private final Path heartPath = CatArt.svg("M12 21C9 19 2 14.5 2 9C2 6 4.5 4 7 4C9 4 11 5.5 12 7C13 5.5 15 4 17 4C19.5 4 22 6 22 9C22 14.5 15 19 12 21Z");

    // touch
    private float downX, downY, lastX, lastY, moved;
    private boolean onCat, longFired, dragging;
    private long lastTapAt, petAt;
    private final Runnable longPress = () -> {
        if (moved < slop * 1.5f && !dragging) {
            longFired = true;
            if (host == null) return;
            if (onCat) host.onCatLongPress();
            else host.onStageLongPress(downX, downY);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            step();
            invalidate();
            if (busy()) postOnAnimation(this);
            else postDelayed(this, 50);
        }
    };

    public CatView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        catW = 150 * dp;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        ringPaint.setStrokeWidth(2 * dp);
        pawPath.addOval(new RectF(5, 9.3f, 15, 17.7f), Path.Direction.CW);
        pawPath.addCircle(4.3f, 8, 2, Path.Direction.CW);
        pawPath.addCircle(8.2f, 4.8f, 2, Path.Direction.CW);
        pawPath.addCircle(12, 4.8f, 2, Path.Direction.CW);
        pawPath.addCircle(15.8f, 8, 2, Path.Direction.CW);
    }

    /** 猫在猫坐标里每单位对应多少像素 */
    float unit() { return catW / 200f; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = SystemClock.uptimeMillis();
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }

    // ---------- 动作 ----------
    void walkTo(float x, float y, float speedDp, Runnable done) {
        tx = x; ty = y; speed = speedDp; onArrive = done; moving = true; sleeping = false;
    }

    void stop() { moving = false; onArrive = null; }

    void hop() { hopAt = SystemClock.uptimeMillis(); }
    void dance() { danceAt = SystemClock.uptimeMillis(); }
    void shake() { shakeAt = SystemClock.uptimeMillis(); }

    void ripple(float sx, float sy) { fx.add(new Fx(RIPPLE, sx, sy, 0, 0, SystemClock.uptimeMillis())); }

    void paw(float sx, float sy, float angle) { fx.add(new Fx(PAW, sx, sy, angle, 0, SystemClock.uptimeMillis())); }

    void hearts(int n) {
        long now = SystemClock.uptimeMillis();
        float u = unit();
        for (int i = 0; i < n; i++) {
            float hx = x + face * 40 * u + (float) (Math.random() * 30 - 15) * dp;
            fx.add(new Fx(HEART, hx, y - 110 * u, 0, (float) (Math.random() * 40 - 20) * dp, now + i * 140L));
        }
    }

    /** 让眼睛看向屏幕上的某一点 */
    void look(float sx, float sy) {
        float u = unit();
        float hx = x + face * 55 * u, hy = y - 82 * u;
        float dx = sx - hx, dy = sy - hy;
        float d = (float) Math.hypot(dx, dy);
        if (d < 1) d = 1;
        float k = Math.min(1f, d / (110 * dp));
        float lx = dx / d * 2.6f * k, ly = dy / d * 1.8f * k;
        if (face < 0) lx = -lx;
        pose.lookX = lx;
        pose.lookY = ly;
    }

    boolean hitCat(float sx, float sy) {
        float u = unit();
        return Math.abs(sx - x) < 95 * u && sy > y - 140 * u && sy < y + 14 * u;
    }

    /** 猫头顶在屏幕上的 y */
    float topY() { return y - 125 * unit() - pose.lift * unit(); }

    private boolean busy() {
        long now = SystemClock.uptimeMillis();
        return moving || !fx.isEmpty() || now - hopAt < 450 || now - danceAt < 1600 || now - shakeAt < 1800;
    }

    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        if (moving) {
            float dx = tx - x, dy = ty - y;
            float d = (float) Math.hypot(dx, dy);
            float v = speed * dp * dt;
            if (Math.abs(dx) > 1) face = dx < 0 ? -1 : 1;
            look(tx, ty);
            if (d <= v) {
                x = tx; y = ty; moving = false;
                Runnable r = onArrive;
                onArrive = null;
                if (r != null) r.run();
            } else {
                x += dx / d * v;
                y += dy / d * v;
                stepAcc += v;
                if (stepAcc > 22 * dp && !docked) {
                    stepAcc = 0;
                    alt = -alt;
                    float nx = -dy / d, ny = dx / d;
                    paw(x + nx * alt * 6 * dp, y - 2 * dp + ny * alt * 6 * dp, (float) Math.atan2(dy, dx));
                }
            }
        }
        Iterator<Fx> it = fx.iterator();
        while (it.hasNext()) {
            Fx f = it.next();
            if (now - f.at > LIFE[f.kind]) it.remove();
        }
        if (host != null) host.onFrame();
    }

    // ---------- 绘制 ----------
    @Override protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        if (tint) c.drawColor(0x2EF1EADB);
        c.save();
        c.translate(-loc[0], -loc[1]);
        long now = SystemClock.uptimeMillis();

        for (Fx f : fx) {
            float k = (now - f.at) / (float) LIFE[f.kind];
            if (k < 0) continue;
            if (f.kind == PAW) {
                float a = k < 0.08f ? 0.5f * k / 0.08f : 0.5f * (1 - (k - 0.08f) / 0.92f);
                fxPaint.setAlpha((int) (255 * Math.max(0, a)));
                c.save();
                c.translate(f.x, f.y);
                c.rotate((float) Math.toDegrees(f.a) + 90);
                float sc = 15 * dp / 20f;
                c.scale(sc, sc);
                c.translate(-10, -10);
                c.drawPath(pawPath, fxPaint);
                c.restore();
            } else if (f.kind == RIPPLE) {
                ringPaint.setAlpha((int) (255 * 0.45f * (1 - k)));
                c.drawCircle(f.x, f.y, 32 * dp * (0.15f + 0.85f * k), ringPaint);
            }
        }

        pose.t = (now - t0) / 1000f;
        pose.face = face;
        pose.walking = moving;
        pose.sleeping = sleeping;
        pose.peek = docked && !moving;
        float lift = 0, tilt = 0;
        long dh = now - hopAt, dd = now - danceAt, ds = now - shakeAt;
        if (dh < 450) lift = (float) Math.sin(Math.PI * dh / 450.0) * 24f;
        if (dd < 1600) {
            double k = dd / 800.0;
            lift = Math.max(lift, (float) Math.abs(Math.sin(Math.PI * 2 * k)) * 20f);
            tilt = -(float) Math.sin(Math.PI * 2 * k) * 14f;
        }
        if (ds < 1800) tilt = (float) Math.sin(ds / 1000.0 * Math.PI * 2 * 4.5) * 7f;
        pose.lift = lift;
        pose.tilt = tilt;
        art.draw(c, x, y, catW, pose);

        for (Fx f : fx) {
            if (f.kind != HEART) continue;
            float k = (now - f.at) / (float) LIFE[f.kind];
            if (k < 0) continue;
            heartPaint.setAlpha((int) (255 * 0.9f * (1 - k)));
            float sc = (0.6f + 0.6f * k) * 18 * dp / 24f;
            c.save();
            c.translate(f.x + f.dx * k, f.y - 70 * dp * k);
            c.scale(sc, sc);
            c.translate(-12, -12);
            c.drawPath(heartPath, heartPaint);
            c.restore();
        }
        c.restore();
    }

    // ---------- 触摸 ----------
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        float sx = e.getRawX(), sy = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = sx;
                downY = lastY = sy;
                moved = 0;
                longFired = false;
                dragging = false;
                onCat = docked || hitCat(sx, sy);
                if (!moving) look(sx, sy);
                postDelayed(longPress, 520);
                return true;
            case MotionEvent.ACTION_MOVE: {
                moved += (float) Math.hypot(sx - lastX, sy - lastY);
                lastX = sx;
                lastY = sy;
                if (!moving) look(sx, sy);
                long now = SystemClock.uptimeMillis();
                if (docked && onCat && Math.hypot(sx - downX, sy - downY) > slop) {
                    dragging = true;
                    removeCallbacks(longPress);
                    if (host != null) host.onDockDrag(sx, sy, false);
                } else if (!docked && onCat && moved > 40 * dp && now - petAt > 700) {
                    petAt = now;
                    removeCallbacks(longPress);
                    if (host != null) host.onPet();
                }
                if (moved > slop * 1.5f) removeCallbacks(longPress);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (longFired || host == null) return true;
                if (dragging) {
                    host.onDockDrag(sx, sy, true);
                    return true;
                }
                if (onCat) {
                    if (moved > 40 * dp) return true;
                    long now = SystemClock.uptimeMillis();
                    if (!docked && now - lastTapAt < 320) {
                        lastTapAt = 0;
                        host.onCatDoubleTap();
                    } else {
                        lastTapAt = now;
                        host.onCatTap();
                    }
                } else if (moved < slop * 2) {
                    host.onStageTap(sx, sy);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
