package com.mocat.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** 首页上的小猫：会眨眼、甩尾巴，摸它会跳一下。 */
@SuppressLint("ViewConstructor")
public class PreviewView extends View {
    private final CatArt art = new CatArt();
    private final CatArt.Pose pose = new CatArt.Pose();
    private final long t0 = SystemClock.uptimeMillis();
    private long hopAt = -99999;
    private Runnable onTap;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            postDelayed(this, 33);
        }
    };

    public PreviewView(Context c) { super(c); }

    void setOnTap(Runnable r) { onTap = r; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float w = Math.min(getWidth() * 0.62f, getHeight() * 1.05f);
        float cx = getWidth() / 2f, feet = getHeight() - w * 0.08f;
        pose.t = (now - t0) / 1000f;
        pose.face = 1;
        long dh = now - hopAt;
        pose.lift = dh < 450 ? (float) Math.sin(Math.PI * dh / 450.0) * 24f : 0f;
        art.draw(c, cx, feet, w, pose);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        float w = Math.min(getWidth() * 0.62f, getHeight() * 1.05f);
        float s = w / 200f, cx = getWidth() / 2f, hx = cx + 55 * s, hy = getHeight() - w * 0.08f - 82 * s;
        float dx = e.getX() - hx, dy = e.getY() - hy;
        float d = Math.max(1f, (float) Math.hypot(dx, dy));
        pose.lookX = dx / d * 2.6f;
        pose.lookY = dy / d * 1.8f;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            hopAt = SystemClock.uptimeMillis();
            if (onTap != null) onTap.run();
        }
        return true;
    }
}
