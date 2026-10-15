package com.example.modemtest;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ModemTest v1.6
 *  1) 自动拨号，三种模式：
 *       ① 单号码（默认卡）
 *       ② 两个号码轮流（默认卡）          A→B→A→B
 *       ③ 双卡绑定轮流  ← SIM1 打号码1、SIM2 打号码2，轮流 SIM1→SIM2→SIM1
 *  2) 切换网络模式 NR/LTE/WCDMA/GSM
 *  3) ping 时延（多目标 + 曲线）
 */
public class MainActivity extends Activity {

    private static final String PREF = "modemtest";
    private static final int REQ = 1001;

    private EditText etPhone1, etPhone2, etTimes, etTalk, etGap;
    private CheckBox cbRound, cbSimBind, cbAutoHangup;
    private Button btnStart, btnStop, btnHangup, btnPingStart, btnPingStop;
    private TextView tvStatus, tvLog, tvPingResult, tvSimInfo;
    private SharedPreferences sp;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean dialing = false;
    private int dialIndex = 0, dialTotal = 0;
    private long talkMs = 0, gapMs = 0;
    private int roundIdx = 0;          // 0 = 该打 号码1/SIM1，1 = 号码2/SIM2
    private int count1 = 0, count2 = 0;
    private String phone1 = "", phone2 = "";

    // 双卡账号缓存
    private final List<PhoneAccountHandle> simHandles = new ArrayList<>();
    private final List<String> simLabels = new ArrayList<>();

