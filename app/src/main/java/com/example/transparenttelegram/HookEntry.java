package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ДИАГНОСТИЧЕСКАЯ версия. Прежние 2 попытки (флаги/фон окна, затем
 * + setFormat(TRANSLUCENT)) выполнялись без единого исключения
 * (подтверждено логами LSPosed на реальном устройстве: "Hooks installed",
 * "Window prepared", множество "Transparency applied" без единого
 * "failed"), но визуально ничего не менялось. Значит наш код в принципе
 * доходит до нужных точек, но либо:
 *   а) что-то перерисовывает поверх ПОСЛЕ нас (асинхронно, после
 *      onResume -- например отложенная загрузка темы/обоев Telegram),
 *   б) реальный "непрозрачный слой" рисуется не в Window/DecorView и не
 *      в первых 1-2 уровнях View, а глубже в иерархии, куда предыдущая
 *      версия не доставала.
 *
 * Вместо того чтобы гадать дальше вслепую, эта версия ОДИН РАЗ (через
 * 1.5 сек после onResume, чтобы дать Telegram время на асинхронную
 * инициализацию темы) дампит в лог LSPosed реальное состояние: флаги
 * окна, фон/альфу DecorView и всех его потомков на нескольких уровнях
 * вглубь. По этому логу будет видно ТОЧНО, какой View рисует
 * непрозрачный слой -- дальше патчим прицельно именно его, а не всё
 * подряд.
 *
 * Как читать лог: LSPosed Manager -> Logs, искать "[TransparentTelegram]
 * [DIAG]". Нужен весь блок целиком (может быть длинным).
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.beta",
            "org.telegram.messenger.web",
            "com.radolyn.ayugram",
            "com.radolyn.ayugram.web",
            "tw.nekomimi.nekogram",
            "nekox.messenger"
    ));

    private static final String LAUNCH_ACTIVITY_CLASS = "org.telegram.ui.LaunchActivity";

    private static final int ALPHA = 0x80;
    private static final int WINDOW_BACKGROUND_COLOR = Color.argb(ALPHA, 0, 0, 0);
    private static final int MAX_DEPTH = 8;      // насколько глубоко логируем дерево View
    private static final int MAX_CHILDREN = 6;   // максимум детей на уровень (чтобы не залить лог)

    // чтобы не дампить дерево при каждом onResume -- достаточно 1 раза за процесс
    private static volatile boolean diagnosticsDumped = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName = lpparam.packageName;
        XposedBridge.log("[TransparentTelegram] Loading: " + packageName);

        try {
            Class<?> launchActivityClass = XposedHelpers.findClass(
                    LAUNCH_ACTIVITY_CLASS, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(launchActivityClass, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                prepareWindow((Activity) param.thisObject);
                                XposedBridge.log("[TransparentTelegram] Window prepared: " + packageName);
                            } catch (Throwable t) {
                                XposedBridge.log("[TransparentTelegram] before onCreate failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                applyTransparency((Activity) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[TransparentTelegram] after onCreate failed: " + t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(launchActivityClass, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Activity activity = (Activity) param.thisObject;
                            try {
                                applyTransparency(activity);
                            } catch (Throwable t) {
                                XposedBridge.log("[TransparentTelegram] onResume failed: " + t);
                            }

                            // диагностический дамп -- один раз за процесс, с задержкой,
                            // чтобы дать Telegram доиграть свою асинхронную инициализацию
                            if (!diagnosticsDumped) {
                                diagnosticsDumped = true;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            dumpDiagnostics(activity);
                                        } catch (Throwable t) {
                                            XposedBridge.log("[TransparentTelegram][DIAG] dump failed: " + t);
                                        }
                                    }
                                }, 1500);
                            }
                        }
                    });

            XposedBridge.log("[TransparentTelegram] Hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Failed for " + packageName + ": " + t);
        }
    }

    private void prepareWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));
    }

    private void applyTransparency(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));

        View root = window.getDecorView();
        if (root != null) {
            makeViewTransparent(root);
        }

        XposedBridge.log("[TransparentTelegram] Transparency applied");
    }

    private void makeViewTransparent(View view) {
        if (view == null) {
            return;
        }
        if (view.getBackground() != null) {
            view.setBackgroundColor(Color.argb(ALPHA, 0, 0, 0));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getChildCount() == 1) {
                View child = group.getChildAt(0);
                if (child instanceof ViewGroup) {
                    child.setBackgroundColor(Color.argb(ALPHA, 0, 0, 0));
                }
            }
        }
    }

    // ==================== ДИАГНОСТИКА ====================

    private void dumpDiagnostics(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            XposedBridge.log("[TransparentTelegram][DIAG] activity is null/finishing, skip");
            return;
        }

        Window window = activity.getWindow();
        WindowManager.LayoutParams attrs = window.getAttributes();

        XposedBridge.log("[TransparentTelegram][DIAG] ===== DUMP START =====");
        XposedBridge.log("[TransparentTelegram][DIAG] activity=" + activity.getClass().getName());
        XposedBridge.log("[TransparentTelegram][DIAG] window.flags=0x" + Integer.toHexString(attrs.flags)
                + " (FLAG_SHOW_WALLPAPER set=" + ((attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0) + ")");
        XposedBridge.log("[TransparentTelegram][DIAG] window.dimAmount=" + attrs.dimAmount);
        XposedBridge.log("[TransparentTelegram][DIAG] window.alpha=" + attrs.alpha);

        View decor = window.getDecorView();
        XposedBridge.log("[TransparentTelegram][DIAG] decorView background=" + describeDrawable(decor.getBackground()));
        XposedBridge.log("[TransparentTelegram][DIAG] decorView alpha=" + decor.getAlpha()
                + " visibility=" + decor.getVisibility());

        dumpViewTree(decor, 0);

        XposedBridge.log("[TransparentTelegram][DIAG] ===== DUMP END =====");
    }

    private void dumpViewTree(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        String line = indent + "[" + depth + "] " + view.getClass().getName()
                + " bg=" + describeDrawable(view.getBackground())
                + " alpha=" + view.getAlpha()
                + " vis=" + visibilityToString(view.getVisibility())
                + " size=" + view.getWidth() + "x" + view.getHeight();
        XposedBridge.log("[TransparentTelegram][DIAG] " + line);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), MAX_CHILDREN);
            for (int i = 0; i < count; i++) {
                dumpViewTree(group.getChildAt(i), depth + 1);
            }
            if (group.getChildCount() > MAX_CHILDREN) {
                XposedBridge.log("[TransparentTelegram][DIAG] " + indent + "  ... ещё "
                        + (group.getChildCount() - MAX_CHILDREN) + " детей не показано");
            }
        }
    }

    private String describeDrawable(Drawable d) {
        if (d == null) {
            return "null";
        }
        if (d instanceof ColorDrawable) {
            int color = ((ColorDrawable) d).getColor();
            return String.format("ColorDrawable(#%08X, alpha=%d)", color, Color.alpha(color));
        }
        return d.getClass().getName() + " (opacity=" + d.getOpacity() + ", alpha=" + d.getAlpha() + ")";
    }

    private String visibilityToString(int v) {
        switch (v) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return String.valueOf(v);
        }
    }
}
