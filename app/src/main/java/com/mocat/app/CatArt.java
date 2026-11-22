package com.mocat.app;

import android.graphics.Canvas;
import android.graphics.DiscretePathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 水墨猫：坐标系 200×160，脚底中心在 (100,150)，默认面朝右。 */
final class CatArt {
    static final int INK = 0xFF1D1B16, PAPER = 0xFFF1EADB, CARD = 0xFFF8F3E8, SEAL = 0xFFB3322A,
            EYE = 0xFFF3EAD4, FEAT = 0xFFE9DFC8, WHISK = 0xFF9A9180, MUTE = 0xFF7B7366, LINE = 0xFFD6CCB6;

    /** 当前姿态 */
    static final class Pose {
        float t;            // 秒
        float face = 1;     // 1 朝右，-1 朝左
        boolean walking, sleeping, peek;
        float lookX, lookY; // 瞳孔偏移（猫自身坐标，未翻转）
        float lift, tilt;   // 跳起高度、整体倾斜角度
    }

    final Path tail = svg("M58 108C30 106 18 78 28 54C33 42 46 42 44 54C38 74 44 94 64 98Z");
    final Path body = svg("M50 116C44 92 70 76 104 77C134 78 152 86 154 106C156 124 140 132 104 132C72 132 54 128 50 116Z");
    final Path earL = svg("M128 56L131 22L152 42Z");
    final Path earR = svg("M162 42L182 21L184 58Z");
    final Path inL = svg("M133 46L134 31L144 42Z");
    final Path inR = svg("M167 42L178 31L178 50Z");
    final Path head = svg("M122 74C120 52 136 40 155 40C175 40 188 54 186 72C185 90 171 100 154 100C137 100 123 92 122 74Z");
    final Path nose = svg("M151.5 80H158.5L155 84.5Z");
    final Path mouth = svg("M149 87Q152 90 155 87Q158 90 161 87");
    final Path shut = svg("M135 69Q142 74 149 69M161 69Q168 74 175 69");
    final Path whisk = svg("M128 82L100 78M128 86L102 90M182 82L208 76M182 86L206 90");
    final Path collar = svg("M128 92C138 101 160 104 178 94");
    // 远后腿、远前腿、近后腿、近前腿
    final RectF[] legs = {
            new RectF(76, 108, 90, 148), new RectF(136, 106, 150, 148),
            new RectF(60, 110, 75, 150), new RectF(122, 108, 137, 150)};

    final Paint fill = paint(INK, Paint.Style.FILL);
    final Paint far = paint(0xB81D1B16, Paint.Style.FILL);
    final Paint halo = paint(0x3C1D1B16, Paint.Style.STROKE);
    final Paint seal = paint(SEAL, Paint.Style.FILL);
    final Paint sealSoft = paint(0x80B3322A, Paint.Style.FILL);
    final Paint eye = paint(EYE, Paint.Style.FILL);
    final Paint pupil = paint(0xFF16140F, Paint.Style.FILL);
    final Paint white = paint(0xFFFFFFFF, Paint.Style.FILL);
    final Paint feat = paint(FEAT, Paint.Style.STROKE);
    final Paint wh = paint(WHISK, Paint.Style.STROKE);
    final Paint col = paint(SEAL, Paint.Style.STROKE);
    final Paint shadow = paint(0x221D1B16, Paint.Style.FILL);

    CatArt() {
        halo.setStrokeWidth(5f);
        halo.setStrokeJoin(Paint.Join.ROUND);
        halo.setPathEffect(new DiscretePathEffect(6f, 1.6f));
        feat.setStrokeWidth(2.2f);
        feat.setStrokeCap(Paint.Cap.ROUND);
        wh.setStrokeWidth(1.1f);
        wh.setStrokeCap(Paint.Cap.ROUND);
        col.setStrokeWidth(5f);
        col.setStrokeCap(Paint.Cap.ROUND);
    }

