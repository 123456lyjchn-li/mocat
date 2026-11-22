package com.mocat.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 墨猫本体：住在无障碍悬浮层里，负责画猫、听指令、操作手机。 */
public class CatService extends AccessibilityService implements CatView.Host {

    private static CatService instance;

    static CatService get() { return instance; }

    enum Mode { DOCK, FREE, BUSY }

    private static final int FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private static final String[] LINES = {"喵～", "咕噜咕噜", "嗯？要我做什么", "再摸摸", "喵呜！", "今天也要开心"};

    private static final String HELP = "我会这些：\n"
            + "· 打开微信 / 回到桌面 / 返回\n"
            + "· 往下翻 / 点击 搜索 / 输入 你好\n"
            + "· 读一下屏幕 / 截屏 / 锁屏\n"
            + "· 打开手电筒 / 音量调大 / 暂停音乐\n"
            + "· 打电话给 妈妈 / 发短信给 10086\n"
            + "· 10分钟后提醒我喝水 / 明早7点叫我\n"
            + "· 现在几点 / 电量 / 算 23*7";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Runnable> reminders = new ArrayList<>();
    private WindowManager wm;
    private CatView cat;
    private BubbleView bubble;
    private WindowManager.LayoutParams catLp, bubLp;
    private SharedPreferences prefs;
    private final Meow meow = new Meow();
    private Vibrator vib;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeak;
    private Mode mode = Mode.DOCK;
    private boolean right = true, bubbleOn, panelOpen, attached;
    private int sw, sh, statusH, lastBx = -9999, lastBy = -9999;
    private float dp;
    private long lastPoke;
    private final Runnable hideBubble = this::hideBubbleNow;

    // ================= 生命周期 =================

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        dp = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        prefs = getSharedPreferences("mocat", MODE_PRIVATE);
        meow.enabled = prefs.getBoolean("sound", true);
        right = prefs.getBoolean("right", true);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusH = id > 0 ? getResources().getDimensionPixelSize(id) : (int) (28 * dp);
        measureScreen();

        cat = new CatView(this);
        cat.host = this;
        cat.docked = true;
        cat.face = right ? -1 : 1;
        cat.x = edgeX();
        cat.y = clampY(prefs.getFloat("y", sh * 0.6f));
        catLp = baseLp();
        applyDockLp();

        bubble = new BubbleView(this);
        bubLp = baseLp();
        bubLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        bubble.setVisibility(View.GONE);
        try {
            wm.addView(cat, catLp);
            wm.addView(bubble, bubLp);
            attached = true;
        } catch (Exception e) {
            attached = false;
        }
        poke();
        h.postDelayed(() -> say("我在这儿～轻点我，我就出来", 3500), 900);
        h.post(idleCheck);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (instance == this) instance = null;
        h.removeCallbacksAndMessages(null);
        reminders.clear();
        if (attached) {
            try { wm.removeView(cat); } catch (Exception ignored) { }
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            attached = false;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (cat == null) return;
        measureScreen();
        cat.stop();
        cat.face = right ? -1 : 1;
        cat.x = edgeX();
        cat.y = clampY(cat.y);
        setMode(Mode.DOCK);
    }

    // ================= 窗口与位置 =================