    private final List<PingTarget> pingTargets = new ArrayList<>();
    private boolean pinging = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        // 兜底：构建 UI 期间任何异常都先显示一个可滚动的错误页，而不是直接闪退
        View content;
        try {
            content = buildUi();
        } catch (Throwable t) {
            content = buildErrorUi(t);
        }
        setContentView(content);
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            log("包名: " + getPackageName() + "  版本: " + pi.versionName + " (code " + pi.versionCode + ")");
        } catch (Exception e) {
            log("包名: " + getPackageName());
        }
        log("无障碍服务: " + (HangupService.isEnabled(MainActivity.this) ? "已开启 ✓" : "未开启（自动挂断需要它）"));
        try { refreshSim(); } catch (Throwable t) { log("refreshSim 异常: " + t); }
        try { askPermissions(); } catch (Throwable t) { log("askPermissions 异常: " + t); }
    }

    /** UI 构建失败时的降级界面：把堆栈显示出来，方便定位 */
    private View buildErrorUi(Throwable t) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setText("UI 构建失败（请把下面内容反馈给开发）：\n\n" + t + "\n\n" +
                android.util.Log.getStackTraceString(t));
        sv.addView(tv);
        return sv;
    }

    @Override protected void onResume() {
        super.onResume();
        if (HangupService.isEnabled(MainActivity.this)) log("无障碍服务: 已开启 ✓");
        refreshSim();
    }

    // ---- 全局崩溃兜底：先打 logcat，再由系统默认处理 ----
    static {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try { android.util.Log.e("ModemTestCrash", "uncaught", e); } catch (Throwable ignore) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    // ================================================================= UI（深色卡片式）

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        addTitle(root, "自动拨号", "填号码 → 设次数/时长/间隔 → 开始，到点自动挂断");
        root.addView(tag("号码1（单号码/轮流/双卡模式都用它）"));
        etPhone1 = styledEdit(sp.getString("phone1", ""), 1f, "必填");
        root.addView(etPhone1, new LinearLayout.LayoutParams(-1, -2));
        root.addView(tag("号码2（双卡模式 = 给 SIM2 拨的号；留空则只打号码1）"));
        etPhone2 = styledEdit(sp.getString("phone2", ""), 1f, "选填");
        root.addView(etPhone2, new LinearLayout.LayoutParams(-1, -2));

        tvSimInfo = new TextView(this);
        tvSimInfo.setTextSize(11.5f);
        tvSimInfo.setTextColor(C_SUB);
        tvSimInfo.setPadding(dp(2), dp(6), dp(2), dp(6));
        root.addView(tvSimInfo);

        LinearLayout rSim = row();
        rSim.addView(styledBtn("刷新卡", C_ACCENT, new View.OnClickListener(){ public void onClick(View v){ buzz(); refreshSim(); }}));
        rSim.addView(styledBtn("开无障碍", C_ACCENT, new View.OnClickListener(){ public void onClick(View v){ buzz(); openAccessibilitySettings(); }}));
        root.addView(rSim);

        LinearLayout r2 = row();
        r2.addView(numBox("次数", etTimes = styledEdit(String.valueOf(sp.getInt("times", 10)), 1f, null), 1f));
        r2.addView(numBox("通话(秒)", etTalk = styledEdit(String.valueOf(sp.getInt("talk", 10)), 1f, null), 1f));
        r2.addView(numBox("间隔(秒)", etGap = styledEdit(String.valueOf(sp.getInt("gap", 10)), 1f, null), 1f));
        root.addView(r2);

        cbAutoHangup = styledCheck(sp.getBoolean("autohangup", true), "到点自动挂断");
        root.addView(cbAutoHangup);
        cbRound = styledCheck(sp.getBoolean("round", true), "两个号码轮流打（号码1 → 号码2 → …）");
        root.addView(cbRound);
        cbSimBind = styledCheck(sp.getBoolean("simbind", false), "双卡绑定轮流：SIM1 拨号码1、SIM2 拨号码2");
        root.addView(cbSimBind);

        LinearLayout r3 = row();
        btnStart  = styledBtn("开始", C_GO, new View.OnClickListener(){ public void onClick(View v){ buzz(); startDial(); }});
        btnStop   = styledBtn("停止", C_STOP, new View.OnClickListener(){ public void onClick(View v){ buzz(); stopDial(); }});
        btnHangup = styledBtn("挂断", C_WARN, new View.OnClickListener(){ public void onClick(View v){ buzz(); hangup(); }});
        r3.addView(btnStart); r3.addView(btnStop); r3.addView(btnHangup);
        root.addView(r3);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12.5f);
        tvStatus.setTextColor(C_ACCENT);
        tvStatus.setPadding(dp(2), dp(6), dp(2), dp(2));
        root.addView(tvStatus);

        addTitle(root, "ping 时延", "多目标 · 实时时延与丢包");
        LinearLayout r5 = row();
        final EditText etPing = styledEdit("223.5.5.5", 1f, "目标 IP / 域名");
        r5.addView(etPing);
        r5.addView(styledBtn("添加", C_ACCENT, new View.OnClickListener(){ public void onClick(View v){
            String s = etPing.getText().toString().trim();
            if (s.isEmpty()) { status("先填目标地址"); buzz(60); return; }
            if (hasTarget(s)) { status("已在列表：" + s); buzz(60); return; }
            pingTargets.add(new PingTarget(s)); refreshPingView();
            status("已添加 " + s); buzz();
        }}));
        root.addView(r5);
        LinearLayout r5b = row();
        btnPingStart = styledBtn("开始", C_GO, new View.OnClickListener(){ public void onClick(View v){ buzz(); startPing(); }});
        btnPingStop  = styledBtn("停止", C_STOP, new View.OnClickListener(){ public void onClick(View v){ buzz(); stopPing(); }});
        r5b.addView(btnPingStart); r5b.addView(btnPingStop);
        root.addView(r5b);

        tvPingResult = new TextView(this);
        tvPingResult.setTextSize(11.5f);
        tvPingResult.setTypeface(Typeface.MONOSPACE);
        tvPingResult.setTextColor(C_TEXT);
        tvPingResult.setBackgroundDrawable(round(C_FIELD, dp(10)));
        tvPingResult.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(tvPingResult);

        addTitle(root, "运行日志", "拨号 / 挂断 / 权限 / 异常");
        tvLog = new TextView(this);
        tvLog.setTextSize(10.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(C_TEXT);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundDrawable(round(C_FIELD, dp(10)));
        sv.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(tvLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(220));
        lp.topMargin = dp(2);
        root.addView(sv, lp);

        // 整页可滚动
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(C_BG);
        page.addView(root);
        return page;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return r;
    }


    /** 数字输入小格：标签在上、输入框在下 */
    private LinearLayout numBox(String label, EditText et, float w) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(11f);
        lb.setTextColor(C_SUB);
        lb.setPadding(dp(2), dp(2), dp(2), dp(2));
        box.addView(lb);
        et.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        box.addView(et);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, -2, w));
        return box;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            log("已打开辅助功能设置 → 找到「ModemTest」并打开");
        } catch (Throwable t) { log("打不开辅助功能设置: " + t); }
    }

    // ================================================================= 双卡探测
    private void refreshSim() {
        simHandles.clear();
        simLabels.clear();
        TelecomManager tcm = (TelecomManager) getSystemService(TELECOM_SERVICE);
        if (tcm == null) { tvSimInfo.setText("SIM: 读不到 TelecomManager"); return; }
        try {
            List<PhoneAccountHandle> handles = tcm.getCallCapablePhoneAccounts();
            if (handles != null) {
                for (PhoneAccountHandle h : handles) {
                    if (h == null) continue;
                    String label = null;
                    try {
                        PhoneAccount pa = tcm.getPhoneAccount(h);
                        if (pa != null && pa.getLabel() != null) label = String.valueOf(pa.getLabel());
                    } catch (Throwable ignore) {}
                    if (label == null) label = h.getId();
                    int idx = simLabels.size() + 1;
                    simLabels.add("SIM" + idx + ":" + label);
                    simHandles.add(h);
                    log("发现卡 " + idx + ": " + label);
                }
            }
        } catch (Throwable t) {
            log("枚举 PhoneAccount 失败: " + t);
        }
        String sub = "";
        try {
            SubscriptionManager sm = (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm != null) {
                List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
                if (infos != null) {
                    StringBuilder sb = new StringBuilder();
                    for (SubscriptionInfo si : infos) {
                        sb.append("[slot").append(si.getSimSlotIndex()).append("] ")
                          .append(si.getDisplayName()).append("  ");
                    }
                    sub = sb.toString();
                }
            }
        } catch (Throwable ignore) {}
        if (simHandles.isEmpty()) {
            tvSimInfo.setText("未发现可拨号 SIM（需要电话权限，可点「刷新卡」）。" +
                    (sub.isEmpty() ? "" : "\n系统里的卡: " + sub));
        } else {
            tvSimInfo.setText("可拨号卡: " + simLabels.toString() + (sub.isEmpty() ? "" : "\n系统卡: " + sub));
        }
    }

    // ================================================================= 1 自动拨号
    private void startDial() {
        if (dialing) { toast("正在拨号中"); return; }
        phone1 = etPhone1.getText().toString().trim();
        phone2 = etPhone2.getText().toString().trim();
        final boolean simBind = cbSimBind.isChecked();

        if (simBind) {
            if (phone1.isEmpty() || phone2.isEmpty()) { toast("双卡绑定模式：号码1、号码2 都要填"); return; }
            if (simHandles.size() < 2) {
                toast("只找到 " + simHandles.size() + " 张可拨号卡，需要两张");
                log("✗ 双卡绑定不可用：可拨号卡 " + simHandles.size() + " 个（点「刷新卡」）");
                return;
            }
        } else {
            if (phone1.isEmpty()) { toast("号码1 必填"); return; }
            if (phone2.isEmpty() && cbRound.isChecked()) { toast("轮流打需要两个号码"); return; }
        }
        try {
            dialTotal = Integer.parseInt(etTimes.getText().toString().trim());
            talkMs = Long.parseLong(etTalk.getText().toString().trim()) * 1000L;
            gapMs = Long.parseLong(etGap.getText().toString().trim()) * 1000L;
        } catch (Exception e) { toast("次数/时长/间隔要填数字"); return; }
        if (dialTotal <= 0) { toast("次数要 > 0"); return; }
        if (talkMs < 1000) { toast("通话时长至少 1 秒"); return; }

        sp.edit()
          .putString("phone1", phone1).putString("phone2", phone2)
          .putInt("times", dialTotal)
          .putInt("talk", (int) (talkMs / 1000)).putInt("gap", (int) (gapMs / 1000))
          .putBoolean("autohangup", cbAutoHangup.isChecked())
          .putBoolean("round", cbRound.isChecked())
          .putBoolean("simbind", cbSimBind.isChecked()).apply();

        dialing = true;
        dialIndex = 0; count1 = 0; count2 = 0; roundIdx = 0;
        log("===== 开始 =====");
        if (simBind) {
            log("模式: 双卡绑定轮流 | " + simLabels.get(0) + "→号码1 " + phone1
                    + " | " + simLabels.get(1) + "→号码2 " + phone2);
        } else {
            log("号码1: " + phone1 + "  号码2: " + (phone2.isEmpty() ? "(无)" : phone2));
            log("模式: " + (cbRound.isChecked() && !phone2.isEmpty() ? "轮流拨打" : "只打号码1"));
        }
        log("每号 " + dialTotal + " 通 | 通话 " + (talkMs / 1000) + "s + 间隔 " + (gapMs / 1000) + "s");
        if (!HangupService.isEnabled(MainActivity.this)) {
            log("⚠ 无障碍未开启 → 到点无法自动挂断（点「开无障碍」）");
        }
        dialNext();
    }

    private void finishDial() {
        dialing = false;
        status("全部完成（" + count1 + " / " + count2 + "）");
        log("===== 结束：共拨 " + (count1 + count2) + " 通 =====");
    }

    /** 周期 = 通话 talkMs + 间隔 gapMs，从拨号那一刻起算 */
    private void dialNext() {
        if (!dialing) return;

        final String number;
        final String who;
        final PhoneAccountHandle handle;

        if (cbSimBind.isChecked()) {
            // 双卡绑定：roundIdx==0 → SIM1+号码1；==1 → SIM2+号码2
            if (count1 >= dialTotal && count2 >= dialTotal) { finishDial(); return; }
            if (roundIdx == 0 && count1 < dialTotal) {
                handle = simHandles.get(0); who = simLabels.get(0) + "→号码1"; number = phone1;
                count1++; roundIdx = 1;
            } else if (count2 < dialTotal) {
                handle = simHandles.get(1); who = simLabels.get(1) + "→号码2"; number = phone2;
                count2++; roundIdx = 0;
            } else if (count1 < dialTotal) {
                handle = simHandles.get(0); who = simLabels.get(0) + "→号码1"; number = phone1;
                count1++; roundIdx = 1;
            } else { finishDial(); return; }
        } else {
            handle = null;
            boolean two = cbRound.isChecked() && !phone2.isEmpty();
            if (!two) {
                if (count1 >= dialTotal) { finishDial(); return; }
                number = phone1; who = "号码1"; count1++;
            } else {
                if (roundIdx == 0 && count1 < dialTotal) {
                    number = phone1; who = "号码1"; count1++; roundIdx = 1;
                } else if (count2 < dialTotal) {
                    number = phone2; who = "号码2"; count2++; roundIdx = 0;
                } else if (count1 < dialTotal) {
                    number = phone1; who = "号码1"; count1++; roundIdx = 1;
                } else { finishDial(); return; }
            }
        }

        dialIndex++;
        status("拨打 " + who + " → " + number + "（第 " + dialIndex + " 通）");
        log("[" + dialIndex + "] " + who + " -> " + number + (handle != null ? "  (指定卡)" : ""));
        try {
            placeCall(number, handle);
        } catch (Exception e) {
            log("拨打失败: " + e);
            status("拨打失败: " + e.getMessage());
            dialing = false;
            return;
        }
        ui.postDelayed(new Runnable(){ public void run(){ hangup(); }}, talkMs);
        ui.postDelayed(new Runnable(){ public void run(){ dialNext(); }}, talkMs + gapMs);
    }

    /**
     * 拨号：handle != null → placeCall 指定 SIM；否则 ACTION_CALL（默认卡）
     */
    private void placeCall(String number, PhoneAccountHandle handle) {
        Uri uri = Uri.fromParts("tel", number, null);
        if (handle != null) {
            TelecomManager tcm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tcm == null) throw new IllegalStateException("no TelecomManager");
            android.os.Bundle extras = new android.os.Bundle();
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
            tcm.placeCall(uri, extras);
            log("  placeCall 指定卡: " + handle.getId());
        } else {
            Intent it = new Intent(Intent.ACTION_CALL, uri);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        }
    }

    private void stopDial() {
        if (!dialing) { toast("没有在进行"); return; }
        dialing = false;
        status("已手动停止（" + count1 + " / " + count2 + "）");
        log("手动停止（已拨 " + count1 + " / " + count2 + "）");
        hangup();
    }

    /**
     * 挂断四级兜底：endCall() → ITelephony 反射 → getITelephony 反射 → 无障碍点击
     */
    private void hangup() {
        if (!cbAutoHangup.isChecked()) return;
        new Thread(new Runnable(){ public void run(){
            String tried = "";
            try {
                TelecomManager tcm = (TelecomManager) getSystemService(TELECOM_SERVICE);
                if (tcm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tried += "endCall() ";
                    if (tcm.endCall()) { log("挂断成功 (TelecomManager.endCall)"); return; }
                    log("endCall() 返回 false（权限/appops 被 ROM 拦）");
                }
            } catch (Throwable t) {
                log("endCall() 异常: " + t.getClass().getSimpleName());
            }
            if (!HangupService.isEnabled(MainActivity.this)) {
                log("挂断失败：" + tried.trim() + " 均不可用");
                log("  → 点「开无障碍」打开辅助功能里的 ModemTest");
                status("挂断失败");
                return;
            }
            log("走无障碍点击挂断按钮...");
            final boolean ok = HangupService.clickHangup();
            ui.post(new Runnable(){ public void run(){
                if (ok) { log("挂断成功 (无障碍点击)"); status("已挂断"); }
                else { log("挂断失败 (无障碍没找到挂断按钮)"); status("挂断失败"); }
            }});
        }}).start();
    }




    // ================================================================= 3 ping
    private boolean hasTarget(String host) {
        for (PingTarget t : pingTargets) if (t.host.equals(host)) return true;
        return false;
    }

    private void refreshPingView() {
        StringBuilder sb = new StringBuilder();
        for (PingTarget t : pingTargets) sb.append(t.line()).append('\n');
        tvPingResult.setText(sb.toString());
    }

    private void startPing() {
        if (pinging) { toast("ping 进行中"); return; }
        if (pingTargets.isEmpty()) pingTargets.add(new PingTarget("223.5.5.5"));
        pinging = true;
        log("开始 ping: " + pingTargets.size() + " 个目标");
        new Thread(new Runnable(){ public void run(){
            while (pinging) {
                for (PingTarget t : pingTargets) t.doPing();
                ui.post(new Runnable(){ public void run(){ refreshPingView(); }});
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }}).start();
    }

    private void stopPing() { pinging = false; log("停止 ping"); }

    private static class PingTarget {
        String host;
        int sent = 0, recv = 0;
        double min = -1, avg = -1, max = -1;
        String lastErr = "";
        final List<Double> curve = new ArrayList<>();
        static final int CURVE_MAX = 30;

        PingTarget(String h) { host = h; }

        String line() {
            String head = String.format(Locale.US, "%-16s", host.length() > 16 ? host.substring(0, 16) : host);
            if (recv == 0 && lastErr.isEmpty()) return head + "等待中...";
            if (recv == 0) return head + "不可达 (" + lastErr + ")";
            double loss = 100.0 * (sent - recv) / sent;
            return head + String.format(Locale.US, "avg %6.1f  max %6.1f  loss %5.1f%% (%d/%d) %s",
                    avg, max, loss, recv, sent, sparkline());
        }

        String sparkline() {
            if (curve.isEmpty()) return "";
            double mn = Double.MAX_VALUE, mx = 0;
            for (double d : curve) { mn = Math.min(mn, d); mx = Math.max(mx, d); }
            double span = Math.max(mx - mn, 1.0);
            String bars = "▁▂▃▄▅▆▇█";
            StringBuilder sb = new StringBuilder();
            int n = Math.min(curve.size(), 20);
            for (int i = curve.size() - n; i < curve.size(); i++) {
                int idx = (int) ((curve.get(i) - mn) / span * (bars.length() - 1));
                sb.append(bars.charAt(Math.max(0, Math.min(bars.length() - 1, idx))));
            }
            return sb.toString();
        }

        void doPing() {
            try {
                Process p = Runtime.getRuntime().exec("ping -c 2 -W 2 " + host);
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String l; List<Double> rtts = new ArrayList<>();
                StringBuilder err = new StringBuilder();
                while ((l = br.readLine()) != null) {
                    if (l.contains("time=")) {
                        int i = l.indexOf("time=");
                        String s = l.substring(i + 5).split("[ ]+")[0].replace("ms", "").trim();
                        try { rtts.add(Double.parseDouble(s)); } catch (Exception ignore) {}
                    }
                    if (l.toLowerCase().contains("unreachable") || l.toLowerCase().contains("unknown host")) {
                        err.append(l).append(" ");
                    }
                }
                p.waitFor();
                sent += 2;
                recv += rtts.size();
                for (double d : rtts) {
                    curve.add(d);
                    if (curve.size() > CURVE_MAX) curve.remove(0);
                    min = min < 0 ? d : Math.min(min, d);
                    max = Math.max(max, d);
                }
                if (!rtts.isEmpty()) {
                    double sum = 0; for (double d : rtts) sum += d;
                    avg = avg < 0 ? sum / rtts.size() : (avg * 0.7 + (sum / rtts.size()) * 0.3);
                } else if (err.length() > 0) {
                    lastErr = err.toString().trim();
                }
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName();
            }
        }
    }

    // ================================================================= 权限
    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(need.toArray(new String[0]), REQ);
        } else {
            log("运行时权限已全部授予 ✓");
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] ps, int[] gr) {
        super.onRequestPermissionsResult(rc, ps, gr);
        for (int i = 0; i < ps.length; i++) {
            log("权限 " + ps[i].replace("android.permission.", "") + " -> " +
                    (gr[i] == PackageManager.PERMISSION_GRANTED ? "已授权" : "拒绝"));
        }
        refreshSim();
    }

    // ================================================================= 小工具
    private String netTypeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA: return "3G WCDMA";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G LTE";
            case 20: return "5G NR";
            default: return "type#" + t;
        }
    }

    private void log(final String s) {
        ui.post(new Runnable(){ public void run(){
            tvLog.append(s + "\n");
            if (tvLog.getParent() instanceof ScrollView) {
                ((ScrollView) tvLog.getParent()).fullScroll(View.FOCUS_DOWN);
            }
        }});
    }
    private void status(final String s) {
        ui.post(new Runnable(){ public void run(){
            tvStatus.setText(s);
            // 按钮启用态跟随状态：拨号中禁止再点「开始」，非拨号时禁止「停止」
            if (btnStart != null)  btnStart.setEnabled(!dialing);
            if (btnStop != null)   btnStop.setEnabled(dialing);
            if (btnHangup != null) btnHangup.setEnabled(true);
        }});
    }
    private void toast(final String s) { ui.post(new Runnable(){ public void run(){ Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); }}); }

    /** 点击震动反馈（轻，20ms） */
    private void buzz() { buzz(20); }
    private void buzz(final int ms) {
        ui.post(new Runnable(){ public void run(){
            try {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(ms);
            } catch (Throwable ignore) {}
        }});
    }

    private EditText edit(String s, float w) {
        EditText e = new EditText(this);
        e.setText(s); e.setTextSize(13f);
        e.setSingleLine(true);
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private TextView section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setPadding(0, dp(10), 0, dp(4));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
    private TextView label(String s, int w) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(13f); t.setGravity(android.view.Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, -2));
        return t;
    }
    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(13f);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return b;
    }
    private LinearLayout.LayoutParams wrap(float w) { return new LinearLayout.LayoutParams(0, -2, w); }

    // ---------- 深色主题工具 ----------
    private static final int C_BG      = 0xFF12161C;
    private static final int C_TEXT    = 0xFFE6EDF3;
    private static final int C_SUB     = 0xFF8B98A5;
    private static final int C_FIELD   = 0xFF222B36;
    private static final int C_BORDER  = 0xFF2B3644;
    private static final int C_ACCENT  = 0xFF2F81F7;
    private static final int C_GO      = 0xFF238636;
    private static final int C_STOP    = 0xFFDA3633;
    private static final int C_WARN    = 0xFF9E6A03;

    private GradientDrawable round(int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(1, C_BORDER);
        return d;
    }

    /** 深色输入框 */
    private EditText styledEdit(String s, float w, String hint) {
        EditText e = new EditText(this);
        e.setText(s);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_SUB);
        e.setTextSize(14f);
        if (hint != null) e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        e.setClickable(true);
        e.setBackgroundDrawable(round(C_FIELD, dp(10)));
        int p = dp(12);
        e.setPadding(p, p, p, p);
        e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    /** 彩色圆角按钮（带按压/禁用态） */
    private Button styledBtn(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14f);
        b.setAllCaps(false);
        b.setBackgroundDrawable(btnStates(color));
        int p = dp(6);
        b.setPadding(dp(10), p, dp(10), p);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return b;
    }

    /** 按钮三态：正常 / 按下(变亮+缩一圈) / 禁用(变灰) */
    private android.graphics.drawable.StateListDrawable btnStates(int color) {
        int r = dp(10);
        int pressed = blend(color, 0xFFFFFFFF, 0.25f);   // 按下变亮 25%
        int disabled = 0xFF3D444D;                        // 禁用深灰

        GradientDrawable n = new GradientDrawable(); n.setColor(color);     n.setCornerRadius(r);
        GradientDrawable p = new GradientDrawable(); p.setColor(pressed);   p.setCornerRadius(r); p.setStroke(dp(2), 0x66FFFFFF);
        GradientDrawable d = new GradientDrawable(); d.setColor(disabled);  d.setCornerRadius(r);

        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{-android.R.attr.state_enabled}, d);
        sl.addState(new int[]{android.R.attr.state_pressed}, p);
        sl.addState(new int[]{}, n);
        return sl;
    }

    /** 两色按比例混合 */
    private int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 分区标题（带副标题） */
    private void addTitle(LinearLayout root, String t, String sub) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(17f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(C_TEXT);
        tv.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(tv);
        if (sub != null) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(11f);
            s2.setTextColor(C_SUB);
            s2.setPadding(dp(2), dp(1), dp(2), dp(6));
            root.addView(s2);
        }
    }

    /** 小号灰字标签 */
    private TextView tag(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(12f);
        tv.setTextColor(C_SUB);
        tv.setPadding(dp(2), dp(4), dp(2), dp(3));
        return tv;
    }

    /** CheckBox 着色（内部 new，返回新对象） */
    private CheckBox styledCheck(boolean checked, String text) {
        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setText(text);
        cb.setTextSize(13f);
        cb.setTextColor(C_TEXT);
        cb.setPadding(dp(4), dp(5), dp(4), dp(5));
        return cb;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

}
