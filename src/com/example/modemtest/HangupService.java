package com.example.modemtest;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍挂断服务：不依赖任何电话权限。
 * 开启方式：设置 → 辅助功能 → ModemTest → 打开
 *
 * 工作方式：挂断时遍历当前窗口（系统通话界面），
 * 按 文本/描述/contentDescription 找「挂断 / 结束 / End call」按钮并 performClick。
 */
public class HangupService extends AccessibilityService {

    private static HangupService sInstance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理事件，只在外部主动调 clickHangup() 时工作
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    /** 服务是否已在系统设置里开启 */
    public static boolean isEnabled() {
        return sInstance != null;
    }

    /** 也检查系统设置里的开关状态（服务未绑定时用） */
    public static boolean isEnabled(Context ctx) {
        if (sInstance != null) return true;
        try {
            int enabled = Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;
            String me = ctx.getPackageName() + "/" + HangupService.class.getName();
            TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
            sp.setString(services);
            for (String s : sp) {
                if (s != null && s.equalsIgnoreCase(me)) return true;
            }
        } catch (Throwable ignore) { }
        return false;
    }

    /**
     * 尝试点击挂断按钮。返回 true 表示点了下去（不保证一定挂断成功，
     * 但找不到按钮会返回 false）。
     */
    public static boolean clickHangup() {
        HangupService s = sInstance;
        if (s == null) return false;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> hits = new ArrayList<>();
            collect(root, hits);
            if (hits.isEmpty()) return false;
            // 优先点可点击的、最像挂断的那个（第一个通常就是）
            for (AccessibilityNodeInfo n : hits) {
                if (n.isClickable()) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
            // 没有直接可点击的，往上找可点击的父节点
            for (AccessibilityNodeInfo n : hits) {
                AccessibilityNodeInfo p = n;
                for (int i = 0; i < 5 && p != null; i++) {
                    p = p.getParent();
                    if (p != null && p.isClickable()) {
                        return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        } catch (Throwable ignore) { }
        return false;
    }

    /** 关键字：中英文 + 常见 ROM 文案 */
    private static final String[] KEYS = {
            "挂断", "结束通话", "结束", "结束呼叫", "挂机", "拒绝",
            "End call", "end call", "End", "Hang up", "hang up", "Reject", "decline",
            "結束通話", "結束通話", "结束"
    };

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String t = text == null ? "" : text.toString().trim();
        String d = desc == null ? "" : desc.toString().trim();
        for (String k : KEYS) {
            if (t.equals(k) || d.equals(k) || t.equalsIgnoreCase(k) || d.equalsIgnoreCase(k)) {
                out.add(node);
                return;
            }
        }
        // 常见：按钮文本形如 "挂断" 或带图标，也匹配包含关系的（保守：只对短文本做包含）
        if ((t.length() <= 6 || d.length() <= 6)) {
            for (String k : KEYS) {
                if (k.length() >= 2 && (t.contains(k) || d.contains(k))) {
                    out.add(node);
                    return;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out);
        }
    }
}
