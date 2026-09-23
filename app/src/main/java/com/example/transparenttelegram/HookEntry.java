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
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

private static final String TAG = "[TransparentTelegram]";

private static final Set<String> TARGET_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "org.telegram.messenger",
                    "org.telegram.messenger.beta",
                    "org.telegram.messenger.web",

"com.radolyn.ayugram",
                    "com.radolyn.ayugram.web",

"tw.nekomimi.nekogram",
                    "nekox.messenger"
            )));

private static final String LAUNCH_ACTIVITY =
            "org.telegram.ui.LaunchActivity";

/*
     * Основной цвет окна.
     *
     * 0x80 = примерно 50% прозрачности.
     * Для более прозрачного варианта можно использовать 0x55.
     */
    private static final int WINDOW_ALPHA = 0x80;

private static final int TRANSPARENT_BLACK =
            Color.argb(WINDOW_ALPHA, 0, 0, 0);

private static final int BLUR_ALPHA = 0x48;

private static final float FULL_SCREEN_WIDTH = 0.82f;
    private static final float FULL_SCREEN_HEIGHT = 0.82f;

private static final long RESCAN_DELAY_MS = 180L;
    private static final long RESCAN_THROTTLE_MS = 250L;

private static final WeakHashMap<View, Boolean> LISTENERS =
            new WeakHashMap<>();

private static final WeakHashMap<View, Long> LAST_SCAN =
            new WeakHashMap<>();

private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

@Override
    public void handleLoadPackage(
            final XC_LoadPackage.LoadPackageParam lpparam) {

if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

final String packageName = lpparam.packageName;

log("Загрузка пакета: " + packageName);

hookLaunchActivity(lpparam, packageName);
        hookViewBackgrounds(lpparam, packageName);
        hookTelegramBlurClasses(lpparam, packageName);
    }

private void hookLaunchActivity(
            final XC_LoadPackage.LoadPackageParam lpparam,
            final String packageName) {

try {
            final Class<?> activityClass =
                    XposedHelpers.findClass(
                            LAUNCH_ACTIVITY,
                            lpparam.classLoader
                    );

XposedHelpers.findAndHookMethod(
                    activityClass,
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
                            } catch (Throwable e) {
                                logError("Ошибка onCreate", e);
                            }
                        }

@Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            try {
                                final Activity activity =
                                        (Activity) param.thisObject;

applyTransparency(activity);
                            } catch (Throwable e) {
                                logError("Ошибка после onCreate", e);
                            }
                        }
                    }
            );

XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            try {
                                final Activity activity =
                                        (Activity) param.thisObject;

applyTransparency(activity);
                            } catch (Throwable e) {
                                logError("Ошибка onResume", e);
                            }
                        }
                    }
            );

log("Хуки LaunchActivity установлены");
        } catch (Throwable e) {
            logError(
                    "Не удалось найти " + LAUNCH_ACTIVITY,
                    e
            );
        }
    }

private void prepareWindow(Activity activity) {
        if (activity == null) {
            return;
        }

Window window = activity.getWindow();

window.setFormat(PixelFormat.TRANSLUCENT);

window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        );

window.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
        );

window.setDimAmount(0.0f);

window.setBackgroundDrawable(
                new ColorDrawable(TRANSPARENT_BLACK)
        );

/*
         * На Android 15+ edge-to-edge может переопределять
         * системные панели, поэтому не полагаемся только на цвета
         * status/navigation bar.
         */
        try {
            window.setStatusBarColor(TRANSPARENT_BLACK);
            window.setNavigationBarColor(TRANSPARENT_BLACK);
        } catch (Throwable ignored) {
        }
    }

private void applyTransparency(final Activity activity) {
        if (activity == null ||
                activity.isFinishing() ||
                activity.isDestroyed()) {
            return;
        }

prepareWindow(activity);

final Window window = activity.getWindow();
        final View root = window.getDecorView();

if (root == null) {
            return;
        }

attachLayoutListener(root);

root.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            scanViewTree(root);
                        } catch (Throwable e) {
                            logError("Ошибка сканирования View", e);
                        }
                    }
                },
                RESCAN_DELAY_MS
        );
    }

private void attachLayoutListener(final View root) {
        synchronized (LISTENERS) {
            if (Boolean.TRUE.equals(LISTENERS.get(root))) {
                return;
            }

LISTENERS.put(root, Boolean.TRUE);
        }

try {
            root.getViewTreeObserver()
                    .addOnGlobalLayoutListener(
                            new ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override
                                public void onGlobalLayout() {
                                    long now =
                                            System.currentTimeMillis();

synchronized (LAST_SCAN) {
                                        Long previous =
                                                LAST_SCAN.get(root);

if (previous != null &&
                                                now - previous <
                                                        RESCAN_THROTTLE_MS) {
                                            return;
                                        }

LAST_SCAN.put(root, now);
                                    }

root.post(
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    scanViewTree(root);
                                                }
                                            }
                                    );
                                }
                            }
                    );
        } catch (Throwable e) {
            logError("Не удалось установить layout listener", e);
        }
    }

private void scanViewTree(View root) {
        if (root == null) {
            return;
        }

int width = root.getWidth();
        int height = root.getHeight();

if (width <= 0 || height <= 0) {
            return;
        }

patchView(root, width, height, 0);
    }

