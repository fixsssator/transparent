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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Transparent Telegram v3.
 *
 * НОВОЕ в этой версии по сравнению с v2: поддержка ОБФУСЦИРОВАННЫХ
 * сборок Telegram (начиная примерно с 12.10.3). Начиная с этой версии
 * Telegram стал обфусцировать (R8) класс org.telegram.ui.ActionBar.Theme
 * и всю систему blur3/glass -- их больше не найти по старым именам
 * (org.telegram.ui.ActionBar.Theme, BlurredBackgroundSourceColor и т.д.),
 * что раньше приводило к "ClassNotFoundException" в логе и потере части
 * эффекта (белая шапка вернулась).
 *
 * Для 12.10.3 (versionCode 70892) вручную найдено соответствие через
 * анализ decompiled smali (строки типа "windowBackgroundWhite" остаются
 * читаемыми даже после обфускации кода, т.к. используются для экспорта
 * тем в текстовом .attheme формате -- по ним удалось восстановить карту):
 *
 *   Theme                              -> o6
 *   Theme.getColor(I[ZZ)I              -> o6.w0([ZIZ)I  (ВНИМАНИЕ: порядок
 *                                          параметров тоже сменился на
 *                                          (boolean[], int, boolean)!)
 *   Theme.getColor(I)I                 -> o6.u0(I)I
 *   key_windowBackgroundWhite          -> o6.d6
 *   key_windowBackgroundUnchecked      -> o6.e6
 *   key_windowBackgroundWhiteBlackText -> o6.G6
 *   key_windowBackgroundGray           -> o6.a7
 *   key_actionBarDefault               -> o6.s8
 *   key_actionBarDefaultArchived       -> o6.M8
 *
 * ВАЖНО, честно: эти обфусцированные имена ПРИВЯЗАНЫ К КОНКРЕТНОЙ СБОРКЕ.
 * R8 переприсваивает короткие имена заново при каждой пересборке --
 * нет никакой гарантии, что "o6"/"d6" останутся теми же в СЛЕДУЮЩЕЙ
 * версии Telegram. Это не "универсальное решение навсегда", а рабочий
 * снимок под конкретный build. Когда Telegram в очередной раз обновится
 * и обфускация снова "уплывёт" -- потребуется повторить тот же анализ
 * (искать классы по строкам типа "windowBackgroundWhite", которые
 * остаются нетронутыми обфускацией) и добавить новый набор имён в
 * OBFUSCATED_CANDIDATES ниже, не переписывая всё остальное.
 *
 * Стратегия отказоустойчивости: пробуем СНАЧАЛА обычные (старые) имена,
 * ЗАТЕМ известные наборы обфусцированных имён по очереди, пока что-то
 * не найдётся. Если не найдётся ничего -- модуль не падает (try/catch
 * на каждом кандидате), просто эта конкретная правка не применится,
 * а универсальные hooks на View/Canvas (не зависящие от имён Telegram
 * вообще) продолжат работать как есть.
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
    private static final int BLUR_ALPHA = 0x40;
    private static final int TEXT_COLOR_LIGHT = Color.argb(0xFF, 0xEE, 0xEE, 0xEE);

    // ---------- Обычные (неофбусцированные) имена -- работают на старых версиях ----------
    private static final String THEME_CLASS_PLAIN = "org.telegram.ui.ActionBar.Theme";
    private static final String[] BACKGROUND_KEY_NAMES_PLAIN = {
            "key_windowBackgroundWhite",
            "key_windowBackgroundGray",
            "key_windowBackgroundUnchecked",
            "key_actionBarDefault",
            "key_actionBarDefaultArchived",
    };
    private static final String[] TEXT_KEY_NAMES_PLAIN = {
            "key_windowBackgroundWhiteBlackText",
            "key_windowBackgroundWhiteGrayText",
            "key_actionBarDefaultTitle",
            "key_actionBarDefaultIcon",
    };

    // ---------- Обфусцированные имена для конкретных известных сборок ----------
    // Каждая запись -- "снимок" под один build. Добавляйте новые записи сюда
    // по мере того как Telegram обновляется и обфускация "уплывает" -- ищите
    // классы через grep по строкам типа "windowBackgroundWhite" в decompiled
    // smali (см. подробности в комментарии класса выше).
    private static final ObfuscatedProfile[] OBFUSCATED_PROFILES = {
            // 12.10.3 (versionCode 70892)
            new ObfuscatedProfile(
                    "org.telegram.ui.ActionBar.o6",
                    new String[]{"d6", "e6", "a7", "s8", "M8"},
                    new String[]{"G6"}
            ),
    };

    private static final class ObfuscatedProfile {
        final String themeClassName;
        final String[] backgroundFieldNames;
        final String[] textFieldNames;

        ObfuscatedProfile(String themeClassName, String[] backgroundFieldNames, String[] textFieldNames) {
            this.themeClassName = themeClassName;
            this.backgroundFieldNames = backgroundFieldNames;
            this.textFieldNames = textFieldNames;
        }
    }

    private static volatile Set<Integer> backgroundKeys = null;
    private static volatile Set<Integer> textKeys = null;
    private static volatile Map<Integer, String> keyNamesByValue = null;
    private static final Object KEYS_LOCK = new Object();
    private static volatile boolean keysResolveFailed = false;

    private static final AtomicInteger getColorPatchLogCount = new AtomicInteger(0);

    /**
     * Ленивое разрешение ключей темы. НЕ вызывать из handleLoadPackage
     * напрямую -- обращение к статическим полям Theme/o6 форсирует их
     * <clinit>, а тот на раннем этапе (до готовности Context) падает с
     * NullPointerException и НАВСЕГДА ломает класс для всего процесса
     * (NoClassDefFoundError). Вызывать только ИЗНУТРИ уже сработавшего
     * хука на getColor -- к этому моменту класс темы гарантированно
     * готов, т.к. именно он вызывается.
     */
    private static void resolveBackgroundKeys(ClassLoader cl) {
        if (backgroundKeys != null || keysResolveFailed) {
            return;
        }
        synchronized (KEYS_LOCK) {
            if (backgroundKeys != null || keysResolveFailed) {
                return;
            }

            Set<Integer> bgKeys = new HashSet<>();
            Set<Integer> txtKeys = new HashSet<>();
            Map<Integer, String> names = new HashMap<>();

            // 1) обычные имена
            try {
                Class<?> themeClass = XposedHelpers.findClass(THEME_CLASS_PLAIN, cl);
                for (String keyName : BACKGROUND_KEY_NAMES_PLAIN) {
                    try {
                        int keyValue = XposedHelpers.getStaticIntField(themeClass, keyName);
                        bgKeys.add(keyValue);
                        names.put(keyValue, keyName);
                    } catch (Throwable ignored) {
                    }
                }
                for (String keyName : TEXT_KEY_NAMES_PLAIN) {
                    try {
                        int keyValue = XposedHelpers.getStaticIntField(themeClass, keyName);
                        txtKeys.add(keyValue);
                        names.put(keyValue, keyName);
                    } catch (Throwable ignored) {
                    }
                }
                XposedBridge.log("[TransparentTelegram] обычные имена Theme найдены");
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] обычные имена Theme не найдены (ok, пробуем обфусцированные): " + t);
            }

            // 2) известные обфусцированные профили -- пробуем все по очереди
            for (ObfuscatedProfile profile : OBFUSCATED_PROFILES) {
                try {
                    Class<?> themeClass = XposedHelpers.findClass(profile.themeClassName, cl);
                    boolean any = false;
                    for (String fieldName : profile.backgroundFieldNames) {
                        try {
                            int keyValue = XposedHelpers.getStaticIntField(themeClass, fieldName);
                            bgKeys.add(keyValue);
                            names.put(keyValue, profile.themeClassName + "." + fieldName);
                            any = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    for (String fieldName : profile.textFieldNames) {
                        try {
                            int keyValue = XposedHelpers.getStaticIntField(themeClass, fieldName);
                            txtKeys.add(keyValue);
                            names.put(keyValue, profile.themeClassName + "." + fieldName);
                            any = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (any) {
                        XposedBridge.log("[TransparentTelegram] обфусцированный профиль сработал: " + profile.themeClassName);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[TransparentTelegram] обфусцированный профиль " + profile.themeClassName + " не подошёл (ok): " + t);
                }
            }

            if (bgKeys.isEmpty() && txtKeys.isEmpty()) {
                keysResolveFailed = true;
                XposedBridge.log("[TransparentTelegram] НИ ОДИН профиль ключей не сработал -- "
                        + "Theme.getColor хук не сможет патчить цвета по ключу для этой версии. "
                        + "Универсальные View/Canvas-хуки продолжат работать как есть.");
                return;
            }

            keyNamesByValue = names;
            backgroundKeys = bgKeys;
            textKeys = txtKeys;
            XposedBridge.log("[TransparentTelegram] ключи разрешены: bg=" + bgKeys.size() + ", text=" + txtKeys.size());
        }
    }

    private static boolean isDarkColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        return luminance < 110;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("[TransparentTelegram] Loading: " + packageName);

        // ---------- 1. LaunchActivity: окно ----------
        try {
            Class<?> launchActivityClass = XposedHelpers.findClass(LAUNCH_ACTIVITY_CLASS, cl);

            XposedHelpers.findAndHookMethod(launchActivityClass, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                prepareWindow((Activity) param.thisObject);
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
                            try {
                                applyTransparency((Activity) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[TransparentTelegram] onResume failed: " + t);
                            }
                        }
                    });

            XposedBridge.log("[TransparentTelegram] LaunchActivity hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] LaunchActivity hook failed for " + packageName + ": " + t);
        }

        // ---------- 2. Theme.getColor -- все известные варианты сигнатуры/имени ----------
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class, boolean[].class, boolean.class}, 0);
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class}, 0);
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class, boolean[].class}, 0);

        for (ObfuscatedProfile profile : OBFUSCATED_PROFILES) {
            // getColor(I[ZZ)I стал w0([ZIZ)I -- ключ (int) теперь ВТОРОЙ параметр (индекс 1)
            hookGetColorVariant(cl, profile.themeClassName, "w0",
                    new Class<?>[]{boolean[].class, int.class, boolean.class}, 1);
            // getColor(I)I стал u0(I)I -- ключ по-прежнему единственный параметр (индекс 0)
            hookGetColorVariant(cl, profile.themeClassName, "u0",
                    new Class<?>[]{int.class}, 0);
        }

        // ---------- 2c. View.setBackgroundColor / setBackground -- не зависит от имён Telegram ----------
        try {
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int color = (Integer) param.args[0];
                                if (Color.alpha(color) == 255) {
                                    param.args[0] = (color & 0x00FFFFFF) | (ALPHA << 24);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(View.class, "setBackground", Drawable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Drawable d = (Drawable) param.args[0];
                                if (d == null) return;

                                if (d instanceof ColorDrawable) {
                                    int c = ((ColorDrawable) d).getColor();
                                    if (Color.alpha(c) == 255) {
                                        param.args[0] = new ColorDrawable((c & 0x00FFFFFF) | (ALPHA << 24));
                                    }
                                    return;
                                }

                                if (d.getOpacity() == PixelFormat.OPAQUE) {
                                    d.mutate().setAlpha(ALPHA);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedBridge.log("[TransparentTelegram] View background hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] View background hooks failed for " + packageName + ": " + t);
        }

        // ---------- 2d. Canvas.drawColor / drawRect -- тоже не зависит от имён Telegram ----------
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int c = (Integer) param.args[0];
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    param.args[0] = WINDOW_BACKGROUND_COLOR;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawRect",
                    float.class, float.class, float.class, float.class, android.graphics.Paint.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.graphics.Paint paint = (android.graphics.Paint) param.args[4];
                                if (paint == null) return;
                                int c = paint.getColor();
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    paint.setColor(WINDOW_BACKGROUND_COLOR);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawRect",
                    android.graphics.RectF.class, android.graphics.Paint.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.graphics.Paint paint = (android.graphics.Paint) param.args[1];
                                if (paint == null) return;
                                int c = paint.getColor();
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    paint.setColor(WINDOW_BACKGROUND_COLOR);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedBridge.log("[TransparentTelegram] Canvas hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Canvas hooks failed for " + packageName + ": " + t);
        }

        // ---------- 3. ActionBar.setBackgroundColor (только неофбусцированные версии) ----------
        try {
            Class<?> actionBarClass = XposedHelpers.findClass("org.telegram.ui.ActionBar.ActionBar", cl);
            XposedHelpers.findAndHookMethod(actionBarClass, "setBackgroundColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int original = (Integer) param.args[0];
                            if (Color.alpha(original) == 255) {
                                param.args[0] = WINDOW_BACKGROUND_COLOR;
                            }
                        }
                    });
            XposedBridge.log("[TransparentTelegram] ActionBar.setBackgroundColor hook installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] ActionBar hook failed for " + packageName + " (ok if obfuscated build): " + t);
        }

        // ---------- 4. BlurredBackgroundSourceColor.setColor (только неофбусцированные версии) ----------
        try {
            Class<?> sourceColorClass = XposedHelpers.findClass(
                    "org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor", cl);
            XposedHelpers.findAndHookMethod(sourceColorClass, "setColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int original = (Integer) param.args[0];
                            if (Color.alpha(original) == 255) {
                                param.args[0] = WINDOW_BACKGROUND_COLOR;
                            }
                        }
                    });
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundSourceColor hook installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundSourceColor hook failed for " + packageName + " (ok if obfuscated build): " + t);
        }

        // ---------- 5. BlurredBackgroundColorProviderThemed (только неофбусцированные версии) ----------
        try {
            Class<?> providerClass = XposedHelpers.findClass(
                    "org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed", cl);
            for (String methodName : new String[]{"getBackgroundColor", "getStrokeColorTop", "getStrokeColorBottom"}) {
                try {
                    XposedHelpers.findAndHookMethod(providerClass, methodName,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    int original = (Integer) param.getResult();
                                    if (Color.alpha(original) == 255) {
                                        param.setResult(WINDOW_BACKGROUND_COLOR);
                                    }
                                }
                            });
                } catch (Throwable t) {
                    XposedBridge.log("[TransparentTelegram] hook for " + methodName + " failed: " + t);
                }
            }
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook failed for " + packageName + " (ok if obfuscated build): " + t);
        }

        // ---------- 6. DialogsActivityTopBubblesFadeView.setColor (только неофбусцированные версии) ----------
        try {
            Class<?> fadeViewClass = XposedHelpers.findClass(
                    "org.telegram.ui.Components.DialogsActivityTopBubblesFadeView", cl);
            XposedHelpers.findAndHookMethod(fadeViewClass, "setColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int original = (Integer) param.args[0];
                            if (Color.alpha(original) == 255) {
                                param.args[0] = WINDOW_BACKGROUND_COLOR;
                            }
                        }
                    });
            XposedBridge.log("[TransparentTelegram] DialogsActivityTopBubblesFadeView hook installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] DialogsActivityTopBubblesFadeView hook failed for " + packageName + " (ok if obfuscated build): " + t);
        }
    }

    /**
     * Общий хелпер для хука любого варианта getColor -- обычного или
     * обфусцированного, с любым порядком параметров. keyArgIndex --
     * индекс параметра, который является int-ключом темы (0 или 1 в
     * известных нам вариантах).
     */
    private void hookGetColorVariant(final ClassLoader cl, final String className,
                                      final String methodName, final Class<?>[] paramTypes,
                                      final int keyArgIndex) {
        try {
            Class<?> targetClass = XposedHelpers.findClass(className, cl);
            Method method = targetClass.getDeclaredMethod(methodName, paramTypes);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    resolveBackgroundKeys(cl);
                    if (backgroundKeys == null && textKeys == null) return;

                    int key = (Integer) param.args[keyArgIndex];
                    Object resultObj = param.getResult();
                    if (!(resultObj instanceof Integer)) return;
                    int original = (Integer) resultObj;

                    if (backgroundKeys != null && backgroundKeys.contains(key)) {
                        if (Color.alpha(original) == 255) {
                            param.setResult(WINDOW_BACKGROUND_COLOR);
                            if (getColorPatchLogCount.incrementAndGet() <= 60) {
                                String name = keyNamesByValue != null ? keyNamesByValue.get(key) : null;
                                XposedBridge.log("[TransparentTelegram] BG " + className + "." + methodName
                                        + "(" + name + "): " + Integer.toHexString(original)
                                        + " -> " + Integer.toHexString(WINDOW_BACKGROUND_COLOR));
                            }
                        }
                        return;
                    }

                    if (textKeys != null && textKeys.contains(key)) {
                        if (Color.alpha(original) == 255 && isDarkColor(original)) {
                            param.setResult(TEXT_COLOR_LIGHT);
                        }
                    }
                }
            });
            XposedBridge.log("[TransparentTelegram] hooked " + className + "." + methodName
                    + Arrays.toString(paramTypes));
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] hook " + className + "." + methodName
                    + Arrays.toString(paramTypes) + " failed (ok, пробуем другие варианты): " + t);
        }
    }

    private void prepareWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));
    }

    private static final java.util.WeakHashMap<View, Boolean> LISTENER_ATTACHED = new java.util.WeakHashMap<>();
    private static volatile long lastScanTime = 0L;
    private static final long SCAN_THROTTLE_MS = 400L;

    private void applyTransparency(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));

        final View root = window.getDecorView();
        if (root == null) {
            return;
        }

        root.post(new Runnable() {
            @Override
            public void run() {
                scanNow(root);
            }
        });

        synchronized (LISTENER_ATTACHED) {
            if (!Boolean.TRUE.equals(LISTENER_ATTACHED.get(root))) {
                LISTENER_ATTACHED.put(root, Boolean.TRUE);
                root.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                long now = System.currentTimeMillis();
                                if (now - lastScanTime >= SCAN_THROTTLE_MS) {
                                    lastScanTime = now;
                                    scanNow(root);
                                }
                            }
                        });
            }
        }
    }

    private void scanNow(View root) {
        try {
            stripOpaqueBackgrounds(root, root.getWidth(), root.getHeight(), 0);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] stripOpaqueBackgrounds failed: " + t);
        }
    }

    private void stripOpaqueBackgrounds(View view, int rootWidth, int rootHeight, int depth) {
        if (view == null || depth > 40) {
            return;
        }

        Drawable bg = view.getBackground();

        if (bg != null && isBlurDrawable(bg)) {
            try {
                bg.mutate().setAlpha(BLUR_ALPHA);
            } catch (Throwable ignored) {
            }
        } else if (bg != null && isEffectivelyOpaque(bg)) {
            boolean fullWidth = view.getWidth() >= rootWidth * 0.85f;
            boolean fullHeight = view.getHeight() >= rootHeight * 0.85f;
            if (fullWidth && fullHeight) {
                try {
                    if (bg instanceof ColorDrawable) {
                        view.setBackgroundColor(WINDOW_BACKGROUND_COLOR);
                    } else {
                        bg.mutate().setAlpha(ALPHA);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                stripOpaqueBackgrounds(group.getChildAt(i), rootWidth, rootHeight, depth + 1);
            }
        }
    }

    private boolean isEffectivelyOpaque(Drawable d) {
        if (d instanceof ColorDrawable) {
            return Color.alpha(((ColorDrawable) d).getColor()) == 255;
        }
        return d.getOpacity() == PixelFormat.OPAQUE;
    }

    private boolean isBlurDrawable(Drawable d) {
        String name = d.getClass().getName().toLowerCase();
        return name.contains("blur");
    }
}
