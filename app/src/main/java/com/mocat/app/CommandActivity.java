package com.mocat.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** 长按小猫弹出的指令面板：打字、语音或点常用指令。 */
public class CommandActivity extends Activity {
    private static final int REQ_VOICE = 7;
    private static final String[] CHIPS = {
            "打开微信", "回到桌面", "返回", "往下翻", "读一下屏幕", "点击 搜索",
            "打开手电筒", "音量调大", "暂停音乐", "10分钟后提醒我喝水", "明早7点叫我",
            "现在几点", "电量", "截屏", "跳个舞", "睡觉吧", "回去吧", "你会什么"};

    private EditText input;
    private boolean sent;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 18);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x4D1D1B16);
        root.setOnClickListener(v -> finish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setClickable(true);
        android.graphics.drawable.GradientDrawable bg = Ui.round(CatArt.PAPER, 0, 0, 0, this);
        float r = Ui.dp(this, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "跟墨墨说", 22, CatArt.INK, true);
        head.addView(title);
        TextView seal = Ui.text(this, "喵", 14, CatArt.PAPER, true);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(Ui.round(CatArt.SEAL, 0, 0, 4, this));
        seal.setRotation(7);
        LinearLayout.LayoutParams slp = Ui.lp(Ui.dp(this, 24), Ui.dp(this, 24));
        slp.leftMargin = Ui.dp(this, 8);
        head.addView(seal, slp);
        card.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("比如：打开支付宝、往下翻");
        input.setHintTextColor(CatArt.MUTE);
        input.setTextColor(CatArt.INK);
        input.setTextSize(16);
        input.setTypeface(Ui.serif(false));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        input.setBackground(Ui.round(CatArt.CARD, CatArt.INK, 1.5f, 25, this));
        input.setOnEditorActionListener((v, actionId, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submit(input.getText().toString());
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));

        boolean canVoice = !getPackageManager()
                .queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        if (canVoice) {
            TextView mic = Ui.button(this, "语音", false);
            mic.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            mic.setOnClickListener(v -> voice());
            LinearLayout.LayoutParams mlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 50));
            mlp.leftMargin = Ui.dp(this, 8);
            row.addView(mic, mlp);
        }
        TextView go = Ui.text(this, "说", 22, CatArt.PAPER, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(Ui.round(CatArt.SEAL, 0, 0, 10, this));
        go.setRotation(-4);
        go.setOnClickListener(v -> submit(input.getText().toString()));
        LinearLayout.LayoutParams glp = Ui.lp(Ui.dp(this, 50), Ui.dp(this, 50));
        glp.leftMargin = Ui.dp(this, 10);
        row.addView(go, glp);
        card.addView(row, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 14)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        for (String s : CHIPS) {
            TextView c = Ui.chip(this, s);
            c.setOnClickListener(v -> submit(s));
            LinearLayout.LayoutParams clp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
            clp.rightMargin = Ui.dp(this, 8);
            chips.addView(c, clp);
        }
        hs.addView(chips);
        card.addView(hs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 14)));

        TextView tip = Ui.text(this, canVoice ? "也可以点「语音」直接说" : "想用说的？点输入法键盘上的话筒键就行",
                13, CatArt.MUTE, false);
        card.addView(tip, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 10)));

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(card, flp);
        setContentView(root);

        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void voice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "跟墨墨说…");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "没找到语音识别，用输入法的话筒键吧", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) submit(r.get(0));
        }
    }

    private void submit(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        CatService s = CatService.get();
        if (s == null) {
            Toast.makeText(this, "先在墨猫首页打开无障碍服务", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            sent = true;
            s.postCommand(t);
        }
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
        finish();
    }

    @Override protected void onDestroy() {
        if (!sent) {
            CatService s = CatService.get();
            if (s != null) s.onPanelClosed();
        }
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }
}
