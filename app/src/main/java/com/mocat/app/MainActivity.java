package com.mocat.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 首页：领养说明 + 权限设置。 */
public class MainActivity extends Activity {
    private TextView st1, st2, st3, mainBtn;
    private final Meow meow = new Meow();
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("mocat", MODE_PRIVATE);
        meow.enabled = prefs.getBoolean("sound", true);
        int pad = Ui.dp(this, 24);
        int W = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 40));
        sv.addView(col);

        // 题字 + 印章
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.addView(Ui.text(this, "墨猫", 46, CatArt.INK, true));
        TextView seal = Ui.text(this, "喵", 16, CatArt.PAPER, true);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(Ui.round(CatArt.SEAL, 0, 0, 4, this));
        seal.setRotation(7);
        LinearLayout.LayoutParams slp = Ui.lp(Ui.dp(this, 28), Ui.dp(this, 28));
        slp.leftMargin = Ui.dp(this, 10);
        slp.topMargin = Ui.dp(this, 12);
        head.addView(seal, slp);
        col.addView(head);
        col.addView(Ui.text(this, "一只住在手机边上的水墨小猫。\n轻点它出来玩，点哪儿它去哪儿；长按它，跟它说要做什么。", 14, CatArt.MUTE, false),
                Ui.lp(W, WC, Ui.dp(this, 4)));

        PreviewView preview = new PreviewView(this);
        preview.setOnTap(() -> meow.play((int) (Math.random() * 3)));
        col.addView(preview, Ui.lp(W, Ui.dp(this, 180), Ui.dp(this, 8)));

        mainBtn = Ui.button(this, "叫墨墨出来", true);
        mainBtn.setOnClickListener(v -> callCat());
        col.addView(mainBtn, Ui.lp(W, Ui.dp(this, 52), Ui.dp(this, 8)));

        TextView talk = Ui.button(this, "跟它说句话试试", false);
        talk.setOnClickListener(v -> {
            CatService s = CatService.get();
            if (s == null) needService();
            else s.openPanel();
        });
        col.addView(talk, Ui.lp(W, Ui.dp(this, 48), Ui.dp(this, 10)));

        // 第一步
        LinearLayout c1 = card(col, "① 打开无障碍服务（必须）");
        st1 = status(c1);
        body(c1, "在设置里找到「已下载的应用」或「已安装的服务」→ 墨猫 → 打开开关。\n\n"
                + "开关是灰的、点不动？这是安卓 13 以后对外部安装应用的保护：点下面的「应用信息」，"
                + "在右上角 ⋮ 菜单里选「允许受限制的设置」，再回来打开开关。");
        buttons(c1, "去开启", v -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                "应用信息", v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));

        // 第二步
        LinearLayout c2 = card(col, "② 让它一直醒着（建议）");
        st2 = status(c2);
        body(c2, "国产手机会清理后台，墨猫可能被“赶走”。请允许它忽略电池优化；"
                + "小米、华为、荣耀、OPPO、vivo 还要在「手机管家 / 应用启动管理」里把墨猫设为允许自启动、后台不限制。");
        buttons(c2, "允许后台运行", v -> askBattery(), null, null);

        // 第三步
        LinearLayout c3 = card(col, "③ 打电话和找联系人（可选）");
        st3 = status(c3);
        body(c3, "授权后，说「打电话给妈妈」它会直接拨出去。不授权也能用，只是需要你自己按一下拨号键。");
        buttons(c3, "授权", v -> requestPermissions(new String[]{
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS}, 3), null, null);

        // 叫声开关
        LinearLayout c4 = card(col, "叫声");
        Switch sw = new Switch(this);
        sw.setText("被摸、被叫的时候“喵”一声");
        sw.setTextColor(CatArt.INK);
        sw.setTypeface(Ui.serif(false));
        sw.setChecked(meow.enabled);
        sw.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("sound", on).apply();
            meow.enabled = on;
            CatService s = CatService.get();
            if (s != null) s.setSound(on);
            if (on) meow.play(0);
        });
        c4.addView(sw, Ui.lp(W, Ui.dp(this, 44), Ui.dp(this, 4)));

        // 本事清单
        LinearLayout c5 = card(col, "它会做的事");
        body(c5, "怎么玩\n"
                + "· 平时它趴在屏幕边上，按住能拖到别的位置\n"
                + "· 轻点它跑出来，点哪儿它去哪儿\n"
                + "· 它出来时，长按屏幕某处，它会走过去帮你按一下\n"
                + "· 摸摸它会呼噜，双击它回边上\n"
                + "· 长按它，弹出指令框\n\n"
                + "指令举例\n"
                + "· 打开微信 / 打开设置 / 打开蓝牙\n"
                + "· 回到桌面 / 返回 / 最近任务 / 通知栏 / 截屏 / 锁屏\n"
                + "· 往下翻 / 往上翻 / 左滑 / 右滑\n"
                + "· 点击 搜索 / 点 赞 / 输入 你好\n"
                + "· 读一下屏幕\n"
                + "· 打开手电筒 / 关手电筒 / 音量调大 / 静音\n"
                + "· 暂停 / 播放音乐 / 下一首\n"
                + "· 打电话给 10086 / 给妈妈打电话 / 发短信给 妈妈 晚点回\n"
                + "· 10分钟后提醒我喝水 / 计时 5 分钟 / 明早7点叫我\n"
                + "· 现在几点 / 今天几号 / 电量 / 算 23*7+5\n"
                + "· 搜索 天气 / 导航去 西湖 / 复制 … / 说 …\n"
                + "· 跳个舞 / 睡觉吧 / 回去吧");

        TextView privacy = Ui.text(this, "墨猫没有联网权限。屏幕上的内容只在你下指令时临时读取，不保存、不上传。", 12, CatArt.MUTE, false);
        col.addView(privacy, Ui.lp(W, WC, Ui.dp(this, 18)));

        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean on = a11yOn();
        mark(st1, on, "已开启", "还没开启");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mark(st2, pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()), "已允许", "未设置");
        boolean perms = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        mark(st3, perms, "已授权", "未授权");
        mainBtn.setText(on ? "叫墨墨出来" : "先打开无障碍服务");
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        onResume();
    }

    private boolean a11yOn() {
        if (CatService.get() != null) return true;
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void callCat() {
        CatService s = CatService.get();
        if (s == null) {
            needService();
            return;
        }
        moveTaskToBack(true);
        s.summon();
    }

    private void needService() {
        Toast.makeText(this, "先打开墨猫的无障碍服务", Toast.LENGTH_LONG).show();
        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())))) return;
        }
        open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) { }
            return false;
        }
    }

    // ---------- 小积木 ----------

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        c.setPadding(p, p, p, p);
        c.setBackground(Ui.round(CatArt.CARD, CatArt.LINE, 1, 14, this));
        c.addView(Ui.text(this, title, 17, CatArt.INK, true));
        parent.addView(c, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 16)));
        return c;
    }

    private TextView status(LinearLayout card) {
        TextView t = Ui.text(this, "", 13, CatArt.MUTE, true);
        card.addView(t, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 4)));
        return t;
    }

    private void body(LinearLayout card, String s) {
        card.addView(Ui.text(this, s, 14, CatArt.INK, false),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 8)));
    }

    private void buttons(LinearLayout card, String a, View.OnClickListener la, String b, View.OnClickListener lb) {
        LinearLayout row = new LinearLayout(this);
        TextView ba = Ui.button(this, a, true);
        ba.setOnClickListener(la);
        row.addView(ba, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        if (b != null) {
            TextView bb = Ui.button(this, b, false);
            bb.setOnClickListener(lb);
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
            p.leftMargin = Ui.dp(this, 10);
            row.addView(bb, p);
        }
        card.addView(row, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 12)));
    }

    private void mark(TextView t, boolean ok, String yes, String no) {
        t.setText(ok ? "● " + yes : "○ " + no);
        t.setTextColor(ok ? 0xFF3C6E47 : CatArt.SEAL);
    }
}