private void patchView(
            View view,
            int rootWidth,
            int rootHeight,
            int depth) {

if (view == null || depth > 45) {
            return;
        }

try {
            Drawable background = view.getBackground();

if (background != null) {
                String drawableName =
                        background.getClass()
                                .getName()
                                .toLowerCase();

if (drawableName.contains("blur")) {
                    patchBlurDrawable(background);
                } else if (isFullScreenView(
                        view,
                        rootWidth,
                        rootHeight
                )) {
                    patchOpaqueDrawable(background);
                }
            }
        } catch (Throwable e) {
            logError("Ошибка обработки View", e);
        }

if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

int count = group.getChildCount();

for (int i = 0; i < count; i++) {
                patchView(
                        group.getChildAt(i),
                        rootWidth,
                        rootHeight,
                        depth + 1
                );
            }
        }
    }

private boolean isFullScreenView(
            View view,
            int rootWidth,
            int rootHeight) {

if (rootWidth <= 0 || rootHeight <= 0) {
            return false;
        }

int width = view.getWidth();
        int height = view.getHeight();

return width >= rootWidth * FULL_SCREEN_WIDTH &&
                height >= rootHeight * FULL_SCREEN_HEIGHT;
    }

private void patchOpaqueDrawable(Drawable drawable) {
        if (drawable == null) {
            return;
        }

try {
            Drawable mutable = drawable.mutate();

if (mutable instanceof ColorDrawable) {
                /*
                 * Важно: заменяем цвет, а не только alpha.
                 * Иначе белая тема превращается в грязно-белый слой.
                 */
                mutable.setAlpha(WINDOW_ALPHA);
            } else if (mutable.getOpacity() == PixelFormat.OPAQUE) {
                mutable.setAlpha(WINDOW_ALPHA);
            }
        } catch (Throwable e) {
            logError("Ошибка прозрачности Drawable", e);
        }
    }

private void patchBlurDrawable(Drawable drawable) {
        if (drawable == null) {
            return;
        }

try {
            drawable.mutate().setAlpha(BLUR_ALPHA);
        } catch (Throwable e) {
            logError("Ошибка прозрачности blur Drawable", e);
        }
    }

private boolean isOpaque(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            int color =
                    ((ColorDrawable) drawable).getColor();

return Color.alpha(color) == 255;
        }

return drawable.getOpacity() == PixelFormat.OPAQUE;
    }

private void hookViewBackgrounds(
            XC_LoadPackage.LoadPackageParam lpparam,
            String packageName) {

try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setBackgroundColor",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

try {
                                View view = (View) param.thisObject;

int color =
                                        (Integer) param.args[0];

/*
                                 * Не вмешиваемся в маленькие элементы.
                                 * Размер View ещё может быть 0, поэтому
                                 * окончательная проверка выполняется
                                 * дополнительно при сканировании дерева.
                                 */
                                if (Color.alpha(color) == 255 &&
                                        isProbablyBackground(view)) {
                                    param.args[0] =
                                            TRANSPARENT_BLACK;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );

XposedHelpers.findAndHookMethod(
                    View.class,
                    "setBackground",
                    Drawable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

try {
                                View view = (View) param.thisObject;
                                Drawable drawable =
                                        (Drawable) param.args[0];

if (drawable == null) {
                                    return;
                                }

int width = view.getWidth();
                                int height = view.getHeight();

if (isFullScreenView(
                                        view,
                                        width,
                                        height
                                ) && isOpaque(drawable)) {
                                    patchOpaqueDrawable(drawable);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );

log("Хуки View установлены");
        } catch (Throwable e) {
            logError(
                    "Не удалось установить хуки View для "
                            + packageName,
                    e
            );
        }
    }

private boolean isProbablyBackground(View view) {
        if (view == null) {
            return false;
        }

int width = view.getWidth();
        int height = view.getHeight();

/*
         * До layout размеры равны нулю.
         * В таком случае не меняем цвет заранее.
         */
        if (width <= 0 || height <= 0) {
            return false;
        }

View root = view.getRootView();

if (root == null) {
            return false;
        }

return isFullScreenView(
                view,
                root.getWidth(),
                root.getHeight()
        );
    }

private void hookTelegramBlurClasses(
            XC_LoadPackage.LoadPackageParam lpparam,
            String packageName) {

/*
         * Telegram периодически переименовывает blur3-классы.
         * Пробуем несколько вариантов, но ошибка одного класса
         * не должна отключать весь модуль.
         */
        String[] classNames = {
                "org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor",
                "org.telegram.ui.Components.blur3.source.BlurredBackgroundSource",
                "org.telegram.ui.Components.blur3.BlurredBackgroundSourceColor",
                "org.telegram.ui.Components.blur3.drawable.BlurredBackgroundColor"
        };

for (String className : classNames) {
            try {
                Class<?> clazz =
                        XposedHelpers.findClass(
                                className,
                                lpparam.classLoader
                        );

hookColorMethod(clazz, "setColor");
                hookColorMethod(clazz, "setBackgroundColor");

log("Хук blur-класса установлен: " + className);
            } catch (Throwable ignored) {
                /*
                 * Класс отсутствует в конкретной версии Telegram —
                 * это нормально.
                 */
            }
        }
    }

private void hookColorMethod(
            Class<?> clazz,
            String methodName) {

try {
            XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

try {
                                int color =
                                        (Integer) param.args[0];

if (Color.alpha(color) == 255) {
                                    param.args[0] =
                                            TRANSPARENT_BLACK;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
            /*
             * Метод отсутствует в данной версии.
             */
        }
    }

private void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }

private void logError(String message, Throwable error) {
        XposedBridge.log(
                TAG + " " + message + ": " + error
        );
    }
}
