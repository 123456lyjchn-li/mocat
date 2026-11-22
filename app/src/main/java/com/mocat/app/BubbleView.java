package com.mocat.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/** 宣纸色的对话气泡，带一个指向猫的小尖角。 */
@SuppressLint("ViewConstructor")
public class BubbleView extends View {
    private final float dp;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = CatArt.paint(CatArt.CARD, Paint.Style.FILL);
    private final Paint stroke = CatArt.paint(CatArt.INK, Paint.Style.STROKE);
    private final Paint shadow = CatArt.paint(0x241D1B16, Paint.Style.FILL);
    private final RectF r = new RectF();
    private final Path tail = new Path();
    private StaticLayout layout;
    private final int padH, padV, tailH, sh, maxW;
    private float tailX;
    private boolean below;

    public BubbleView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        tp.setColor(CatArt.INK);
        tp.setTextSize(16 * c.getResources().getDisplayMetrics().scaledDensity);
        tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        padH = (int) (16 * dp);
        padV = (int) (12 * dp);
        tailH = (int) (9 * dp);
        sh = (int) (5 * dp);
        maxW = (int) (280 * dp);
    }

    void setText(CharSequence s) {
        int w = (int) Math.ceil(StaticLayout.getDesiredWidth(s, tp));
        w = Math.max(1, Math.min(maxW - 2 * padH, w));
        layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.2f)
                .setIncludePad(false)
                .build();
        requestLayout();
        invalidate();
    }

    int bw() { return layout == null ? 0 : layout.getWidth() + 2 * padH + sh + (int) (2 * dp); }

    int bh() { return layout == null ? 0 : layout.getHeight() + 2 * padV + tailH + sh + (int) (2 * dp); }

    void setTail(float x, boolean below) {
        if (Math.abs(x - tailX) > 0.5f || below != this.below) {
            tailX = x;
            this.below = below;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (layout == null) return;
        float s = stroke.getStrokeWidth();
        float top = below ? tailH : s;
        r.set(s, top, bw() - sh - s, top + layout.getHeight() + 2 * padV);
        float rad = 18 * dp;
        c.save();
        c.translate(sh * 0.8f, sh);
        c.drawRoundRect(r, rad, rad, shadow);
        c.restore();
        c.drawRoundRect(r, rad, rad, bg);
        c.drawRoundRect(r, rad, rad, stroke);

        float tx = Math.max(r.left + rad, Math.min(r.right - rad, tailX));
        tail.reset();
        if (below) {
            tail.moveTo(tx - 8 * dp, r.top + s);
            tail.lineTo(tx, r.top - tailH + s);
            tail.lineTo(tx + 8 * dp, r.top + s);
        } else {
            tail.moveTo(tx - 8 * dp, r.bottom - s);
            tail.lineTo(tx, r.bottom + tailH - s);
            tail.lineTo(tx + 8 * dp, r.bottom - s);
        }
        c.drawPath(tail, bg);
        c.drawPath(tail, stroke);
        // 盖住尖角根部的那段描边
        Paint cover = bg;
        if (below) c.drawRect(tx - 7 * dp, r.top - s * 0.2f, tx + 7 * dp, r.top + s * 1.4f, cover);
        else c.drawRect(tx - 7 * dp, r.bottom - s * 1.4f, tx + 7 * dp, r.bottom + s * 0.2f, cover);

        c.save();
        c.translate(r.left + padH, r.top + padV);
        layout.draw(c);
        c.restore();
    }
}