    static Paint paint(int color, Paint.Style style) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(style);
        return p;
    }

    /** 在 (cx, feetY) 处画一只宽 widthPx 像素的猫。 */
    void draw(Canvas c, float cx, float feetY, float widthPx, Pose p) {
        float s = widthPx / 200f;
        c.save();
        c.translate(cx, feetY);
        c.scale(s * p.face, s);
        c.translate(-100, -150);

        float shrink = 1f - Math.min(0.4f, p.lift / 60f);
        c.save();
        c.scale(shrink, 1f, 102, 151);
        c.drawOval(42, 145, 162, 157, shadow);
        c.restore();

        c.translate(0, -p.lift);
        if (p.tilt != 0) c.rotate(p.tilt, 100, 140);
        double tau = Math.PI * 2;
        float bob = p.walking ? (float) Math.abs(Math.sin(p.t * tau / 0.3)) * 2.5f : 0f;
        c.translate(0, -bob);
        float tailA = (float) Math.sin(p.t * tau / (p.walking ? 0.7 : 2.8)) * 8f;
        float legA = p.walking ? (float) Math.sin(p.t * tau / 0.6) * 22f : 0f;

        c.save();
        c.rotate(tailA, 62, 106);
        ink(c, tail);
        c.restore();

        leg(c, legs[0], -legA, far, false);
        leg(c, legs[1], legA, far, false);
        ink(c, body);
        leg(c, legs[2], legA, fill, true);
        leg(c, legs[3], -legA, fill, true);

        float headA;
        if (p.sleeping) headA = 16f;
        else if (p.peek) headA = -5f - (float) Math.sin(p.t * tau / 3.4) * 5f;
        else headA = 0f;
        c.save();
        c.rotate(headA, 138, 95);
        if (p.sleeping) c.translate(0, 8);
        ink(c, earL);
        ink(c, earR);
        c.drawPath(inL, sealSoft);
        c.drawPath(inR, sealSoft);
        ink(c, head);
        if (p.sleeping) {
            c.drawPath(shut, feat);
        } else {
            float ph = p.t % 5.5f;
            float blink = ph > 5.28f ? 0.1f : 1f;
            c.save();
            c.scale(1f, blink, 155, 68);
            c.drawOval(134.5f, 59.5f, 149.5f, 76.5f, eye);
            c.drawOval(160.5f, 59.5f, 175.5f, 76.5f, eye);
            float dx = p.lookX, dy = p.lookY;
            c.drawOval(138.6f + dx, 61.6f + dy, 145.4f + dx, 74.4f + dy, pupil);
            c.drawOval(164.6f + dx, 61.6f + dy, 171.4f + dx, 74.4f + dy, pupil);
            c.drawCircle(144 + dx, 65 + dy, 1.6f, white);
            c.drawCircle(170 + dx, 65 + dy, 1.6f, white);
            c.restore();
        }
        c.drawPath(nose, seal);
        c.drawPath(mouth, feat);
        c.drawPath(whisk, wh);
        c.restore();

        c.drawPath(collar, col);
        c.drawCircle(150, 102, 5, seal);
        c.restore();
    }

    private void ink(Canvas c, Path path) {
        c.drawPath(path, halo);
        c.drawPath(path, fill);
    }

    private void leg(Canvas c, RectF r, float angle, Paint p, boolean withHalo) {
        c.save();
        c.rotate(angle, r.centerX(), r.top + 2);
        float rad = r.width() / 2f;
        if (withHalo) c.drawRoundRect(r, rad, rad, halo);
        c.drawRoundRect(r, rad, rad, p);
        c.restore();
    }

    /** 极简 SVG path 解析：支持 M L H V C S Q Z（绝对坐标）。 */
    static Path svg(String d) {
        Path p = new Path();
        Matcher m = Pattern.compile("[MLHVCSQZ]|-?\\d*\\.?\\d+").matcher(d);
        ArrayList<String> tk = new ArrayList<>();
        while (m.find()) tk.add(m.group());
        int i = 0;
        char cmd = 'M';
        float x = 0, y = 0, c2x = 0, c2y = 0;
        while (i < tk.size()) {
            String t = tk.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = t.charAt(0);
                i++;
                if (cmd == 'Z') {
                    p.close();
                    continue;
                }
            }
            if (i >= tk.size()) break;
            switch (cmd) {
                case 'M':
                    x = f(tk, i++); y = f(tk, i++);
                    p.moveTo(x, y);
                    cmd = 'L';
                    break;
                case 'L':
                    x = f(tk, i++); y = f(tk, i++);
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = f(tk, i++);
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = f(tk, i++);
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float a = f(tk, i++), b = f(tk, i++);
                    c2x = f(tk, i++); c2y = f(tk, i++);
                    x = f(tk, i++); y = f(tk, i++);
                    p.cubicTo(a, b, c2x, c2y, x, y);
                    break;
                }
                case 'S': {
                    float a = 2 * x - c2x, b = 2 * y - c2y;
                    c2x = f(tk, i++); c2y = f(tk, i++);
                    x = f(tk, i++); y = f(tk, i++);
                    p.cubicTo(a, b, c2x, c2y, x, y);
                    break;
                }
                case 'Q': {
                    float a = f(tk, i++), b = f(tk, i++);
                    x = f(tk, i++); y = f(tk, i++);
                    p.quadTo(a, b, x, y);
                    break;
                }
                default:
                    i++;
            }
        }
        return p;
    }

    private static float f(ArrayList<String> tk, int i) {
        return i < tk.size() ? Float.parseFloat(tk.get(i)) : 0f;
    }
}
