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
     * ============================================================
     * TRANSPARENCY
     * ============================================================
     *
     * 0x00 = completely transparent
     * 0x80 = approximately 50%
     * 0xFF = completely opaque
     */

    private static final int ALPHA = 0x80;

    private static final int WINDOW_BACKGROUND_COLOR =
            Color.argb(ALPHA, 0, 0, 0);


    /*
     * ============================================================
     * LAYOUT / RETRY
     * ============================================================
     *
     * Telegram часто создаёт View раньше, чем он получает размер.
     *
     * Поэтому:
     *
     *   setBackground()      -> может быть 0x0
     *   onLayout/onDraw      -> уже нормальный размер
     *
     * Мы повторяем проверку несколько раз после layout.
     */

    private static final int RETRY_DELAY_1 = 100;
    private static final int RETRY_DELAY_2 = 350;
    private static final int RETRY_DELAY_3 = 800;
    private static final int RETRY_DELAY_4 = 1500;


    /*
     * ============================================================
     * DIAGNOSTICS
     * ============================================================
     */

    private static final int MAX_DEPTH = 40;

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());


    /*
     * Чтобы не спамить одинаковыми сообщениями в лог.
     */

    private static final Set<Integer> LOGGED_VIEWS =
            new HashSet<>();


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

        final String packageName = lpparam.packageName;

        XposedBridge.log(
                "[TransparentTelegram] Loading: "
                        + packageName
        );

        try {

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
             * ----------------------------------------------------
             * onCreate
             * ----------------------------------------------------
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
                                        "[TransparentTelegram] before onCreate failed: "
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

                                XposedBridge.log(
                                        "[TransparentTelegram] after onCreate: "
                                                + packageName
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] after onCreate failed: "
                                                + t
                                );
                            }
                        }
                    }
            );


            /*
             * ----------------------------------------------------
             * onResume
             * ----------------------------------------------------
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
                                        "[TransparentTelegram] onResume failed: "
                                                + t
                                );
                            }
                        }
                    }
            );


            /*
             * ====================================================
             * GLOBAL VIEW BACKGROUND HOOK
             * ====================================================
             *
             * Это основное изменение.
             *
             * Telegram может установить background ПОСЛЕ того,
             * как мы прошли дерево View.
             *
             * Поэтому перехватываем сам момент:
             *
             *     View.setBackground(Drawable)
             *
             * и отдельно обрабатываем Drawable.
             *
             * ВАЖНО:
             *
             * если View ещё 0x0 —
             * НЕ делаем вывод, что это не тот View.
             *
             * Ставим обработку через post().
             */

            hookViewSetBackground(lpparam);


            /*
             * ====================================================
             * DRAWABLE / BLUR HOOK
             * ====================================================
             *
             * Telegram может использовать специальные Drawable
             * для blur / material / wallpaper effects.
             *
             * Поэтому дополнительно перехватываем:
             *
             *     View.setBackgroundResource()
             *
             * и после изменения View снова запускаем обработку.
             */

            hookViewSetBackgroundResource(lpparam);


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
     * WINDOW
     * ============================================================
     */

    private void prepareWindow(Activity activity) {

        if (activity == null) {
            return;
        }

        Window window = activity.getWindow();

        window.setFormat(
                PixelFormat.TRANSLUCENT
        );

        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        );

        window.setDimAmount(0f);

        window.setBackgroundDrawable(
                new ColorDrawable(
                        WINDOW_BACKGROUND_COLOR
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

            window.setFormat(
                    PixelFormat.TRANSLUCENT
            );

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
            );

            window.setDimAmount(0f);

            window.setBackgroundDrawable(
                    new ColorDrawable(
                            WINDOW_BACKGROUND_COLOR
                    )
            );


            final View root =
                    window.getDecorView();

            if (root == null) {
                return;
            }


            /*
             * ----------------------------------------------------
             * Первый проход
             * ----------------------------------------------------
             */

            root.post(new Runnable() {

                @Override
                public void run() {

                    scanAndPatchRoot(root);

                }
            });


            /*
             * ----------------------------------------------------
             * Повторные проходы
             * ----------------------------------------------------
             *
             * Telegram может создать / заменить View
             * уже после первого прохода.
             */

            MAIN_HANDLER.postDelayed(
                    new Runnable() {

                        @Override
                        public void run() {

                            scanAndPatchRoot(root);

                        }
                    },
                    RETRY_DELAY_1
            );


            MAIN_HANDLER.postDelayed(
                    new Runnable() {

                        @Override
                        public void run() {

                            scanAndPatchRoot(root);

                        }
                    },
                    RETRY_DELAY_2
            );


            MAIN_HANDLER.postDelayed(
                    new Runnable() {

                        @Override
                        public void run() {

                            scanAndPatchRoot(root);

                        }
                    },
                    RETRY_DELAY_3
            );


            MAIN_HANDLER.postDelayed(
                    new Runnable() {

                        @Override
                        public void run() {

                            scanAndPatchRoot(root);

                        }
                    },
                    RETRY_DELAY_4
            );


            XposedBridge.log(
                    "[TransparentTelegram] Transparency applied"
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] applyTransparency failed: "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * SETBACKGROUND HOOK
     * ============================================================
     */

    private void hookViewSetBackground(
            XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> viewClass =
                    XposedHelpers.findClass(
                            "android.view.View",
                            lpparam.classLoader
                    );


            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "setBackground",
                    Drawable.class,
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            try {

                                View view =
                                        (View) param.thisObject;

                                Drawable drawable =
                                        (Drawable) param.args[0];


                                if (drawable == null) {
                                    return;
                                }


                                /*
                                 * НЕ блокируем View размером 0x0.
                                 *
                                 * Это как раз проблема из твоего
                                 * текущего лога:
                                 *
                                 *     size=0x0
                                 *
                                 * Вместо этого ждём layout.
                                 */

                                if (view.getWidth() == 0 ||
                                        view.getHeight() == 0) {

                                    scheduleViewPatch(view);

                                    return;
                                }


                                /*
                                 * View уже измерен.
                                 */

                                patchDrawableBackground(
                                        view,
                                        drawable
                                );

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] setBackground hook failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] setBackground hook install failed: "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * SETBACKGROUNDRESOURCE HOOK
     * ============================================================
     */

    private void hookViewSetBackgroundResource(
            XC_LoadPackage.LoadPackageParam lpparam) {

        try {

            Class<?> viewClass =
                    XposedHelpers.findClass(
                            "android.view.View",
                            lpparam.classLoader
                    );


            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "setBackgroundResource",
                    int.class,
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            try {

                                View view =
                                        (View) param.thisObject;

                                /*
                                 * Drawable уже будет установлен
                                 * Android'ом к этому моменту.
                                 *
                                 * Поэтому просто планируем
                                 * повторную обработку.
                                 */

                                scheduleViewPatch(view);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        "[TransparentTelegram] setBackgroundResource hook failed: "
                                                + t
                                );
                            }
                        }
                    }
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] setBackgroundResource hook install failed: "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * SCHEDULE VIEW PATCH
     * ============================================================
     */

    private void scheduleViewPatch(
            final View view) {

        if (view == null) {
            return;
        }


        /*
         * Сейчас.
         */

        view.post(new Runnable() {

            @Override
            public void run() {

                patchView(view);

            }
        });


        /*
         * После layout.
         */

        MAIN_HANDLER.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        patchView(view);

                    }
                },
                RETRY_DELAY_1
        );


        MAIN_HANDLER.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        patchView(view);

                    }
                },
                RETRY_DELAY_2
        );


        MAIN_HANDLER.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        patchView(view);

                    }
                },
                RETRY_DELAY_3
        );
    }


    /*
     * ============================================================
     * PATCH SINGLE VIEW
     * ============================================================
     */

    private void patchView(
            View view) {

        if (view == null) {
            return;
        }

        if (view.getWidth() <= 0 ||
                view.getHeight() <= 0) {

            return;
        }


        Drawable background =
                view.getBackground();

        if (background == null) {
            return;
        }


        patchDrawableBackground(
                view,
                background
        );
    }


    /*
     * ============================================================
     * PATCH DRAWABLE
     * ============================================================
     */

    private void patchDrawableBackground(
            View view,
            Drawable drawable) {

        if (view == null ||
                drawable == null) {

            return;
        }


        int width =
                view.getWidth();

        int height =
                view.getHeight();


        if (width <= 0 ||
                height <= 0) {

            /*
             * Очень важно:
             *
             * НЕ логируем "Blocked blur drawable"
             * здесь, потому что это ложная диагностика.
             *
             * View просто ещё не измерен.
             */

            return;
        }


        /*
         * --------------------------------------------------------
         * BLUR / SPECIAL DRAWABLE
         * --------------------------------------------------------
         *
         * Для Drawable, который не является обычным ColorDrawable,
         * не заменяем его полностью.
         *
         * Просто делаем его полупрозрачным.
         *
         * Это позволяет сохранить:
         *
         *   - blur
         *   - bitmap
         *   - NinePatch
         *   - Telegram material drawable
         *
         * но убрать полностью непрозрачный слой.
         */

        if (!(drawable instanceof ColorDrawable)) {

            if (isEffectivelyOpaque(drawable)) {

                try {

                    Drawable mutable =
                            drawable.mutate();

                    mutable.setAlpha(ALPHA);


                    logPatchedView(
                            view,
                            "special/blur drawable"
                    );

                } catch (Throwable t) {

                    XposedBridge.log(
                            "[TransparentTelegram] special drawable patch failed: "
                                    + view.getClass().getName()
                                    + ": "
                                    + t
                    );
                }
            }

            return;
        }


        /*
         * --------------------------------------------------------
         * COLOR DRAWABLE
         * --------------------------------------------------------
         */

        ColorDrawable colorDrawable =
                (ColorDrawable) drawable;

        int color =
                colorDrawable.getColor();

        int alpha =
                Color.alpha(color);


        /*
         * Уже прозрачный — ничего делать не надо.
         */

        if (alpha < 255) {
            return;
        }


        /*
         * --------------------------------------------------------
         * НЕБОЛЬШИЕ VIEW НЕ ТРОГАЕМ
         * --------------------------------------------------------
         *
         * Это критично.
         *
         * Иначе прозрачными станут:
         *
         *   - кнопки
         *   - bubble
         *   - карточки
         *   - popup
         *   - элементы списка
         *
         * Нам нужны именно фоновые слои.
         */

        View root =
                view.getRootView();

        if (root == null) {
            return;
        }


        int rootWidth =
                root.getWidth();

        int rootHeight =
                root.getHeight();


        if (rootWidth <= 0 ||
                rootHeight <= 0) {

            return;
        }


        boolean largeEnough =
                width >= rootWidth * 0.85f &&
                height >= rootHeight * 0.85f;


        /*
         * --------------------------------------------------------
         * LARGE FULL-SCREEN VIEW
         * --------------------------------------------------------
         */

        if (largeEnough) {

            try {

                view.setBackgroundColor(
                        WINDOW_BACKGROUND_COLOR
                );


                logPatchedView(
                        view,
                        "large opaque View"
                );

            } catch (Throwable t) {

                XposedBridge.log(
                        "[TransparentTelegram] large View patch failed: "
                                + view.getClass().getName()
                                + ": "
                                + t
                );
            }

            return;
        }


        /*
         * --------------------------------------------------------
         * SPECIAL TOP/BOTTOM DRAWABLES
         * --------------------------------------------------------
         *
         * НОВОЕ ПРАВИЛО.
         *
         * Здесь специально НЕ проверяем минимальный размер.
         *
         * Нам нужны узкие панели/баннеры, которые могут занимать
         * только верхнюю часть экрана.
         *
         * Это именно то, чего сейчас не хватает для верхнего
         * переосветлённого баннера.
         */


        boolean fullWidth =
                width >= rootWidth * 0.80f;


        boolean topAligned =
                view.getTop() <= rootHeight * 0.20f;


        boolean bottomAligned =
                view.getBottom() >= rootHeight * 0.80f;


        /*
         * Верхний или нижний широкий непрозрачный слой.
         *
         * Высота НЕ учитывается.
         *
         * Поэтому правило работает независимо от размера
         * самого banner/drawable.
         */

        if (fullWidth &&
                (topAligned || bottomAligned)) {

            try {

                view.setBackgroundColor(
                        WINDOW_BACKGROUND_COLOR
                );


                logPatchedView(
                        view,
                        "top/bottom opaque panel"
                );

            } catch (Throwable t) {

                XposedBridge.log(
                        "[TransparentTelegram] top/bottom patch failed: "
                                + view.getClass().getName()
                                + ": "
                                + t
                );
            }

            return;
        }
    }


    /*
     * ============================================================
     * FULL TREE SCAN
     * ============================================================
     */

    private void scanAndPatchRoot(
            View root) {

        if (root == null) {
            return;
        }


        try {

            int rootWidth =
                    root.getWidth();

            int rootHeight =
                    root.getHeight();


            if (rootWidth <= 0 ||
                    rootHeight <= 0) {

                return;
            }


            stripOpaqueBackgrounds(
                    root,
                    rootWidth,
                    rootHeight,
                    0
            );

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] tree scan failed: "
                            + t
            );
        }
    }


    /*
     * ============================================================
     * TREE WALK
     * ============================================================
     */

    private void stripOpaqueBackgrounds(
            View view,
            int rootWidth,
            int rootHeight,
            int depth) {

        if (view == null ||
                depth > MAX_DEPTH) {

            return;
        }


        try {

            Drawable bg =
                    view.getBackground();


            if (bg != null &&
                    isEffectivelyOpaque(bg)) {


                int width =
                        view.getWidth();

                int height =
                        view.getHeight();


                if (width > 0 &&
                        height > 0) {


                    /*
                     * ------------------------------------------------
                     * LARGE FULLSCREEN
                     * ------------------------------------------------
                     */

                    boolean fullWidth =
                            width >= rootWidth * 0.85f;

                    boolean fullHeight =
                            height >= rootHeight * 0.85f;


                    if (fullWidth &&
                            fullHeight) {

                        patchDrawableBackground(
                                view,
                                bg
                        );
                    }


                    /*
                     * ------------------------------------------------
                     * TOP / BOTTOM PANEL
                     * ------------------------------------------------
                     *
                     * Независимо от высоты.
                     */

                    else if (
                            width >= rootWidth * 0.80f &&
                            (
                                    view.getTop()
                                            <= rootHeight * 0.20f
                                            ||
                                    view.getBottom()
                                            >= rootHeight * 0.80f
                            )
                    ) {

                        patchDrawableBackground(
                                view,
                                bg
                        );
                    }
                }
            }


            /*
             * --------------------------------------------------------
             * CHILDREN
             * --------------------------------------------------------
             */

            if (view instanceof ViewGroup) {

                ViewGroup group =
                        (ViewGroup) view;

                int count =
                        group.getChildCount();


                for (int i = 0;
                     i < count;
                     i++) {

                    stripOpaqueBackgrounds(
                            group.getChildAt(i),
                            rootWidth,
                            rootHeight,
                            depth + 1
                    );
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    "[TransparentTelegram] tree node failed: "
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


        if (drawable instanceof ColorDrawable) {

            return Color.alpha(
                    ((ColorDrawable) drawable).getColor()
            ) == 255;
        }


        return drawable.getOpacity()
                == PixelFormat.OPAQUE;
    }


    /*
     * ============================================================
     * LOGGING
     * ============================================================
     */

    private void logPatchedView(
            View view,
            String reason) {

        if (view == null) {
            return;
        }


        /*
         * Не логируем один и тот же объект
         * сотни раз.
         */

        int id =
                System.identityHashCode(view);


        synchronized (LOGGED_VIEWS) {

            if (LOGGED_VIEWS.contains(id)) {
                return;
            }

            LOGGED_VIEWS.add(id);
        }


        Drawable bg =
                view.getBackground();


        String drawableInfo =
                describeDrawable(bg);


        XposedBridge.log(
                "[TransparentTelegram] "
                        + reason
                        + ": "
                        + view.getClass().getName()
                        + " size="
                        + view.getWidth()
                        + "x"
                        + view.getHeight()
                        + " top="
                        + view.getTop()
                        + " bottom="
                        + view.getBottom()
                        + " bg="
                        + drawableInfo
        );
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
                        ((ColorDrawable) drawable)
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
}
