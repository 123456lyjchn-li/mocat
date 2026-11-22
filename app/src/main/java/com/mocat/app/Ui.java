package com.mocat.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 小工具：用代码拼出宣纸风的控件。 */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static Typeface serif(boolean bold) { return Typeface.create(Typeface.SERIF, bold ? Typeface.BOLD : Typeface.NORMAL); }

    static GradientDrawable round(int fill, int stroke, float strokeDp, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(serif(bold));
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    /** 实心墨色按钮（primary=true）或描边按钮 */
    static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 15, primary ? CatArt.PAPER : CatArt.INK, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setBackground(primary ? round(CatArt.INK, 0, 0, 23, c) : round(0x00000000, CatArt.INK, 1.5f, 23, c));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView chip(Context c, String s) {
        TextView b = text(c, s, 14, CatArt.INK, false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 40));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setBackground(round(CatArt.CARD, CatArt.LINE, 1, 20, c));
        b.setClickable(true);
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }
}
