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

public class HookEntry implements IXposedHookLoadPackage {

    /*
     * ============================================================
     * TARGET PACKAGES
     * ============================================================
     */

    private static final Set<String> TARGET_PACKAGES =
            new HashSet<>(Arrays.asList(
                    "org.telegram.messenger",
                    "org.telegram.messenger.beta",
                    "org.telegram.messenger.web",

                    "com.radolyn.ayugram",
                    "com.radolyn.ayugram.web",

                    "tw.nekomimi.nekogram",
                    "nekox.messenger"
            ));

    private static final String LAUNCH_ACTIVITY_CLASS =
            "org.telegram.ui.LaunchActivity";

    /*
     * ============================================================
     * HANDLER
     * ============================================================
     */

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    /*
     * ============================================================
     * DIAGNOSTICS
     * ============================================================
     */

    private static final int MAX_DEPTH = 40;
    private static final int MAX_CHILDREN = 8;

    private static volatile boolean diagnosticsDumped = false;

    /*
     * ============================================================
     * LOAD PACKAGE
     * ============================================================
     */

    @Override
    public void handleLoadPackage(
            XC_LoadPackage.LoadPackageParam lpparam) {

        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName =
                lpparam.packageName;

        XposedBridge.log(
                "[TransparentTelegram] Loading: "
                        + packageName
        );

        try {

            /*
             * ----------------------------------------------------
             * Hook View backgrounds
             * ----------------------------------------------------
             *
             * Здесь мы специально перехватываем только:
             *
             * 1. DecorView
             * 2. Telegram blur drawable
             *
             * Остальные View НЕ изменяются.
             */

            hookViewBackgrounds();

            /*
             * ----------------------------------------------------
             * LaunchActivity
             * ----------------------------------------------------
             */

            Class<?> launchActivityClass =
                    XposedHelpers.findClass(
                            LAUNCH_ACTIVITY_CLASS,
                            lpparam.classLoader
                    );

            /*
             * ====================================================
             * onCreate
             * ====================================================
             */

            XposedHelpers.findAndHookMethod(
                    launchActivityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

                            try {

                                Activity activity =
                                        (Activity) param.thisObject;

                                prepareWindow(activity);

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "Window prepared: "
                                                + packageName
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "before onCreate failed: "
                                                + t
                                );
                            }
                        }

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            try {

                                Activity activity =
                                        (Activity) param.thisObject;

                                applyTransparency(activity);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "after onCreate failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            /*
             * ====================================================
             * onResume
             * ====================================================
             */

            XposedHelpers.findAndHookMethod(
                    launchActivityClass,
                    "onResume",
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            final Activity activity =
                                    (Activity) param.thisObject;

                            try {

                                applyTransparency(activity);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "onResume failed: "
                                                + t
                                );
                            }

                            /*
                             * Telegram может восстановить фон
                             * после layout/resume.
                             *
                             * Поэтому повторяем только очистку
                             * DecorView и blur.
                             */

                            scheduleApply(
                                    activity,
                                    100
                            );

                            scheduleApply(
                                    activity,
                                    500
                            );

                            scheduleApply(
                                    activity,
                                    1000
                            );

                            scheduleApply(
                                    activity,
                                    2000
                            );

                            scheduleApply(
                                    activity,
                                    3000
                            );

                            /*
                             * Диагностика.
                             */

                            if (!diagnosticsDumped) {

                                diagnosticsDumped = true;

                                MAIN_HANDLER.postDelayed(
                                        new Runnable() {

                                            @Override
                                            public void run() {

                                                try {

                                                    dumpDiagnostics(
                                                            activity
                                                    );

                                                } catch (Throwable t) {

                                                    XposedBridge.log(
                                                            "[TransparentTelegram]"
                                                                    + "[DIAG] "
                                                                    + "dump failed: "
                                                                    + t
                                                    );
                                                }
                                            }
                                        },
                                        1500
                                );
                            }
                        }
                    }
            );

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "Hooks installed for "
                            + packageName
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "Failed for "
                            + packageName
                            + ": "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * HOOK VIEW BACKGROUNDS
     * ============================================================
     */

    private void hookViewBackgrounds() {

        try {

            /*
             * ----------------------------------------------------
             * setBackground(Drawable)
             * ----------------------------------------------------
             */

            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setBackground",
                    Drawable.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

                            try {

                                View view =
                                        (View) param.thisObject;

                                Drawable drawable =
                                        (Drawable) param.args[0];

                                /*
                                 * DecorView
                                 */

                                if (isDecorView(view)) {

                                    param.args[0] =
                                            new ColorDrawable(
                                                    Color.TRANSPARENT
                                            );

                                    XposedBridge.log(
                                            "[TransparentTelegram] "
                                                    + "Blocked DecorView "
                                                    + "background"
                                    );

                                    return;
                                }

                                /*
                                 * Telegram blur
                                 */

                                if (isTelegramBlur(drawable)) {

                                    disableBlurDrawable(
                                            drawable
                                    );

                                    XposedBridge.log(
                                            "[TransparentTelegram] "
                                                    + "Blocked blur drawable: "
                                                    + view.getClass().getName()
                                                    + " size="
                                                    + view.getWidth()
                                                    + "x"
                                                    + view.getHeight()
                                    );
                                }

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "setBackground hook failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            /*
             * ----------------------------------------------------
             * setBackgroundDrawable(Drawable)
             * ----------------------------------------------------
             *
             * Некоторые версии Android/Telegram используют
             * именно deprecated-метод.
             */

            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setBackgroundDrawable",
                    Drawable.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

                            try {

                                View view =
                                        (View) param.thisObject;

                                Drawable drawable =
                                        (Drawable) param.args[0];

                                /*
                                 * DecorView
                                 */

                                if (isDecorView(view)) {

                                    param.args[0] =
                                            new ColorDrawable(
                                                    Color.TRANSPARENT
                                            );

                                    XposedBridge.log(
                                            "[TransparentTelegram] "
                                                    + "Blocked DecorView "
                                                    + "background"
                                    );

                                    return;
                                }

                                /*
                                 * Telegram blur
                                 */

                                if (isTelegramBlur(drawable)) {

                                    disableBlurDrawable(
                                            drawable
                                    );

                                    XposedBridge.log(
                                            "[TransparentTelegram] "
                                                    + "Blocked blur drawable: "
                                                    + view.getClass().getName()
                                                    + " size="
                                                    + view.getWidth()
                                                    + "x"
                                                    + view.getHeight()
                                    );
                                }

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "setBackgroundDrawable "
                                                + "hook failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            /*
             * ----------------------------------------------------
             * setBackgroundColor(int)
             * ----------------------------------------------------
             *
             * Это особенно важно, потому что твой DIAG показывает:
             *
             * DecorView
             * bg=ColorDrawable(#80000000)
             *
             * Значит Telegram или Android может снова вызывать
             * setBackgroundColor().
             */

            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setBackgroundColor",
                    int.class,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

                            try {

                                View view =
                                        (View) param.thisObject;

                                if (isDecorView(view)) {

                                    int oldColor =
                                            (Integer) param.args[0];

                                    param.args[0] =
                                            Color.TRANSPARENT;

                                    XposedBridge.log(
                                            "[TransparentTelegram] "
                                                    + "Blocked DecorView "
                                                    + "color #"
                                                    + String.format(
                                                            "%08X",
                                                            oldColor
                                                    )
                                    );
                                }

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "setBackgroundColor "
                                                + "hook failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "View background hooks installed"
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "Failed to hook View backgrounds: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * DECOR VIEW CHECK
     * ============================================================
     */

    private boolean isDecorView(
            View view) {

        if (view == null) {
            return false;
        }

        String className =
                view.getClass().getName();

        return className.equals(
                "com.android.internal.policy.DecorView"
        );
    }

    /*
     * ============================================================
     * TELEGRAM BLUR CHECK
     * ============================================================
     */

    private boolean isTelegramBlur(
            Drawable drawable) {

        if (drawable == null) {
            return false;
        }

        String className =
                drawable.getClass().getName();

        /*
         * Именно тот drawable, который виден
         * в твоём DIAG:
         *
         * org.telegram.ui.Components.blur3.
         * BlurredBackgroundWithFadeDrawable
         */

        return className.contains(
                "BlurredBackgroundWithFadeDrawable"
        );
    }

    /*
     * ============================================================
     * DISABLE BLUR
     * ============================================================
     */

    private void disableBlurDrawable(
            Drawable drawable) {

        if (drawable == null) {
            return;
        }

        try {

            /*
             * Не заменяем Drawable.
             *
             * Просто выключаем его отрисовку.
             */

            drawable.mutate();

            drawable.setAlpha(0);

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "Failed to disable blur: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * PREPARE WINDOW
     * ============================================================
     */

    private void prepareWindow(
            Activity activity) {

        if (activity == null) {
            return;
        }

        try {

            Window window =
                    activity.getWindow();

            window.setFormat(
                    PixelFormat.TRANSLUCENT
            );

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );

            window.setDimAmount(0f);

            window.setBackgroundDrawable(
                    new ColorDrawable(
                            Color.TRANSPARENT
                    )
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "prepareWindow failed: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * APPLY TRANSPARENCY
     * ============================================================
     */

    private void applyTransparency(
            final Activity activity) {

        if (activity == null ||
                activity.isFinishing()) {

            return;
        }

        try {

            Window window =
                    activity.getWindow();

            window.setFormat(
                    PixelFormat.TRANSLUCENT
            );

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );

            window.setDimAmount(0f);

            /*
             * Само окно прозрачное.
             */

            window.setBackgroundDrawable(
                    new ColorDrawable(
                            Color.TRANSPARENT
                    )
            );

            final View root =
                    window.getDecorView();

            if (root == null) {
                return;
            }

            /*
             * Принудительно очищаем DecorView.
             */

            forceTransparentDecor(
                    root
            );

            /*
             * Проходим дерево только для поиска
             * BlurredBackgroundWithFadeDrawable.
             *
             * Никаких полноэкранных ColorDrawable
             * больше не трогаем.
             */

            root.post(
                    new Runnable() {

                        @Override
                        public void run() {

                            try {

                                scanForBlur(
                                        root,
                                        0
                                );

                                forceTransparentDecor(
                                        root
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "scan failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "Transparency applied"
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "applyTransparency failed: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * FORCE TRANSPARENT DECOR
     * ============================================================
     */

    private void forceTransparentDecor(
            View root) {

        if (root == null) {
            return;
        }

        try {

            if (isDecorView(root)) {

                Drawable background =
                        root.getBackground();

                /*
                 * Если уже прозрачный — ничего не делаем.
                 */

                if (background instanceof ColorDrawable) {

                    int color =
                            ((ColorDrawable)
                                    background)
                                    .getColor();

                    if (Color.alpha(color) == 0) {
                        return;
                    }
                }

                /*
                 * Меняем именно DecorView.
                 */

                root.setBackground(
                        new ColorDrawable(
                                Color.TRANSPARENT
                        )
                );

                root.invalidate();

                XposedBridge.log(
                        "[TransparentTelegram] "
                                + "DecorView forced transparent"
                );
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "force DecorView failed: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * SCAN FOR BLUR
     * ============================================================
     */

    private void scanForBlur(
            View view,
            int depth) {

        if (view == null) {
            return;
        }

        if (depth > MAX_DEPTH) {
            return;
        }

        try {

            Drawable background =
                    view.getBackground();

            if (isTelegramBlur(background)) {

                disableBlurDrawable(
                        background
                );

                view.invalidate();

                XposedBridge.log(
                        "[TransparentTelegram] "
                                + "Blocked blur drawable: "
                                + view.getClass().getName()
                                + " size="
                                + view.getWidth()
                                + "x"
                                + view.getHeight()
                );
            }

            /*
             * Никакие другие background здесь НЕ трогаем.
             */

            if (view instanceof ViewGroup) {

                ViewGroup group =
                        (ViewGroup) view;

                int count =
                        group.getChildCount();

                for (int i = 0;
                     i < count;
                     i++) {

                    scanForBlur(
                            group.getChildAt(i),
                            depth + 1
                    );
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "scan node failed: "
                            + view.getClass().getName()
                            + ": "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * DELAYED APPLY
     * ============================================================
     */

    private void scheduleApply(
            final Activity activity,
            long delay) {

        if (activity == null) {
            return;
        }

        MAIN_HANDLER.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        try {

                            applyTransparency(
                                    activity
                            );

                        } catch (Throwable t) {

                            XposedBridge.log(
                                    "[TransparentTelegram] "
                                            + "delayed apply failed: "
                                            + t
                            );
                        }
                    }
                },
                delay
        );
    }

    /*
     * ============================================================
     * DIAGNOSTICS
     * ============================================================
     */

    private void dumpDiagnostics(
            Activity activity) {

        if (activity == null ||
                activity.isFinishing()) {

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "activity null/finishing"
            );

            return;
        }

        try {

            Window window =
                    activity.getWindow();

            WindowManager.LayoutParams attrs =
                    window.getAttributes();

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "===== DUMP START ====="
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "activity="
                            + activity.getClass().getName()
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "window.flags=0x"
                            + Integer.toHexString(
                                    attrs.flags
                            )
                            + " FLAG_SHOW_WALLPAPER="
                            + (
                                    (attrs.flags
                                            & WindowManager.LayoutParams
                                            .FLAG_SHOW_WALLPAPER)
                                            != 0
                            )
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "window.dimAmount="
                            + attrs.dimAmount
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "window.alpha="
                            + attrs.alpha
            );

            View decor =
                    window.getDecorView();

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "decorView background="
                            + describeDrawable(
                                    decor.getBackground()
                            )
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "decorView alpha="
                            + decor.getAlpha()
                            + " visibility="
                            + visibilityToString(
                                    decor.getVisibility()
                            )
                            + " size="
                            + decor.getWidth()
                            + "x"
                            + decor.getHeight()
            );

            dumpViewTreeDiagnostics(
                    decor,
                    0
            );

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "===== DUMP END ====="
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "failed: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * DIAGNOSTIC TREE
     * ============================================================
     */

    private void dumpViewTreeDiagnostics(
            View view,
            int depth) {

        if (view == null) {
            return;
        }

        if (depth > MAX_DEPTH) {
            return;
        }

        try {

            StringBuilder indent =
                    new StringBuilder();

            for (int i = 0;
                 i < depth;
                 i++) {

                indent.append("  ");
            }

            String line =
                    indent
                            + "["
                            + depth
                            + "] "
                            + view.getClass().getName()
                            + " bg="
                            + describeDrawable(
                                    view.getBackground()
                            )
                            + " alpha="
                            + view.getAlpha()
                            + " vis="
                            + visibilityToString(
                                    view.getVisibility()
                            )
                            + " size="
                            + view.getWidth()
                            + "x"
                            + view.getHeight()
                            + " top="
                            + view.getTop()
                            + " bottom="
                            + view.getBottom();

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + line
            );

            if (view instanceof ViewGroup) {

                ViewGroup group =
                        (ViewGroup) view;

                int childCount =
                        group.getChildCount();

                int count =
                        Math.min(
                                childCount,
                                MAX_CHILDREN
                        );

                for (int i = 0;
                     i < count;
                     i++) {

                    dumpViewTreeDiagnostics(
                            group.getChildAt(i),
                            depth + 1
                    );
                }

                if (childCount > MAX_CHILDREN) {

                    XposedBridge.log(
                            "[TransparentTelegram][DIAG] "
                                    + indent
                                    + "  ... "
                                    + (
                                            childCount
                                                    - MAX_CHILDREN
                                    )
                                    + " children hidden"
                    );
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "node failed: "
                            + t
            );
        }
    }

    /*
     * ============================================================
     * DRAWABLE DESCRIPTION
     * ============================================================
     */

    private String describeDrawable(
            Drawable drawable) {

        if (drawable == null) {
            return "null";
        }

        try {

            if (drawable instanceof ColorDrawable) {

                int color =
                        ((ColorDrawable)
                                drawable)
                                .getColor();

                return String.format(
                        "ColorDrawable(#%08X, alpha=%d)",
                        color,
                        Color.alpha(color)
                );
            }

            return drawable.getClass().getName()
                    + " opacity="
                    + drawable.getOpacity()
                    + " alpha="
                    + drawable.getAlpha();

        } catch (Throwable t) {

            return drawable.getClass().getName();
        }
    }

    /*
     * ============================================================
     * VISIBILITY
     * ============================================================
     */

    private String visibilityToString(
            int visibility) {

        switch (visibility) {

            case View.VISIBLE:
                return "VISIBLE";

            case View.INVISIBLE:
                return "INVISIBLE";

            case View.GONE:
                return "GONE";

            default:
                return String.valueOf(
                        visibility
                );
        }
    }
}
