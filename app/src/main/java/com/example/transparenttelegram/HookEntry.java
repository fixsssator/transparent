package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
     * Приложения, к которым применяется модуль.
     */
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(
            Arrays.asList(
                    "org.telegram.messenger",
                    "org.telegram.messenger.beta",
                    "org.telegram.messenger.web",

                    "com.radolyn.ayugram",
                    "com.radolyn.ayugram.web",

                    "tw.nekomimi.nekogram",
                    "nekox.messenger"
            )
    );

    private static final String LAUNCH_ACTIVITY_CLASS =
            "org.telegram.ui.LaunchActivity";

    /*
     * Точный класс Telegram/AyuGram,
     * который создаёт пересвеченный blur/fade.
     *
     * ВАЖНО:
     * правило для него НЕ зависит от размера View,
     * opacity, alpha или цвета.
     */
    private static final String BLUR_DRAWABLE_CLASS =
            "org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable";

    /*
     * Полупрозрачный чёрный фон:
     *
     * 0x80 = alpha 128 ≈ 50%
     */
    private static final int WINDOW_BACKGROUND_COLOR =
            0x80000000;

    /*
     * Альфа для обычных больших непрозрачных View.
     *
     * Оставляем существующее поведение.
     */
    private static final int VIEW_ALPHA = 128;

    @Override
    public void handleLoadPackage(
            XC_LoadPackage.LoadPackageParam lpparam) {

        final String packageName = lpparam.packageName;

        if (!TARGET_PACKAGES.contains(packageName)) {
            return;
        }

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
             * =========================================================
             * LaunchActivity.onCreate()
             * =========================================================
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
                                                + "prepareWindow failed: "
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

                                /*
                                 * После создания интерфейса
                                 * ищем Telegram blur.
                                 */
                                inspectViewTree(
                                        activity.getWindow().getDecorView()
                                );

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "after onCreate: "
                                                + packageName
                                );

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
             * =========================================================
             * LaunchActivity.onResume()
             * =========================================================
             *
             * Telegram/AyuGram могут менять Window после onCreate().
             */

            XposedHelpers.findAndHookMethod(
                    launchActivityClass,
                    "onResume",
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            try {

                                Activity activity =
                                        (Activity) param.thisObject;

                                applyTransparency(activity);

                                inspectViewTree(
                                        activity.getWindow().getDecorView()
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] "
                                                + "onResume failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

            /*
             * =========================================================
             * View.setBackground(Drawable)
             * =========================================================
             *
             * Это важная часть для динамически создаваемого blur.
             *
             * Telegram может установить
             * BlurredBackgroundWithFadeDrawable уже ПОСЛЕ
             * нашего обхода View hierarchy.
             *
             * Поэтому перехватываем сам момент установки background.
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

                                Drawable drawable =
                                        (Drawable) param.args[0];

                                if (drawable == null) {
                                    return;
                                }

                                String drawableClass =
                                        drawable.getClass().getName();

                                /*
                                 * =================================================
                                 * ОТДЕЛЬНОЕ ПРАВИЛО ДЛЯ TELEGRAM BLUR
                                 * =================================================
                                 *
                                 * Размер View НЕ имеет значения.
                                 *
                                 * opacity НЕ имеет значения.
                                 *
                                 * alpha НЕ имеет значения.
                                 *
                                 * Если Telegram пытается поставить
                                 * BlurredBackgroundWithFadeDrawable —
                                 * запрещаем установку этого background.
                                 */

                                if (BLUR_DRAWABLE_CLASS.equals(
                                        drawableClass)) {

                                    param.args[0] = null;

                                    View view =
                                            (View) param.thisObject;

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
     * =============================================================
     * Настройка Window
     * =============================================================
     */

    private void prepareWindow(Activity activity) {

        Window window = activity.getWindow();

        /*
         * Показываем системные обои за окном.
         *
         * Аналог:
         *
         * android:windowShowWallpaper="true"
         */
        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        );

        /*
         * Разрешаем окну быть translucent.
         */
        window.setFormat(
                PixelFormat.TRANSLUCENT
        );

        /*
         * Не затемняем фон.
         */
        window.setDimAmount(0.0f);

        /*
         * Полупрозрачный фон окна.
         *
         * Аналог:
         *
         * android:windowBackground="#80000000"
         */
        window.setBackgroundDrawable(
                new ColorDrawable(
                        WINDOW_BACKGROUND_COLOR
                )
        );
    }

    /*
     * =============================================================
     * Повторное применение Window после создания Telegram UI
     * =============================================================
     */

    private void applyTransparency(Activity activity) {

        try {

            Window window = activity.getWindow();

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );

            window.setFormat(
                    PixelFormat.TRANSLUCENT
            );

            window.setDimAmount(0.0f);

            window.setBackgroundDrawable(
                    new ColorDrawable(
                            WINDOW_BACKGROUND_COLOR
                    )
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
     * =============================================================
     * Обход View hierarchy
     * =============================================================
     */

    private void inspectViewTree(View view) {

        if (view == null) {
            return;
        }

        try {

            /*
             * =====================================================
             * ПРАВИЛО №1 — TELEGRAM BLUR
             * =====================================================
             *
             * Это отдельное правило.
             *
             * Никаких проверок:
             * - размера;
             * - opacity;
             * - alpha.
             */

            Drawable background =
                    view.getBackground();

            if (background != null) {

                String drawableClass =
                        background.getClass().getName();

                if (BLUR_DRAWABLE_CLASS.equals(
                        drawableClass)) {

                    view.setBackground(null);

                    XposedBridge.log(
                            "[TransparentTelegram] "
                                    + "Removed blur drawable from "
                                    + view.getClass().getName()
                                    + " size="
                                    + view.getWidth()
                                    + "x"
                                    + view.getHeight()
                    );

                    /*
                     * Не применяем к этому View остальные правила.
                     */
                    return;
                }

                /*
                 * =================================================
                 * ПРАВИЛО №2 — существующая логика
                 * =================================================
                 *
                 * Здесь обрабатываем только обычные непрозрачные
                 * большие View.
                 */

                if (background.getOpacity() ==
                        android.graphics.PixelFormat.OPAQUE) {

                    int width = view.getWidth();
                    int height = view.getHeight();

                    View root =
                            view.getRootView();

                    int rootWidth =
                            root != null
                                    ? root.getWidth()
                                    : 0;

                    int rootHeight =
                            root != null
                                    ? root.getHeight()
                                    : 0;

                    boolean largeEnough =
                            rootWidth > 0
                                    && rootHeight > 0
                                    && width >= rootWidth * 0.7f
                                    && height >= rootHeight * 0.5f;

                    if (largeEnough) {

                        view.setBackgroundColor(
                                Color.argb(
                                        VIEW_ALPHA,
                                        0,
                                        0,
                                        0
                                )
                        );

                        XposedBridge.log(
                                "[TransparentTelegram] "
                                        + "Made large opaque View "
                                        + "transparent: "
                                        + view.getClass().getName()
                                        + " size="
                                        + width
                                        + "x"
                                        + height
                        );
                    }
                }
            }

            /*
             * =====================================================
             * Рекурсивно обходим дочерние View
             * =====================================================
             *
             * Это позволяет найти blur независимо от того,
             * где Telegram его создаёт в иерархии.
             */

            if (view instanceof ViewGroup) {

                ViewGroup group =
                        (ViewGroup) view;

                int childCount =
                        group.getChildCount();

                for (int i = 0; i < childCount; i++) {

                    View child =
                            group.getChildAt(i);

                    inspectViewTree(child);
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] "
                            + "inspectViewTree failed: "
                            + t
            );
        }
    }
}
