package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Transparent Telegram v5 -- универсальная версия (без привязки к именам R8).
 *
 * Что нового по сравнению с v4 (проверено разбором dex беты 12.10.6):
 *
 *  A. Цвета через провайдер ресурсов.
 *     В бете почти все цвета UI берутся через static-метод Theme (i6.v0) с сигнатурой (int, ResourceProvider).
 *     Если провайдер не null (а в чате он всегда не null), метод вызывает provider.G0(key)
 *     и НЕ заходит в корневой getColor(boolean[], int, boolean). Поэтому в чате хук на корневой метод
 *     не срабатывал: стеклянные панели (шапка, закреп) оставались тёмными и почти непрозрачными.
 *     Теперь хукается и этот вариант: static int-метод Theme с параметрами (int, <interface>).
 *
 *  B. Фон чата.
 *     Обои чата рисует вложенный View внутри SizeNotifierFrameLayout (в 12.10.6 -- cw0),
 *     и только пока флаг skipBackgroundDrawing == false (проверено по байткоду: при true onDraw
 *     пропускает весь блок отрисовки обоев). Класс ищется структурно: класс пакета
 *     org.telegram.ui.Components, у которого есть метод setSkipBackgroundDrawing(boolean)
 *     (имя метода R8 не переименовывает). На нём: аргумент принудительно true, плюс true
 *     ставится при onAttachedToWindow.
 *
 *  C. Прозрачность панелей отдельно.
 *     Для ключей из PANEL_NAMES альфа принудительно опускается до PANEL_ALPHA
 *     (чем меньше, тем прозрачнее). Крутите константу под вкус.
 *
 * Остальное как в v4: Theme = класс org.telegram.ui.ActionBar с максимумом static int-полей,
 * карта "ключ -> имя" -- static SparseArray-метод без аргументов со строкой "windowBackgroundWhite".
 *
 * Что сломает модуль: смена пакетов org.telegram.ui.ActionBar / org.telegram.ui.Components,
 * переименование LaunchActivity, изменение строк ключей темы или имени setSkipBackgroundDrawing.
 * Всё это видно в логе Xposed по префиксу [TT].
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
    private static final String ACTIONBAR_PKG = "org.telegram.ui.ActionBar.";
    private static final String COMPONENTS_PKG = "org.telegram.ui.Components.";
    private static final String SIZE_NOTIFIER_NAME = "org.telegram.ui.Components.SizeNotifierFrameLayout";
    private static final String SKIP_BG_METHOD = "setSkipBackgroundDrawing";

    /**
     * Общее затемнение: чёрный с этой альфой кладётся на окно и все "залитые" фоны.
     * Было 0x80 (50%) -- отсюда общая темнота. 0x00 = без затемнения, 0xFF = сплошной чёрный.
     */
    private static final int ALPHA = 0x60;
    private static final int WINDOW_BACKGROUND_COLOR = Color.argb(ALPHA, 0, 0, 0);
    private static final int BLUR_ALPHA = 0x40;
    private static final int TEXT_COLOR_LIGHT = Color.argb(0xFF, 0xEE, 0xEE, 0xEE);

    /**
     * Максимальная альфа для "панельных" ключей (закреп, верхние панели чата).
     * 0x00 -- полностью прозрачно, 0xFF -- как было. Если баннер всё ещё тёмный, уменьшайте.
     */
    private static final int PANEL_ALPHA = 0x28;

    /** Минимум static int-полей у класса Theme (в реальности ~850). */
    private static final int THEME_MIN_STATIC_INTS = 200;

    /** Имена ключей темы (строки, R8 их не трогает). */
    private static final String[] BG_NAMES = {
            "windowBackgroundWhite",
            "windowBackgroundGray",
            "windowBackgroundUnchecked",
            "chat_wallpaper",
    };
    private static final String[] TEXT_NAMES = {
            "windowBackgroundWhiteBlackText",
            "windowBackgroundWhiteGrayText",
            "actionBarDefaultTitle",
            "actionBarDefaultIcon",
    };
    /**
     * Панели: альфа ограничивается сверху PANEL_ALPHA, цвет сохраняется.
     * Если шапка чата (кнопки назад/звонок/заголовок) всё ещё тёмная, добавьте сюда
     * "glass_targetMainTopPanel" -- но он же влияет на верхнюю панель списка чатов.
     */
    private static final String[] PANEL_NAMES = {
            "chat_topPanelBackground",
            "actionBarDefault",
            "actionBarDefaultArchived",
            "glass_targetMainTopPanel",
    };
    private static final String KEY_MARKER = "windowBackgroundWhite";

    private static volatile List<Class<?>> uiClasses = null;

    private static volatile Set<Integer> backgroundKeys = null;
    private static volatile Set<Integer> textKeys = null;
    private static volatile Set<Integer> panelKeys = null;
    private static volatile Map<Integer, String> keyNamesByValue = null;
    private static final Object KEYS_LOCK = new Object();
    private static volatile boolean keysResolveFailed = false;
    private static volatile boolean resolving = false;

    private static final AtomicInteger getColorPatchLogCount = new AtomicInteger(0);

    // =====================================================================
    // Структурный поиск
    // =====================================================================

    /** Верхнеуровневые классы пакета (без вложенных), БЕЗ запуска <clinit>. */
    private static List<Class<?>> listClasses(ClassLoader cl, String prefix) {
        List<Class<?>> out = new ArrayList<>();
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object element : elements) {
                Object df = XposedHelpers.getObjectField(element, "dexFile");
                if (df == null) continue;
                Enumeration<String> en = ((DexFile) df).entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement();
                    if (!name.startsWith(prefix) || name.indexOf('$') >= 0) continue;
                    try {
                        // initialize=false: не форсируем <clinit>, иначе Theme упадёт раньше времени
                        out.add(Class.forName(name, false, cl));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[TT] listClasses(" + prefix + ") failed: " + t);
        }
        return out;
    }

    private static Class<?> findThemeClass(List<Class<?>> classes) {
        Class<?> best = null;
        int bestCount = 0;
        for (Class<?> c : classes) {
            int cnt = 0;
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) cnt++;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (cnt > bestCount) {
                bestCount = cnt;
                best = c;
            }
        }
        if (best != null && bestCount >= THEME_MIN_STATIC_INTS) {
            XposedBridge.log("[TT] Theme class = " + best.getName() + " (static ints: " + bestCount + ")");
            return best;
        }
        XposedBridge.log("[TT] Theme class не найден (max static ints = " + bestCount + ")");
        return null;
    }

    /** Индекс первого int-параметра или -1. */
    private static int firstIntIndex(Class<?>[] p) {
        for (int i = 0; i < p.length; i++) if (p[i] == int.class) return i;
        return -1;
    }

    private static boolean onlyColorParamTypes(Class<?>[] p) {
        for (Class<?> t : p) {
            if (t != int.class && t != boolean.class && t != boolean[].class) return false;
        }
        return true;
    }

    /**
     * Все точки входа getColor:
     *  - корневой: static int-метод с boolean[] и одним int (в 12.10.6 -- w0);
     *  - вариант с провайдером ресурсов: static int-метод (int, <interface>) (в 12.10.6 -- v0);
     *    именно им пользуются чат и стеклянные панели;
     *  - запасной вариант, если корневого нет: static (I)I.
     */
    private static List<Method> findGetColorMethods(Class<?> themeClass) {
        List<Method> primary = new ArrayList<>();
        List<Method> providerVariants = new ArrayList<>();
        List<Method> fallback = new ArrayList<>();
        Method[] methods;
        try {
            methods = themeClass.getDeclaredMethods();
        } catch (Throwable t) {
            XposedBridge.log("[TT] getDeclaredMethods(Theme) failed: " + t);
            return primary;
        }
        for (Method m : methods) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != int.class) continue;
            Class<?>[] p = m.getParameterTypes();

            // (int key, ResourceProvider provider)
            if (p.length == 2 && p[0] == int.class && !p[1].isPrimitive() && p[1].isInterface()) {
                providerVariants.add(m);
                continue;
            }

            if (p.length == 0 || p.length > 3 || !onlyColorParamTypes(p)) continue;

            int ints = 0;
            boolean hasBoolArray = false;
            for (Class<?> t : p) {
                if (t == int.class) ints++;
                if (t == boolean[].class) hasBoolArray = true;
            }
            if (ints != 1) continue;

            if (hasBoolArray) primary.add(m);
            else if (p.length == 1) fallback.add(m);
        }
        List<Method> result = new ArrayList<>(primary.isEmpty() ? fallback : primary);
        result.addAll(providerVariants);
        return result;
    }

    /** Ищет static no-arg метод -> SparseArray с "windowBackgroundWhite" и возвращает имя->ключ. */
    private static Map<String, Integer> findNameToKeyMap(List<Class<?>> classes) {
        for (Class<?> c : classes) {
            Method[] ms;
            try {
                ms = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : ms) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != SparseArray.class) continue;
                try {
                    m.setAccessible(true);
                    Object res = m.invoke(null);
                    Map<String, Integer> map = invert(res);
                    if (map != null && map.containsKey(KEY_MARKER)) {
                        XposedBridge.log("[TT] карта ключей: " + c.getName() + "." + m.getName()
                                + "() -> " + map.size() + " записей");
                        return map;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> invert(Object res) {
        if (!(res instanceof SparseArray)) return null;
        SparseArray<Object> sa = (SparseArray<Object>) res;
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < sa.size(); i++) {
            Object v = sa.valueAt(i);
            if (v instanceof String) out.put((String) v, sa.keyAt(i));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Ленивое разрешение ключей. Только изнутри сработавшего хука getColor:
     * к этому моменту Theme уже инициализирован.
     */
    private static void resolveKeys() {
        if (backgroundKeys != null || keysResolveFailed || resolving) return;
        synchronized (KEYS_LOCK) {
            if (backgroundKeys != null || keysResolveFailed || resolving) return;
            resolving = true;
            try {
                List<Class<?>> classes = uiClasses;
                if (classes == null) {
                    keysResolveFailed = true;
                    return;
                }
                Map<String, Integer> nameToKey = findNameToKeyMap(classes);
                if (nameToKey == null) {
                    keysResolveFailed = true;
                    XposedBridge.log("[TT] карта ключей не найдена -- getColor-хук не будет патчить цвета. "
                            + "View/Canvas-хуки работают как обычно.");
                    return;
                }

                Set<Integer> bg = new HashSet<>();
                Set<Integer> txt = new HashSet<>();
                Set<Integer> pnl = new HashSet<>();
                Map<Integer, String> names = new HashMap<>();

                for (String n : BG_NAMES) {
                    Integer k = nameToKey.get(n);
                    if (k != null) {
                        bg.add(k);
                        names.put(k, n);
                    }
                }
                for (String n : TEXT_NAMES) {
                    Integer k = nameToKey.get(n);
                    if (k != null) {
                        txt.add(k);
                        names.put(k, n);
                    }
                }
                for (String n : PANEL_NAMES) {
                    Integer k = nameToKey.get(n);
                    if (k != null && !bg.contains(k)) {
                        pnl.add(k);
                        names.put(k, n);
                    }
                }
                if (bg.isEmpty() && txt.isEmpty() && pnl.isEmpty()) {
                    keysResolveFailed = true;
                    XposedBridge.log("[TT] нужные имена ключей не найдены в карте");
                    return;
                }
                keyNamesByValue = names;
                textKeys = txt;
                panelKeys = pnl;
                backgroundKeys = bg; // последним: по нему проверяется готовность
                XposedBridge.log("[TT] ключи разрешены: bg=" + bg.size() + ", text=" + txt.size()
                        + ", panel=" + pnl.size());
            } finally {
                resolving = false;
            }
        }
    }

    private static boolean isDarkColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        return luminance < 110;
    }

    // =====================================================================
    // Фон чата: SizeNotifierFrameLayout.setSkipBackgroundDrawing
    // =====================================================================

    private static final WeakHashMap<Object, Boolean> SKIP_APPLIED = new WeakHashMap<>();

    private static Class<?> findSkipBackgroundClass(ClassLoader cl) {
        // 1. Необфусцированное имя (стабильные сборки, форки).
        Class<?> direct = XposedHelpers.findClassIfExists(SIZE_NOTIFIER_NAME, cl);
        if (direct != null && hasSkipMethod(direct)) return direct;

        // 2. Структурно: класс-View в org.telegram.ui.Components с методом setSkipBackgroundDrawing(boolean).
        for (Class<?> k : listClasses(cl, COMPONENTS_PKG)) {
            try {
                if (!View.class.isAssignableFrom(k)) continue;
                if (hasSkipMethod(k)) return k;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean hasSkipMethod(Class<?> k) {
        try {
            k.getDeclaredMethod(SKIP_BG_METHOD, boolean.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookChatBackground(ClassLoader cl) {
        try {
            final Class<?> sn = findSkipBackgroundClass(cl);
            if (sn == null) {
                XposedBridge.log("[TT] класс с " + SKIP_BG_METHOD + " не найден -- обои чата не отключены");
                return;
            }
            XposedBridge.log("[TT] SizeNotifier class = " + sn.getName());

            // Всегда true: ChatActivity сам вызывает setSkipBackgroundDrawing(false) после анимаций.
            XposedHelpers.findAndHookMethod(sn, SKIP_BG_METHOD, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = Boolean.TRUE;
                }
            });

            // Один раз на экземпляр: включаем пропуск отрисовки обоев.
            XposedHelpers.findAndHookMethod(sn, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        synchronized (SKIP_APPLIED) {
                            if (SKIP_APPLIED.containsKey(self)) return;
                            SKIP_APPLIED.put(self, Boolean.TRUE);
                        }
                        XposedHelpers.callMethod(self, SKIP_BG_METHOD, Boolean.TRUE);
                    } catch (Throwable t) {
                        XposedBridge.log("[TT] skip background failed: " + t);
                    }
                }
            });
            XposedBridge.log("[TT] chat background hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("[TT] hookChatBackground failed: " + t);
        }
    }

    // =====================================================================
    // Стеклянные панели (liquid glass): шапки, закреп, пузыри в чате
    // =====================================================================

    /** Во сколько раз ослабить цвет-тонировку стекла (1.0 = как было, 0.0 = без тонировки). */
    private static final float GLASS_TINT_SCALE = 0.35f;
    /** Потолок альфы для полупрозрачных тёмных drawColor (тонировка стекла рисуется через drawColor). */
    private static final int GLASS_DRAWCOLOR_CAP = 0x30;
    /** false -- не трогать полупрозрачные drawColor (если что-то лишнее стало прозрачным). */
    private static final boolean GLASS_PATCH_DRAWCOLOR = true;

    private static final AtomicInteger glassLogCount = new AtomicInteger(0);

    private static void hookGlass() {
        // 1. Шейдер стекла принимает цвет тонировки как uniform "foreground_color_premultiplied"
        //    (premultiplied RGBA). Домножение всех четырёх компонент = ослабление альфы тонировки.
        try {
            Class<?> rs = XposedHelpers.findClassIfExists("android.graphics.RuntimeShader", null);
            if (rs != null) {
                XposedHelpers.findAndHookMethod(rs, "setFloatUniform", String.class,
                        float.class, float.class, float.class, float.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!"foreground_color_premultiplied".equals(param.args[0])) return;
                                    for (int i = 1; i <= 4; i++) {
                                        param.args[i] = ((Float) param.args[i]) * GLASS_TINT_SCALE;
                                    }
                                    if (glassLogCount.incrementAndGet() <= 5) {
                                        XposedBridge.log("[TT] glass tint scaled x" + GLASS_TINT_SCALE);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                XposedBridge.log("[TT] RuntimeShader hook installed");
            } else {
                XposedBridge.log("[TT] RuntimeShader не найден (Android < 13?)");
            }
        } catch (Throwable t) {
            XposedBridge.log("[TT] RuntimeShader hook failed: " + t);
        }

        // 2. Тонировка также рисуется Canvas.drawColor на RenderNode-канвасе. Он RecordingCanvas,
        //    а RecordingCanvas переопределяет drawColor, поэтому хука на Canvas.drawColor мало.
        try {
            Class<?> brc = XposedHelpers.findClassIfExists("android.graphics.BaseRecordingCanvas", null);
            if (brc != null) {
                XposedHelpers.findAndHookMethod(brc, "drawColor", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int c = (Integer) param.args[0];
                            int a = Color.alpha(c);
                            if (!isDarkColor(c)) return;
                            if (a == 255) {
                                param.args[0] = WINDOW_BACKGROUND_COLOR;
                            } else if (GLASS_PATCH_DRAWCOLOR && a > GLASS_DRAWCOLOR_CAP) {
                                param.args[0] = (c & 0x00FFFFFF) | (GLASS_DRAWCOLOR_CAP << 24);
                                if (glassLogCount.incrementAndGet() <= 10) {
                                    XposedBridge.log("[TT] drawColor " + Integer.toHexString(c)
                                            + " -> " + Integer.toHexString((Integer) param.args[0]));
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log("[TT] BaseRecordingCanvas.drawColor hook installed");
            }
        } catch (Throwable t) {
            XposedBridge.log("[TT] BaseRecordingCanvas hook failed: " + t);
        }
    }

    // =====================================================================
    // Точка входа
    // =====================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("[TT] Loading: " + packageName);

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
                                XposedBridge.log("[TT] before onCreate failed: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                applyTransparency((Activity) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log("[TT] after onCreate failed: " + t);
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
                                XposedBridge.log("[TT] onResume failed: " + t);
                            }
                        }
                    });

            XposedBridge.log("[TT] LaunchActivity hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TT] LaunchActivity hook failed for " + packageName + ": " + t);
        }

        // ---------- 2. Theme.getColor: структурный поиск ----------
        try {
            List<Class<?>> classes = listClasses(cl, ACTIONBAR_PKG);
            uiClasses = classes;
            XposedBridge.log("[TT] классов в " + ACTIONBAR_PKG + ": " + classes.size());

            Class<?> themeClass = findThemeClass(classes);
            if (themeClass != null) {
                List<Method> getColors = findGetColorMethods(themeClass);
                if (getColors.isEmpty()) {
                    XposedBridge.log("[TT] getColor-методы в Theme не найдены");
                }
                for (Method m : getColors) {
                    hookColorMethod(m, firstIntIndex(m.getParameterTypes()));
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[TT] структурный поиск Theme упал: " + t);
        }

        // ---------- 2b. Фон чата ----------
        hookChatBackground(cl);

        // ---------- 2c. Стекло (шапки/закреп) ----------
        hookGlass();

        // ---------- 3. View.setBackgroundColor / setBackground ----------
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

            XposedBridge.log("[TT] View background hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TT] View background hooks failed for " + packageName + ": " + t);
        }

        // ---------- 4. Canvas.drawColor / drawRect ----------
        try {
            XposedHelpers.findAndHookMethod(Canvas.class, "drawColor", int.class,
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

            XposedHelpers.findAndHookMethod(Canvas.class, "drawRect",
                    float.class, float.class, float.class, float.class, Paint.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Paint paint = (Paint) param.args[4];
                                if (paint == null) return;
                                int c = paint.getColor();
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    paint.setColor(WINDOW_BACKGROUND_COLOR);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(Canvas.class, "drawRect",
                    RectF.class, Paint.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Paint paint = (Paint) param.args[1];
                                if (paint == null) return;
                                int c = paint.getColor();
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    paint.setColor(WINDOW_BACKGROUND_COLOR);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedBridge.log("[TT] Canvas hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TT] Canvas hooks failed for " + packageName + ": " + t);
        }
    }

    /** Хук одного варианта getColor. keyArgIndex -- индекс int-ключа в параметрах. */
    private void hookColorMethod(final Method method, final int keyArgIndex) {
        if (keyArgIndex < 0) return;
        final String label = method.getDeclaringClass().getName() + "." + method.getName();
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        resolveKeys();
                        Set<Integer> bg = backgroundKeys;
                        Set<Integer> txt = textKeys;
                        Set<Integer> pnl = panelKeys;
                        if (bg == null && txt == null && pnl == null) return;

                        int key = (Integer) param.args[keyArgIndex];
                        Object resultObj = param.getResult();
                        if (!(resultObj instanceof Integer)) return;
                        int original = (Integer) resultObj;

                        if (bg != null && bg.contains(key)) {
                            if (Color.alpha(original) == 255) {
                                param.setResult(WINDOW_BACKGROUND_COLOR);
                                if (getColorPatchLogCount.incrementAndGet() <= 60) {
                                    Map<Integer, String> names = keyNamesByValue;
                                    XposedBridge.log("[TT] BG " + label + "("
                                            + (names != null ? names.get(key) : key) + "): "
                                            + Integer.toHexString(original) + " -> "
                                            + Integer.toHexString(WINDOW_BACKGROUND_COLOR));
                                }
                            }
                            return;
                        }

                        if (pnl != null && pnl.contains(key)) {
                            if (Color.alpha(original) > PANEL_ALPHA) {
                                int patched = (original & 0x00FFFFFF) | (PANEL_ALPHA << 24);
                                param.setResult(patched);
                                if (getColorPatchLogCount.incrementAndGet() <= 60) {
                                    Map<Integer, String> names = keyNamesByValue;
                                    XposedBridge.log("[TT] PANEL " + label + "("
                                            + (names != null ? names.get(key) : key) + "): "
                                            + Integer.toHexString(original) + " -> "
                                            + Integer.toHexString(patched));
                                }
                            }
                            return;
                        }

                        if (txt != null && txt.contains(key)) {
                            if (Color.alpha(original) == 255 && isDarkColor(original)) {
                                param.setResult(TEXT_COLOR_LIGHT);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log("[TT] hooked " + label + Arrays.toString(method.getParameterTypes())
                    + " keyIdx=" + keyArgIndex);
        } catch (Throwable t) {
            XposedBridge.log("[TT] hook " + label + " failed: " + t);
        }
    }

    // =====================================================================
    // Окно и сканирование View-дерева
    // =====================================================================

    private void prepareWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));
    }

    private static final WeakHashMap<View, Boolean> LISTENER_ATTACHED = new WeakHashMap<>();
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
                        new ViewTreeObserver.OnGlobalLayoutListener() {
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
            XposedBridge.log("[TT] stripOpaqueBackgrounds failed: " + t);
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

    /**
     * Блюр-drawable определяется по имени класса. В обфусцированных сборках
     * имя может быть коротким и не содержать "blur" -- тогда этот путь не сработает,
     * и шапку/стекло прикрывают только Theme.getColor и View/Canvas-хуки.
     */
    private boolean isBlurDrawable(Drawable d) {
        return d.getClass().getName().toLowerCase().contains("blur");
    }
}
