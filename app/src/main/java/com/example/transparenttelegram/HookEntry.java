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
                                        "[TransparentTelegram] Window prepared: "
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
             *
             * Telegram может снова установить background уже
             * после onResume.
             *
             * Поэтому делаем несколько проходов.
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
                             * ------------------------------------------------
                             * Повторные проходы
                             * ------------------------------------------------
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


                            /*
                             * ------------------------------------------------
                             * Диагностика один раз за процесс
                             * ------------------------------------------------
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
                    "[TransparentTelegram] Hooks installed for "
                            + packageName
            );


        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] Failed for "
                            + packageName
                            + ": "
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


        Window window =
                activity.getWindow();


        /*
         * Разрешаем прозрачность окна.
         */

        window.setFormat(
                PixelFormat.TRANSLUCENT
        );


        /*
         * Показываем wallpaper за окном.
         */

        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        );


        /*
         * Никакого dim.
         */

        window.setDimAmount(0f);


        /*
         * ========================================================
         * ВАЖНО
         * ========================================================
         *
         * Раньше здесь было:
         *
         *     Color.argb(0x80, 0, 0, 0)
         *
         * Это создавало дополнительный тёмный слой.
         *
         * Теперь окно действительно прозрачное.
         */

        window.setBackgroundDrawable(
                new ColorDrawable(
                        Color.TRANSPARENT
                )
        );
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


            /*
             * Окно должно оставаться прозрачным.
             */

            window.setFormat(
                    PixelFormat.TRANSLUCENT
            );


            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );


            window.setDimAmount(0f);


            /*
             * НЕ #80000000.
             *
             * Только настоящий transparent background.
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
             * Первый проход после layout.
             */

            root.post(
                    new Runnable() {

                        @Override
                        public void run() {

                            try {

                                patchViewTree(
                                        root,
                                        root.getWidth(),
                                        root.getHeight(),
                                        0
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "patch tree failed: "
                                                + t
                                );
                            }
                        }
                    }
            );


            XposedBridge.log(
                    "[TransparentTelegram] Transparency applied"
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
     * VIEW TREE
     * ============================================================
     */

    private void patchViewTree(
            View view,
            int rootWidth,
            int rootHeight,
            int depth) {

        if (view == null) {
            return;
        }


        if (depth > MAX_DEPTH) {
            return;
        }


        if (rootWidth <= 0 ||
                rootHeight <= 0) {

            return;
        }


        try {

            Drawable background =
                    view.getBackground();


            if (background != null) {

                /*
                 * ------------------------------------------------
                 * Проверяем только реально непрозрачные Drawable.
                 * ------------------------------------------------
                 */

                if (isEffectivelyOpaque(background)) {

                    int width =
                            view.getWidth();

                    int height =
                            view.getHeight();


                    if (width > 0 &&
                            height > 0) {


                        boolean fullWidth =
                                width >= rootWidth * 0.85f;


                        boolean fullHeight =
                                height >= rootHeight * 0.85f;


                        /*
                         * ------------------------------------------------
                         * FULL SCREEN WALL
                         * ------------------------------------------------
                         */

                        if (fullWidth &&
                                fullHeight) {

                            patchFullScreenView(
                                    view,
                                    background
                            );
                        }
                    }
                }
            }


            /*
             * ----------------------------------------------------
             * CHILDREN
             * ----------------------------------------------------
             */

            if (view instanceof ViewGroup) {

                ViewGroup group =
                        (ViewGroup) view;

                int count =
                        group.getChildCount();


                for (int i = 0;
                     i < count;
                     i++) {

                    patchViewTree(
                            group.getChildAt(i),
                            rootWidth,
                            rootHeight,
                            depth + 1
                    );
                }
            }


        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "node patch failed: "
                            + view.getClass().getName()
                            + ": "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * PATCH FULL SCREEN VIEW
     * ============================================================
     */

    private void patchFullScreenView(
            View view,
            Drawable background) {

        if (view == null ||
                background == null) {

            return;
        }


        try {

            /*
             * ====================================================
             * ВАЖНО
             * ====================================================
             *
             * ColorDrawable:
             *
             *     это обычно фон-контейнер Telegram.
             *
             * Мы НЕ заменяем его новым ColorDrawable.
             *
             * Мы просто убираем его альфу.
             *
             * Это позволяет увидеть нижележащий слой.
             */

            if (background instanceof ColorDrawable) {

                ColorDrawable colorDrawable =
                        (ColorDrawable)
                                background;


                int color =
                        colorDrawable.getColor();


                int alpha =
                        Color.alpha(color);


                /*
                 * Уже прозрачный.
                 */

                if (alpha == 0) {
                    return;
                }


                /*
                 * Не заменяем объект.
                 *
                 * mutate() нужен, чтобы не менять Drawable,
                 * который может быть shared.
                 */

                colorDrawable
                        .mutate()
                        .setAlpha(0);


                view.invalidate();


                XposedBridge.log(
                        "[TransparentTelegram] "
                                + "Transparent wall: "
                                + view.getClass().getName()
                                + " size="
                                + view.getWidth()
                                + "x"
                                + view.getHeight()
                                + " originalColor="
                                + String.format(
                                        "#%08X",
                                        color
                                )
                );


                return;
            }


            /*
             * ====================================================
             * НЕ ColorDrawable
             * ====================================================
             *
             * Здесь НИЧЕГО НЕ ДЕЛАЕМ.
             *
             * Это специально.
             *
             * Иначе можно случайно сделать прозрачными:
             *
             * - обои
             * - bitmap
             * - NinePatch
             * - Telegram wallpaper drawable
             */

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "patchFullScreenView failed: "
                            + view.getClass().getName()
                            + ": "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * OPAQUE CHECK
     * ============================================================
     */

    private boolean isEffectivelyOpaque(
            Drawable drawable) {

        if (drawable == null) {
            return false;
        }


        try {

            if (drawable instanceof ColorDrawable) {

                return Color.alpha(
                        ((ColorDrawable)
                                drawable)
                                .getColor()
                ) == 255;
            }


            /*
             * Для других Drawable проверяем opacity.
             *
             * Но patchFullScreenView их всё равно
             * не изменяет.
             */

            return drawable.getOpacity()
                    == PixelFormat.OPAQUE;


        } catch (Throwable t) {

            return false;
        }
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
                            + "decor.background="
                            + describeDrawable(
                                    decor.getBackground()
                            )
            );


            XposedBridge.log(
                    "[TransparentTelegram][DIAG] "
                            + "decor.alpha="
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