    private WindowManager.LayoutParams baseLp() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    @SuppressWarnings("deprecation")
    private void measureScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        DisplayManager dmg = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dmg.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        sw = dm.widthPixels;
        sh = dm.heightPixels;
    }

    private float peekW() { return 74 * dp; }

    private float edgeX() { return right ? sw - peekW() + cat.catW / 2f : peekW() - cat.catW / 2f; }

    private float minY() { return statusH + 140 * cat.unit() + 24 * dp; }

    private float maxY() { return sh - 56 * dp; }

    private float clampY(float y) { return Math.max(minY(), Math.min(maxY(), y)); }

    private float clampX(float x) { return Math.max(40 * dp, Math.min(sw - 40 * dp, x)); }

    private void applyDockLp() {
        float u = cat.unit();
        catLp.width = (int) peekW();
        catLp.height = (int) (165 * u + 30 * dp);
        catLp.x = right ? sw - catLp.width : 0;
        catLp.y = (int) (cat.y - 150 * u - 30 * dp);
    }

    private void setMode(Mode m) {
        mode = m;
        cat.docked = m == Mode.DOCK;
        cat.tint = m == Mode.FREE;
        if (m == Mode.DOCK) {
            applyDockLp();
        } else {
            catLp.x = 0;
            catLp.y = 0;
            catLp.width = sw;
            catLp.height = sh;
        }
        catLp.flags = m == Mode.BUSY ? FLAGS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : FLAGS;
        updateCat();
    }

    private void updateCat() {
        if (!attached) return;
        try { wm.updateViewLayout(cat, catLp); } catch (Exception ignored) { }
    }

    private void save() {
        prefs.edit().putBoolean("right", right).putFloat("y", cat.y).apply();
    }

    private void poke() { lastPoke = SystemClock.uptimeMillis(); }

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            long idle = SystemClock.uptimeMillis() - lastPoke;
            if (cat != null && !cat.moving && !panelOpen) {
                if (mode == Mode.FREE && idle > 12000) {
                    say("我先回边上歇着啦", 2000);
                    dock(null);
                } else if (mode == Mode.BUSY && idle > 5000) {
                    dock(null);
                }
            }
            h.postDelayed(this, 1000);
        }
    };

    // ================= 走动 =================

    private void comeOut(Runnable then) {
        poke();
        cat.sleeping = false;
        setMode(Mode.FREE);
        cat.walkTo(right ? sw - 130 * dp : 130 * dp, clampY(cat.y), 260, then);
    }

    /** 不挡住手机的出场：猫可以在屏幕上走，但触摸会穿透到下面的应用。 */
    private void goBusy(Runnable then) {
        poke();
        cat.sleeping = false;
        if (mode == Mode.DOCK) {
            setMode(Mode.BUSY);
            cat.walkTo(right ? sw - 130 * dp : 130 * dp, clampY(cat.y), 280, then);
        } else {
            setMode(Mode.BUSY);
            if (then != null) then.run();
        }
    }

    private void dock(Runnable after) {
        poke();
        if (mode == Mode.DOCK) {
            if (after != null) after.run();
            return;
        }
        right = cat.x >= sw / 2f;
        cat.walkTo(edgeX(), clampY(cat.y), 280, () -> {
            cat.face = right ? -1 : 1;
            setMode(Mode.DOCK);
            save();
            if (after != null) after.run();
        });
    }

    /** 走到某个点旁边，让爪子刚好够到它 */
    private void walkNear(float px, float py, Runnable then) {
        float u = cat.unit();
        float f = px >= cat.x ? 1 : -1;
        cat.walkTo(clampX(px - f * 45 * u), clampY(py + 30 * u), 320, () -> {
            cat.face = f;
            if (then != null) then.run();
        });
    }

    // ================= 气泡 =================

    void say(String text) { say(text, 3500); }

    void say(String text, long ms) {
        if (!attached || text == null) return;
        bubble.setText(text);
        bubLp.width = bubble.bw();
        bubLp.height = bubble.bh();
        bubble.setVisibility(View.VISIBLE);
        bubbleOn = true;
        lastBx = lastBy = -9999;
        try { wm.updateViewLayout(bubble, bubLp); } catch (Exception ignored) { }
        positionBubble();
        h.removeCallbacks(hideBubble);
        h.postDelayed(hideBubble, ms);
    }

    private void hideBubbleNow() {
        bubbleOn = false;
        if (bubble != null) bubble.setVisibility(View.GONE);
    }

    private void positionBubble() {
        if (!bubbleOn || !attached) return;
        float u = cat.unit();
        float vx = mode == Mode.DOCK ? (right ? sw - 40 * dp : 40 * dp) : clampX(cat.x + cat.face * 30 * u);
        int w = bubLp.width, hh = bubLp.height;
        int left = (int) Math.max(12 * dp, Math.min(sw - w - 12 * dp, vx - w / 2f));
        int top = (int) (cat.topY() - hh);
        boolean below = top < statusH + 8 * dp;
        if (below) top = (int) (cat.y + 14 * dp);
        bubble.setTail(vx - left, below);
        if (left != lastBx || top != lastBy) {
            lastBx = left;
            lastBy = top;
            bubLp.x = left;
            bubLp.y = top;
            try { wm.updateViewLayout(bubble, bubLp); } catch (Exception ignored) { }
        }
    }

    // ================= 触摸回调 =================

    @Override public void onCatTap() {
        poke();
        if (mode == Mode.DOCK) {
            meow.play(0);
            cat.hop();
            buzz(15);
            say("喵？来啦", 1800);
            int hints = prefs.getInt("hints", 0);
            comeOut(() -> {
                if (hints < 3) {
                    prefs.edit().putInt("hints", hints + 1).apply();
                    say("点哪儿我就去哪儿～\n长按别处，我替你按一下\n长按我，跟我说要做什么\n双击我，我就回边上", 6000);
                }
            });
        } else if (cat.sleeping) {
            cat.sleeping = false;
            meow.play(2);
            say("嗯…醒了醒了", 2000);
        } else {
            meow.play(Math.random() < 0.5 ? 0 : 1);
            cat.hop();
            cat.hearts(2);
            buzz(10);
            say(LINES[(int) (Math.random() * LINES.length)], 1800);
        }
    }

    @Override public void onCatDoubleTap() {
        say("那我回边上等你～", 1800);
        dock(null);
    }

    @Override public void onCatLongPress() {
        buzz(25);
        meow.play(1);
        openPanel();
    }

    @Override public void onStageTap(float x, float y) {
        poke();
        cat.ripple(x, y);
        if (mode == Mode.FREE) cat.walkTo(clampX(x), clampY(y + 60 * cat.unit()), 240, null);
    }

    @Override public void onStageLongPress(float x, float y) {
        if (mode != Mode.FREE) return;
        buzz(25);
        say("我去按这里", 1500);
        setMode(Mode.BUSY);
        walkNear(x, y, () -> pressAt(x, y, () -> h.postDelayed(() -> {
            if (mode == Mode.BUSY) setMode(Mode.FREE);
            poke();
        }, 500)));
    }

    @Override public void onDockDrag(float x, float y, boolean end) {
        poke();
        right = x >= sw / 2f;
        cat.face = right ? -1 : 1;
        cat.x = edgeX();
        cat.y = clampY(y + 82 * cat.unit());
        applyDockLp();
        updateCat();
        if (end) save();
    }

    @Override public void onPet() {
        poke();
        cat.sleeping = false;
        cat.hearts(1);
        buzz(12);
        say("呼噜…呼噜…", 1500);
    }

    @Override public void onFrame() { positionBubble(); }

    // ================= 给其他界面调用 =================

    void openPanel() {
        poke();
        panelOpen = true;
        if (mode == Mode.FREE) setMode(Mode.BUSY);
        say("要我做什么？", 3000);
        Intent i = new Intent(this, CommandActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { panelOpen = false; }
    }

    void onPanelClosed() {
        panelOpen = false;
        poke();
    }

    void postCommand(String text) {
        panelOpen = false;
        poke();
        h.postDelayed(() -> run(text), 420);
    }

    void summon() {
        if (mode == Mode.DOCK) {
            meow.play(0);
            say("我在这儿～", 1800);
            comeOut(null);
        } else {
            cat.hop();
            say("我在这儿～", 1800);
        }
    }

    void setSound(boolean on) { meow.enabled = on; }

    // ================= 手机操作 =================

    private void buzz(long ms) {
        try {
            if (vib != null && vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            say("这台手机上没有能做这件事的应用", 3000);
            return false;
        }
    }

    private boolean hasPerm(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private void global(int action, String msg) {
        boolean ok;
        try { ok = performGlobalAction(action); } catch (Exception e) { ok = false; }
        say(ok ? msg : "这台手机不让我这么做…", 1800);
    }

    private void tap(float x, float y, Runnable done) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        boolean ok = false;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { if (done != null) done.run(); }
                @Override public void onCancelled(GestureDescription d) { if (done != null) done.run(); }
            }, h);
        } catch (Exception ignored) { }
        if (!ok && done != null) h.post(done);
    }

    private void pressAt(float x, float y, Runnable done) {
        cat.hop();
        cat.paw(x, y, (float) (-Math.PI / 2));
        buzz(15);
        h.postDelayed(() -> tap(x, y, done), 220);
    }

    /** dir: 0 往下翻(手指上划) 1 往上翻 2 左滑 3 右滑 */
    private void swipe(int dir, String msg) {
        float cx = sw / 2f, cy = sh / 2f;
        Path p = new Path();
        if (dir == 0) { p.moveTo(cx, sh * 0.72f); p.lineTo(cx, sh * 0.28f); }
        else if (dir == 1) { p.moveTo(cx, sh * 0.28f); p.lineTo(cx, sh * 0.72f); }
        else if (dir == 2) { p.moveTo(sw * 0.75f, cy); p.lineTo(sw * 0.25f, cy); }
        else { p.moveTo(sw * 0.25f, cy); p.lineTo(sw * 0.75f, cy); }
        if (mode == Mode.FREE) setMode(Mode.BUSY);
        cat.hop();
        say(msg, 1500);
        h.postDelayed(() -> {
            try {
                dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 320)).build(), null, null);
            } catch (Exception e) {
                say("滑不动…", 1800);
            }
        }, 80);
    }

    private List<AccessibilityNodeInfo> roots() {
        ArrayList<AccessibilityNodeInfo> r = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo n = w.getRoot();
                if (n != null && !isMine(n)) r.add(n);
            }
        } catch (Exception ignored) { }
        if (r.isEmpty()) {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a != null && !isMine(a)) r.add(a);
        }
        return r;
    }

    private boolean isMine(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p != null && getPackageName().contentEquals(p);
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    private AccessibilityNodeInfo findNode(String q) {
        AccessibilityNodeInfo best = null;
        int bestScore = 99;
        for (AccessibilityNodeInfo root : roots()) {
            List<AccessibilityNodeInfo> list;
            try { list = root.findAccessibilityNodeInfosByText(q); } catch (Exception e) { continue; }
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                if (!n.isVisibleToUser() || isMine(n)) continue;
                String t = str(n.getText()), d = str(n.getContentDescription());
                int sc = (t.equals(q) || d.equals(q)) ? 0 : (t.startsWith(q) || d.startsWith(q)) ? 1 : 2;
                if (sc < bestScore) {
                    bestScore = sc;
                    best = n;
                }
            }
        }
        return best;
    }

    private void clickText(String q) {
        AccessibilityNodeInfo n = findNode(q);
        if (n == null) {
            say("屏幕上没找到「" + q + "」", 2500);
            return;
        }
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        final AccessibilityNodeInfo target = c;
        final float px = b.centerX(), py = b.centerY();
        say("找到「" + q + "」，我去按", 2200);
        goBusy(() -> walkNear(px, py, () -> {
            cat.hop();
            cat.paw(px, py, (float) (-Math.PI / 2));
            buzz(15);
            h.postDelayed(() -> {
                boolean ok = false;
                try { ok = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Exception ignored) { }
                if (!ok) tap(px, py, null);
                poke();
            }, 220);
        }));
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private void typeText(String text) {
        AccessibilityNodeInfo target = null;
        List<AccessibilityNodeInfo> rs = roots();
        for (AccessibilityNodeInfo r : rs) {
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) { target = f; break; }
        }
        if (target == null) {
            for (AccessibilityNodeInfo r : rs) {
                target = firstEditable(r, 0);
                if (target != null) break;
            }
        }
        if (target == null) {
            say("没找到输入框…先点一下要写字的地方", 3000);
            return;
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        say(ok ? "写好了：" + text : "这个输入框不让我写…", 2500);
    }

    private void collect(AccessibilityNodeInfo n, Set<String> out, int depth) {
        if (n == null || depth > 40 || out.size() >= 40) return;
        if (n.isVisibleToUser()) {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) t = n.getContentDescription();
            String s = str(t);
            if (s.length() > 0 && s.length() < 200) out.add(s);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private void readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || isMine(root)) {
            List<AccessibilityNodeInfo> rs = roots();
            root = rs.isEmpty() ? null : rs.get(0);
        }
        if (root == null) {
            say("看不清屏幕…", 2000);
            return;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collect(root, out, 0);
        if (out.isEmpty()) {
            say("这一页没有能读的字", 2500);
            return;
        }
        String all = TextUtils.join("，", out);
        say("屏幕上写着：\n" + (all.length() > 70 ? all.substring(0, 70) + "…" : all), 7000);
        speak(all.length() > 300 ? all.substring(0, 300) : all);
    }

    private void speak(String s) {
        if (tts == null) {
            pendingSpeak = s;
            tts = new TextToSpeech(this, status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && tts != null) {
                    tts.setLanguage(Locale.CHINA);
                    tts.setPitch(1.4f);
                    if (pendingSpeak != null) tts.speak(pendingSpeak, TextToSpeech.QUEUE_FLUSH, null, "mocat");
                    pendingSpeak = null;
                } else {
                    say("手机里没有能朗读的语音引擎", 3000);
                }
            });
            return;
        }
        if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "mocat");
    }

    private void torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(f)) {
                    cm.setTorchMode(id, on);
                    say(on ? "手电筒打开啦" : "手电筒关掉啦", 2000);
                    return;
                }
            }
            say("没找到闪光灯", 2000);
        } catch (Exception e) {
            say("手电筒被别的应用占着…", 2500);
        }
    }

    private void volume(String v) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (v.contains("取消静音") || v.contains("恢复")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                say("声音回来啦", 1800);
            } else if (v.contains("静音")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                say("嘘…静音了", 1800);
            } else if (v.contains("最大")) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
                say("开到最大啦", 1800);
            } else if (v.matches(".*(小|低|减|轻).*")) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                say("小声一点", 1500);
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                say("大声一点", 1500);
            }
        } catch (Exception e) {
            say("现在调不了音量（可能开着勿扰）", 2500);
        }
    }

    private void media(int keyCode, String msg) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        say(msg, 1500);
    }

    private String lookup(String name) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return null;
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + name + "%"}, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    /** 把名字或号码变成号码；找不到时让猫说明原因并返回 null */
    private String resolveNumber(String who) {
        String num = who.replaceAll("[\\s-]", "");
        if (num.matches("\\+?\\d{3,}")) return num;
        String found = lookup(who);
        if (found == null) {
            say(hasPerm(Manifest.permission.READ_CONTACTS)
                    ? "通讯录里没找到「" + who + "」"
                    : "我还不能看通讯录…\n在墨猫首页点「授权」，或者直接说号码", 4500);
            return null;
        }
        return found.replaceAll("[\\s-]", "");
    }

    private void call(String who) {
        String num = resolveNumber(who);
        if (num == null) return;
        boolean direct = hasPerm(Manifest.permission.CALL_PHONE);
        Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + num));
        if (start(i)) say(direct ? "正在打给 " + num : "号码填好了，按拨号键就打出去", 3000);
    }

    private void sms(String who, String body) {
        String num = resolveNumber(who);
        if (num == null) return;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + num));
        if (body != null && !body.isEmpty()) i.putExtra("sms_body", body);
        if (start(i)) say("短信写好了，发不发由你按", 3000);
    }

    private static final Map<String, String[]> ALIAS = new HashMap<>();

    static {
        ALIAS.put("b站", new String[]{"哔哩哔哩", "bilibili"});
        ALIAS.put("相册", new String[]{"相册", "图库", "照片"});
        ALIAS.put("时钟", new String[]{"时钟", "闹钟"});
        ALIAS.put("应用商店", new String[]{"应用商店", "应用市场", "软件商店", "play 商店"});
        ALIAS.put("浏览器", new String[]{"浏览器", "chrome"});
        ALIAS.put("短信", new String[]{"信息", "短信", "messages"});
        ALIAS.put("电话", new String[]{"电话", "拨号"});
    }

    private boolean openApp(String name) {
        PackageManager pm = getPackageManager();
        String q = name.toLowerCase(Locale.ROOT).replace("app", "").replace("应用", "").trim();
        if (q.isEmpty()) return false;
        String[] cands = ALIAS.containsKey(q) ? ALIAS.get(q) : new String[]{q};
        Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(li, 0);
        ResolveInfo best = null;
        int bestScore = 9;
        for (ResolveInfo r : apps) {
            String label = str(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.isEmpty()) continue;
            for (String c : cands) {
                int s = label.equals(c) ? 0 : label.startsWith(c) ? 1 : label.contains(c) ? 2
                        : (c.contains(label) && label.length() >= 2) ? 3 : 9;
                if (s < bestScore) {
                    bestScore = s;
                    best = r;
                }
            }
        }
        if (best == null) return false;
        String pkg = best.activityInfo.packageName;
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, best.activityInfo.name);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
            say("叼着「" + str(best.loadLabel(pm)) + "」的钥匙来啦", 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openSpecial(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("手电") || n.contains("闪光灯")) { torch(true); return true; }
        if (n.matches(".*(wifi|wlan|无线).*")) return start(new Intent(Settings.ACTION_WIFI_SETTINGS));
        if (n.contains("蓝牙")) return start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        if (n.matches(".*(定位|位置|gps).*")) return start(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        if (n.contains("亮度") || n.contains("显示")) return start(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        if (n.equals("设置") || n.equals("系统设置")) return start(new Intent(Settings.ACTION_SETTINGS));
        if (n.contains("相机") || n.contains("拍照")) return start(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        return false;
    }

    private void remind(long ms, String what) {
        Runnable r = new Runnable() {
            @Override public void run() {
                reminders.remove(this);
                ring(what);
            }
        };
        reminders.add(r);
        h.postDelayed(r, ms);
    }

    private void ring(String what) {
        poke();
        try {
            if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 120, 300, 120, 300}, -1));
        } catch (Exception ignored) { }
        meow.play(0);
        h.postDelayed(() -> meow.play(1), 550);
        h.postDelayed(() -> meow.play(2), 1100);
        speak("时间到了，" + what);
        Runnable show = () -> {
            cat.shake();
            say("时间到！" + what, 8000);
            poke();
        };
        if (mode == Mode.DOCK) goBusy(show);
        else show.run();
    }

    private void setAlarm(int hour, int min) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "墨猫叫你啦")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (start(i)) say(String.format(Locale.CHINA, "闹钟定好了：%02d:%02d", hour, min), 3000);
    }

    // ================= 指令解析 =================

    private static final String N = "([\\d.]+|[零一二两三四五六七八九十百半]+)";
    private static final String UNIT = "(秒钟?|分钟?|个?小时|个?钟头)";
    private static final Pattern RA = Pattern.compile(N + "\\s*" + UNIT + "(?:之|以)?后\\s*(?:提醒我?|叫我)?\\s*(.*)");
    private static final Pattern RB = Pattern.compile("(?:提醒我?|叫我)\\s*(.*?)\\s*" + N + "\\s*" + UNIT + "(?:之|以)?后");
    private static final Pattern TA = Pattern.compile("(?:计时|倒计时|定时|定个时)\\s*" + N + "\\s*(秒钟?|分钟?|个?小时|s|min|m|h)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TB = Pattern.compile(N + "\\s*" + UNIT + "的?(?:计时|倒计时|定时)");
    private static final Pattern ALARM = Pattern.compile("(早上|上午|中午|下午|晚上|今晚|明早|明天早上|明天|凌晨)?\\s*" + N + "\\s*[点:：时]\\s*" + N + "?");

    private static boolean has(String v, String re) { return Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(v).find(); }

    private static Matcher mt(String re, String v) {
        Matcher m = Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(v);
        return m.find() ? m : null;
    }

    private static Matcher mt(Pattern p, String v) {
        Matcher m = p.matcher(v);
        return m.find() ? m : null;
    }

    private static final Map<Character, Integer> CN = new HashMap<>();

    static {
        String d = "零一二三四五六七八九";
        for (int i = 0; i < d.length(); i++) CN.put(d.charAt(i), i);
        CN.put('两', 2);
    }

    static double num(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (s.matches("[\\d.]+")) {
            try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
        }
        double n = 0, cur = 0;
        for (char c : s.toCharArray()) {
            if (c == '百') { n += (cur == 0 ? 1 : cur) * 100; cur = 0; }
            else if (c == '十') { n += (cur == 0 ? 1 : cur) * 10; cur = 0; }
            else if (c == '半') cur += 0.5;
            else if (CN.containsKey(c)) cur = CN.get(c);
        }
        return n + cur;
    }

    private static long unitMs(String u) {
        if (u == null) return 60000;
        String l = u.toLowerCase(Locale.ROOT);
        if (l.contains("秒") || l.equals("s")) return 1000;
        if (l.contains("时") || l.contains("钟头") || l.equals("h")) return 3600000;
        return 60000;
    }

    private static String unitName(String u) {
        long ms = unitMs(u);
        return ms == 1000 ? "秒" : ms == 60000 ? "分钟" : "小时";
    }

    private static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return BigDecimal.valueOf(d).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    void run(String raw) {
        String v = raw.trim().replaceAll("[。！!，,\\s]+$", "");
        if (v.isEmpty() || cat == null) return;
        poke();
        Matcher m;
        if (cat.sleeping && !has(v, "睡|晚安")) cat.sleeping = false;

        // —— 提醒、计时、闹钟 ——
        if (has(v, "取消(所有)?(提醒|计时)")) {
            for (Runnable r : reminders) h.removeCallbacks(r);
            int n = reminders.size();
            reminders.clear();
            say(n > 0 ? "取消了 " + n + " 个提醒" : "现在没有提醒", 2000);
            return;
        }
        if ((m = mt(RA, v)) != null) {
            double n = num(m.group(1));
            String what = m.group(3).replaceAll("^[，,:：\\s]+", "");
            if (what.isEmpty()) what = "时间到";
            if (n > 0) {
                remind((long) (n * unitMs(m.group(2))), what);
                say("好，" + fmt(n) + unitName(m.group(2)) + "后提醒你：" + what, 3000);
                return;
            }
        }
        if ((m = mt(RB, v)) != null) {
            double n = num(m.group(2));
            String what = m.group(1).isEmpty() ? "时间到" : m.group(1);
            if (n > 0) {
                remind((long) (n * unitMs(m.group(3))), what);
                say("好，" + fmt(n) + unitName(m.group(3)) + "后提醒你：" + what, 3000);
                return;
            }
        }
        if ((m = mt(TA, v)) != null || (m = mt(TB, v)) != null) {
            double n = num(m.group(1));
            String u = m.group(2);
            if (n > 0) {
                remind((long) (n * unitMs(u)), fmt(n) + unitName(u) + "到啦");
                say("开始计时 " + fmt(n) + unitName(u) + "，我替你盯着", 3000);
                return;
            }
        }
        if (has(v, "闹钟|叫我|叫醒") && (m = mt(ALARM, v)) != null) {
            int hour = (int) num(m.group(2));
            String mm = m.group(3);
            int min = mm == null ? 0 : mm.equals("半") ? 30 : (int) num(mm);
            String part = m.group(1);
            if (part != null && part.matches("下午|晚上|今晚") && hour < 12) hour += 12;
            if ("中午".equals(part) && hour < 11) hour += 12;
            if (hour >= 0 && hour < 24 && min >= 0 && min < 60) {
                setAlarm(hour, min);
                return;
            }
        }

        // —— 系统动作 ——
        if (has(v, "回到桌面|回桌面|返回桌面|主屏幕|桌面|主页")) { global(GLOBAL_ACTION_HOME, "回桌面啦"); return; }
        if (has(v, "^(返回|后退|退回|上一步)")) { global(GLOBAL_ACTION_BACK, "返回"); return; }
        if (has(v, "回去|退下|躲起来|回边上|靠边|藏起来|走开")) { say("我在边上等你～", 1800); dock(null); return; }
        if (has(v, "最近任务|多任务|最近应用|后台任务")) { global(GLOBAL_ACTION_RECENTS, "最近用过的都在这儿"); return; }
        if (has(v, "通知")) { global(GLOBAL_ACTION_NOTIFICATIONS, "通知栏拉下来啦"); return; }
        if (has(v, "控制中心|快捷设置|快捷开关")) { global(GLOBAL_ACTION_QUICK_SETTINGS, "快捷开关在这儿"); return; }
        if (has(v, "锁屏|关屏|熄屏")) {
            if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN, "晚安～");
            else say("这台手机太老了，我锁不了屏", 2500);
            return;
        }
        if (has(v, "截屏|截图")) {
            if (Build.VERSION.SDK_INT >= 28) {
                hideBubbleNow();
                h.postDelayed(() -> global(GLOBAL_ACTION_TAKE_SCREENSHOT, "咔嚓！"), 300);
            } else say("这台手机太老了，我截不了图", 2500);
            return;
        }
        if (has(v, "分屏")) { global(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, "分屏啦"); return; }
        if (has(v, "关机菜单|电源菜单|重启")) { global(GLOBAL_ACTION_POWER_DIALOG, "电源菜单"); return; }

        // —— 打开应用 ——
        if ((m = mt("^(?:帮我)?(?:打开|启动|开一下|点开|进入|open)\\s*(.+)", v)) != null) {
            String name = m.group(1).trim();
            if (openSpecial(name)) return;
            if (!openApp(name)) say("手机里没找到「" + name + "」", 2500);
            return;
        }

        // —— 电话短信 ——
        if ((m = mt("给\\s*(.+?)\\s*打(?:个)?电话", v)) != null) { call(m.group(1)); return; }
        if ((m = mt("(?:打电话|打个电话|拨打|拨号|呼叫|call)\\s*(?:给)?\\s*(.+)", v)) != null) { call(m.group(1).trim()); return; }
        if (has(v, "^打电话$")) { start(new Intent(Intent.ACTION_DIAL)); return; }
        if ((m = mt("(?:发短信|发信息|短信)\\s*(?:给)?\\s*([^\\s,，:：]+)\\s*[,，:：]?\\s*(.*)", v)) != null) {
            sms(m.group(1), m.group(2));
            return;
        }

        // —— 屏幕操作 ——
        if ((m = mt("^(?:帮我)?(?:点击|点一下|按一下|点按|帮我点|帮我按|点)\\s*[「『\"“']?(.+?)[」』\"”']?$", v)) != null) {
            clickText(m.group(1).trim());
            return;
        }
        if ((m = mt("^(?:输入|打字|填写?|写上)\\s*[「『\"“']?(.+?)[」』\"”']?$", v)) != null) {
            typeText(m.group(1));
            return;
        }
        if (has(v, "读.{0,3}屏|屏幕上|念.{0,3}屏|看看屏幕|这页写了什么|屏幕写了什么")) { readScreen(); return; }

        // —— 媒体与硬件 ——
        if (has(v, "下一首|下一曲|切歌")) { media(KeyEvent.KEYCODE_MEDIA_NEXT, "下一首～"); return; }
        if (has(v, "上一首|上一曲")) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一首～"); return; }
        if (has(v, "暂停|停止播放|别放了|停一下")) { media(KeyEvent.KEYCODE_MEDIA_PAUSE, "暂停了"); return; }
        if (has(v, "继续播放|播放音乐|放歌|放音乐|^播放$|继续放")) { media(KeyEvent.KEYCODE_MEDIA_PLAY, "放起来～"); return; }
        if (has(v, "音量|声音|大声|小声|静音")) { volume(v); return; }
        if (has(v, "手电|闪光灯")) { torch(!has(v, "关")); return; }

        // —— 滑动 ——
        if (has(v, "往下翻|向下翻|下一页|往下滑|向下滑|下滑|往下滚|下一个|下一条")) { swipe(0, "往下翻～"); return; }
        if (has(v, "往上翻|向上翻|上一页|往上滑|向上滑|上滑|往上滚|上一个|上一条")) { swipe(1, "往上翻～"); return; }
        if (has(v, "左滑|向左滑|往左")) { swipe(2, "左滑～"); return; }
        if (has(v, "右滑|向右滑|往右")) { swipe(3, "右滑～"); return; }

        // —— 查找 ——
        if ((m = mt("^(?:搜索|搜一下|搜一搜|搜|查一下|查查|百度一下|百度)\\s*(.+)", v)) != null) {
            if (start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(m.group(1)))))) say("帮你搜「" + m.group(1) + "」", 2000);
            return;
        }
        if ((m = mt("翻译\\s*(.+)", v)) != null) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode("翻译 " + m.group(1)))));
            return;
        }
        if ((m = mt("(?:导航|带我去|怎么去|去往)\\s*(?:到|去)?\\s*(.+)", v)) != null) {
            String place = m.group(1).trim();
            Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)));
            geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(geo);
                say("去「" + place + "」，地图打开啦", 2500);
            } catch (Exception e) {
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode(place + " 地图"))));
            }
            return;
        }
        if (has(v, "天气")) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=" + Uri.encode("天气"))));
            say("我看不到窗外…帮你查查", 2000);
            return;
        }

        // —— 计算 ——
        if ((m = mt("^(?:算一?下|计算|算)?\\s*([\\d\\s.+\\-*/×÷xX^%()（）]+?)\\s*(?:[=＝?？]|等于几|等于多少|是多少)?$", v)) != null
                && m.group(1).matches(".*\\d.*") && m.group(1).matches(".*[+\\-*/×÷xX^%].*")) {
            String ex = m.group(1).replaceAll("[×xX]", "*").replace("÷", "/").replace("（", "(").replace("）", ")");
            try {
                say(m.group(1).trim() + " = " + fmt(Calc.eval(ex)), 6000);
            } catch (Exception e) {
                say("这道题把我绕晕了…", 2500);
            }
            return;
        }

        // —— 时间与状态 ——
        if (has(v, "几点|时间")) {
            Calendar c = Calendar.getInstance();
            int hr = c.get(Calendar.HOUR_OF_DAY);
            say(String.format(Locale.CHINA, "现在 %02d:%02d", hr, c.get(Calendar.MINUTE))
                    + (hr >= 23 || hr < 5 ? "\n该睡觉啦" : hr < 9 ? "\n早呀" : ""), 3000);
            return;
        }
        if (has(v, "几号|日期|星期几|周几|今天是")) {
            Calendar c = Calendar.getInstance();
            say(String.format(Locale.CHINA, "今天是 %d年%d月%d日，星期%s", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH), "日一二三四五六".charAt(c.get(Calendar.DAY_OF_WEEK) - 1)), 3500);
            return;
        }
        if (has(v, "电量|电池|还有多少电")) {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int lv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            say("电量 " + lv + "%" + (bm.isCharging() ? "，正在充电" : lv < 20 ? "，快去充电！" : ""), 3000);
            return;
        }

        // —— 文字 ——
        if ((m = mt("^复制\\s*(.+)", v)) != null) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("mocat", m.group(1)));
            say("复制好啦", 1800);
            return;
        }
        if ((m = mt("^(?:说|念|朗读|读)\\s*(.+)", v)) != null) {
            speak(m.group(1));
            say("「" + m.group(1) + "」", 3000);
            return;
        }

        // —— 猫自己的事 ——
        if (has(v, "震动|振动")) {
            try { if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 80, 60, 80, 60, 200}, -1)); } catch (Exception ignored) { }
            say("嗡嗡嗡～", 1500);
            return;
        }
        if (has(v, "过来|出来|来这|醒醒|醒来")) {
            meow.play(0);
            if (mode == Mode.DOCK) comeOut(null);
            else cat.sleeping = false;
            say("来啦来啦", 1800);
            return;
        }
        if (has(v, "睡觉|睡吧|晚安|休息")) {
            dock(() -> {
                cat.sleeping = true;
                say("晚安…呼…", 2200);
            });
            return;
        }
        if (has(v, "跳舞|跳个舞|转圈|卖萌|打滚")) {
            goBusy(() -> {
                meow.play(1);
                cat.dance();
                cat.hearts(5);
                say("看我看我！", 2000);
            });
            return;
        }
        if (has(v, "帮助|你会什么|会干嘛|能做什么|会做什么|help")) { say(HELP, 10000); return; }
        if (has(v, "名字|叫什么|你是谁")) { say("我叫墨墨，是宣纸上的一滴墨变的", 3500); return; }
        if (has(v, "^(喵+|你好|嗨|hi|hello|在吗|在不在)")) {
            meow.play(0);
            cat.hearts(2);
            say("喵～我在", 1800);
            return;
        }
        if (has(v, "拍照|相机|拍张照")) { start(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return; }

        // —— 兜底：也许说的就是应用名 ——
        if (v.length() <= 8 && openApp(v)) return;
        say("喵？「" + v + "」我还不会…\n说「你会什么」看看我的本事", 4000);
    }

    /** 安全的四则运算，不执行任何代码 */
    static final class Calc {
        private final String s;
        private int i;

        private Calc(String s) { this.s = s; }

        static double eval(String in) {
            Calc c = new Calc(in.replaceAll("\\s", ""));
            double v = c.expr();
            if (c.i != c.s.length() || Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException();
            return v;
        }

        private boolean at(char ch) { return i < s.length() && s.charAt(i) == ch; }

        private double expr() {
            double v = term();
            while (true) {
                if (at('+')) { i++; v += term(); }
                else if (at('-')) { i++; v -= term(); }
                else return v;
            }
        }

        private double term() {
            double v = power();
            while (true) {
                if (at('*')) { i++; v *= power(); }
                else if (at('/')) { i++; v /= power(); }
                else if (at('%')) { i++; v %= power(); }
                else return v;
            }
        }

        private double power() {
            double b = unary();
            if (at('^')) { i++; return Math.pow(b, power()); }
            return b;
        }

        private double unary() {
            if (at('-')) { i++; return -unary(); }
            if (at('+')) { i++; return unary(); }
            return atom();
        }

        private double atom() {
            if (at('(')) {
                i++;
                double v = expr();
                if (!at(')')) throw new IllegalArgumentException();
                i++;
                return v;
            }
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(st, i));
        }
    }
}
