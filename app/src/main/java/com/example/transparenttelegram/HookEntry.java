package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
     * Telegram и Telegram-based клиенты.
     *
     * Обычный Telegram:
     * org.telegram.messenger
     *
     * AyuGram:
     * com.radolyn.ayugram
     */
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(
            Arrays.asList(
                    "org.telegram.messenger",
                    "org.telegram.messenger.beta",
                    "com.radolyn.ayugram"
            )
    );

    private static final String LAUNCH_ACTIVITY_CLASS =
            "org.telegram.ui.LaunchActivity";

    /*
     * Прозрачность.
     *
     * 0   = полностью прозрачно
     * 128 = примерно 50%
     * 255 = полностью непрозрачно
     */
    private static final int ALPHA = 128;

    @Override
    public void handleLoadPackage(
            XC_LoadPackage.LoadPackageParam lpparam) {

        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName = lpparam.packageName;

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
             * ---------------------------------------------------------
             * onCreate
             * ---------------------------------------------------------
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
             * ---------------------------------------------------------
             * onResume
             * ---------------------------------------------------------
             *
             * Telegram может менять фон после onCreate().
             * Поэтому повторно применяем прозрачность.
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
     * Подготавливаем Window ДО создания UI.
     */
    private void prepareWindow(Activity activity) {

        Window window = activity.getWindow();

        /*
         * Разрешаем показывать системные обои
         * за окном приложения.
         */
        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        );

        /*
         * Отключаем затемнение фона.
         */
        window.setDimAmount(0.0f);

        /*
         * Делаем фон самого Window прозрачным.
         */
        window.setBackgroundDrawable(
                new ColorDrawable(Color.TRANSPARENT)
        );
    }

    /*
     * Применяем прозрачность к Window и корневому View.
     */
    private void applyTransparency(Activity activity) {

        if (activity == null || activity.isFinishing()) {
            return;
        }

        try {

            Window window = activity.getWindow();

            /*
             * Window
             */
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );

            window.setDimAmount(0.0f);

            window.setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT)
            );

            /*
             * Root View
             */
            View root = activity.getWindow().getDecorView();

            if (root != null) {

                makeViewTransparent(root);
            }

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
     * Делаем фон View полупрозрачным.
     *
     * Здесь намеренно НЕ красим все дочерние View.
     * Иначе текст, кнопки и картинки Telegram тоже
     * станут прозрачными.
     */
    private void makeViewTransparent(View view) {

        if (view == null) {
            return;
        }

        /*
         * Пока меняем только фон корневого контейнера.
         */
        if (view.getBackground() != null) {

            view.setBackgroundColor(
                    Color.argb(
                            ALPHA,
                            0,
                            0,
                            0
                    )
            );
        }

        /*
         * Если это ViewGroup, ищем подходящий корневой
         * контейнер, но НЕ меняем прозрачность его
         * дочерних элементов.
         */
        if (view instanceof ViewGroup) {

            ViewGroup group = (ViewGroup) view;

            if (group.getChildCount() == 1) {

                View child = group.getChildAt(0);

                /*
                 * Устанавливаем прозрачность только если
                 * дочерний View является основным контейнером.
                 */
                if (child instanceof ViewGroup) {

                    child.setBackgroundColor(
                            Color.argb(
                                    ALPHA,
                                    0,
                                    0,
                                    0
                            )
                    );
                }
            }
        }
    }
}
