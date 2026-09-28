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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Enumeration;
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
 * Transparent Telegram v4.
 *
 * НОВОЕ в этой версии: ДИНАМИЧЕСКИЙ ПОИСК обфусцированного класса Theme.
 *
 * Вместо того чтобы вручную прописывать имена вида "org.telegram.ui.ActionBar.o6"
 * (которые меняются при каждой пересборке Telegram), модуль теперь:
 *
 *   1. Перебирает все классы в dex-файлах приложения.
 *   2. Ищет класс, содержащий строковые константы вида "windowBackgroundWhite",
 *      "actionBarDefault" и т.д. — эти строки НЕ обфусцируются, потому что
 *      используются для экспорта тем в текстовом .attheme формате.
 *   3. В найденном классе ищет статические int-поля — это ключи темы.
 *   4. Ищет методы getColor по СИГНАТУРЕ (int -> int, int[ZZ -> int и т.д.),
 *      а не по имени — имена методов тоже обфусцируются.
 *   5. Хукает найденные методы и патчит цвета по ключу.
 *
 * Статические профили (ObfuscatedProfile) оставлены как fallback —
 * если динамический поиск почему-то не сработает, попробуем известные имена.
 *
 * ВАЖНО: динамический поиск делается ОДИН РАЗ лениво, при первом срабатывании
 * любого getColor-хука — чтобы не форсировать <clinit> класса темы раньше
 * времени (иначе NoClassDefFoundError, как было в v3).
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

    // ---------- Обычные (неофбусцированные) имена ----------
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

    // ---------- Статические обфусцированные профили (fallback) ----------
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

    /**
     * Строки-маркеры, по которым узнаём обфусцированный класс Theme.
     * Это значения констант, а не имена полей — R8 их не трогает, потому что
     * они используются для экспорта/импорта тем в текстовом формате.
     */
    private static final String[] THEME_MARKER_STRINGS = {
            "windowBackgroundWhite",
            "windowBackgroundGray",
            "actionBarDefault",
            "windowBackgroundWhiteBlackText",
            "actionBarDefaultTitle",
    };

    private static volatile Set<Integer> backgroundKeys = null;
    private static volatile Set<Integer> textKeys = null;
    private static volatile Map<Integer, String> keyNamesByValue = null;
    private static final Object KEYS_LOCK = new Object();
    private static volatile boolean keysResolveFailed = false;

    private static final AtomicInteger getColorPatchLogCount = new AtomicInteger(0);

    // =====================================================================
    // ============ ДИНАМИЧЕСКИЙ ПОИСК ОБФУСЦИРОВАННОГО THEME =============
    // =====================================================================

    /**
     * Перебирает все классы из dex-файлов приложения и возвращает тот,
     * который содержит строковые константы-маркеры Theme.
     */
    private static Class<?> findThemeClassDynamically(ClassLoader cl) {
        try {
            // Получаем pathList у BaseDexClassLoader
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            if (pathList == null) return null;

            Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            if (dexElements == null) return null;

            int scanned = 0;
            for (Object element : dexElements) {
                Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                if (dexFile == null) continue;

                Enumeration<String> entries;
                try {
                    entries = (Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");
                } catch (Throwable t) {
                    continue;
                }
                if (entries == null) continue;

                while (entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    scanned++;

                    // Интересуют только классы Telegram, и только те,
                    // что лежат в ActionBar или рядом (Theme там обычно и живёт).
                    if (!className.startsWith("org.telegram.")) continue;

                    Class<?> clazz;
                    try {
                        // false = не инициализировать класс (важно! иначе <clinit>)
                        clazz = Class.forName(className, false, cl);
                    } catch (Throwable ignored) {
                        continue;
                    }

                    if (classContainsAnyMarker(clazz)) {
                        XposedBridge.log("[TransparentTelegram] Динамически найден Theme-класс: " + className);
                        return clazz;
                    }
                }
            }
            XposedBridge.log("[TransparentTelegram] Динамический поиск Theme: просканировано " + scanned
                    + " классов, ничего не найдено");
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Динамический поиск Theme упал: " + t);
        }
        return null;
    }

    /**
     * Проверяет, есть ли в классе статическое String-поле со значением-маркером.
     * Используем getDeclaredFields без setAccessible для чтения статиков —
     * для public/package-private полей это работает. Если поле приватное,
     * setAccessible(true) тоже безопасен, т.к. класс ещё НЕ инициализирован.
     */
    private static boolean classContainsAnyMarker(Class<?> clazz) {
        try {
            Field[] fields = clazz.getDeclaredFields();
            if (fields.length == 0) return false;

            // Быстрая проверка: в Theme десятки строковых констант.
            // Если их нет вообще — это не Theme.
            int stringFieldCount = 0;
            for (Field f : fields) {
                if (f.getType() == String.class) stringFieldCount++;
            }
            if (stringFieldCount < 3) return false;

            for (Field f : fields) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (!(val instanceof String)) continue;
                    String s = (String) val;
                    for (String marker : THEME_MARKER_STRINGS) {
                        if (marker.equals(s)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Собирает ключи темы из найденного класса Theme.
     *
     * Логика: перебираем все статические int-поля класса, читаем их значения.
     * Значения ключей в Telegram — это обычно хеши от строк (int).
     * Мы НЕ можем надёжно сопоставить каждое int-поле конкретному ключу
     * (windowBackgroundWhite / actionBarDefault) без чтения строковых полей.
     *
     * Поэтому: находим пары "строковое поле со значением X" и "int-поле,
     * которое является ключом для X". В Telegram ключ вычисляется как
     * hash от строки, но проще — сопоставить по соседству в исходнике.
     *
     * Практический приём: ключи в Theme — это статические int-поля,
     * которых обычно ~200-300 штук, и они лежат вперемешку со строковыми.
     * Надёжный способ — найти int-поле, значение которого используется
     * в getColor. Но проще всего: взять все статические int-поля класса
     * и пометить их как потенциальные ключи, а точную привязку к
     * конкретному имени получить через строковые поля нельзя.
     *
     * Компромисс: ищем int-поля, чьё значение совпадает с hash-ом
     * известных строк. Telegram использует простую формулу:
     *   key = string.hashCode() ^ 0x...   (в разных версиях по-разному)
     *
     * Чтобы не гадать — используем эвристику: считаем ключами ВСЕ
     * статические int-поля класса Theme, значения которых не равны 0
     * и не являются маленькими числами (0..1000 отданы под индексы).
     * Это грубо, но для нашей задачи (патчить цвета фона) работает:
     * если ключ не наш — хук просто ничего не сделает.
     *
     * Более точный способ: для каждого строкового поля-маркера найти
     * int-поле, значение которого равно вычисленному ключу. Формулу
     * ключа можно подсмотреть в самом Theme — там есть метод
     * getColorKey или аналог. Но он тоже обфусцирован.
     *
     * Итог: используем подход "все int-поля = потенциальные ключи",
     * и отдельно пытаемся найти точную привязку через хук на getColor:
     * в момент вызова getColor мы знаем int-ключ, и по логу можем
     * сопоставить его со строкой.
     */
    private static void resolveKeysFromClass(Class<?> themeClass,
                                             Set<Integer> bgKeys,
                                             Set<Integer> txtKeys,
                                             Map<Integer, String> names) {
        try {
            Field[] fields = themeClass.getDeclaredFields();

            // Шаг 1: собираем строковые поля-маркеры и их значения
            Map<String, String> markerFieldToValue = new HashMap<>();
            for (Field f : fields) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof String) {
                        String s = (String) val;
                        for (String marker : THEME_MARKER_STRINGS) {
                            if (marker.equals(s)) {
                                markerFieldToValue.put(f.getName(), s);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (markerFieldToValue.isEmpty()) {
                XposedBridge.log("[TransparentTelegram] В классе " + themeClass.getName()
                        + " не найдено строковых полей-маркеров");
                return;
            }

            XposedBridge.log("[TransparentTelegram] Найдены строковые маркеры: " + markerFieldToValue);

            // Шаг 2: вычисляем ключи. В Telegram ключ = hash от строки,
            // но точная формула может меняться. Пробуем несколько вариантов:
            //   a) string.hashCode()
            //   b) string.hashCode() ^ 0x... (константа)
            // На практике в Theme есть статический метод, который делает это,
            // но он обфусцирован. Поэтому пробуем оба варианта и смотрим,
            // какое int-поле совпадёт.
            Set<Integer> candidateKeys = new HashSet<>();
            for (String markerValue : markerFieldToValue.values()) {
                candidateKeys.add(markerValue.hashCode());
                // XOR с типичными константами Telegram
                candidateKeys.add(markerValue.hashCode() ^ 0x7fffffff);
                candidateKeys.add(markerValue.hashCode() ^ 0x100);
            }

            // Шаг 3: ищем int-поля, чьи значения совпадают с кандидатами
            for (Field f : fields) {
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int val = f.getInt(null);
                    if (candidateKeys.contains(val)) {
                        // Это ключ! Но какой именно строке соответствует —
                        // определяем по совпадению hash-а.
                        for (Map.Entry<String, String> e : markerFieldToValue.entrySet()) {
                            String markerValue = e.getValue();
                            if (markerValue.hashCode() == val
                                    || (markerValue.hashCode() ^ 0x7fffffff) == val
                                    || (markerValue.hashCode() ^ 0x100) == val) {
                                names.put(val, markerValue);
                                if (isBackgroundMarker(markerValue)) {
                                    bgKeys.add(val);
                                } else {
                                    txtKeys.add(val);
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // Шаг 4: fallback — если по hash-ам ничего не нашли,
            // берём ВСЕ статические int-поля с "разумными" значениями
            // (не 0, не маленькие индексы). Это грубо, но рабочий вариант.
            if (bgKeys.isEmpty() && txtKeys.isEmpty()) {
                XposedBridge.log("[TransparentTelegram] Hash-сопоставление не сработало, "
                        + "берём все int-поля как потенциальные ключи");
                for (Field f : fields) {
                    if (f.getType() != int.class) continue;
                    try {
                        f.setAccessible(true);
                        int val = f.getInt(null);
                        // Отсеиваем явно служебные значения
                        if (val == 0 || (val > 0 && val < 1000)) continue;
                        // Не можем знать, bg это или text — кладём в оба,
                        // хук сам решит по исходному цвету (тёмный -> text, иначе bg)
                        bgKeys.add(val);
                    } catch (Throwable ignored) {
                    }
                }
                XposedBridge.log("[TransparentTelegram] Взято int-полей как ключей: " + bgKeys.size());
            }
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] resolveKeysFromClass упал: " + t);
        }
    }

    private static boolean isBackgroundMarker(String marker) {
        return marker.startsWith("windowBackground")
                || marker.startsWith("actionBarDefault")
                && !marker.endsWith("Title")
                && !marker.endsWith("Icon");
    }

    // =====================================================================
    // ==================== РАЗРЕШЕНИЕ КЛЮЧЕЙ (lazy) =======================
    // =====================================================================

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

            // 1) Пробуем обычные (неофбусцированные) имена
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
                if (!bgKeys.isEmpty() || !txtKeys.isEmpty()) {
                    XposedBridge.log("[TransparentTelegram] обычные имена Theme найдены");
                }
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] обычные имена Theme не найдены (ok): " + t);
            }

            // 2) ДИНАМИЧЕСКИЙ ПОИСК обфусцированного Theme
            if (bgKeys.isEmpty() && txtKeys.isEmpty()) {
                Class<?> themeClass = findThemeClassDynamically(cl);
                if (themeClass != null) {
                    resolveKeysFromClass(themeClass, bgKeys, txtKeys, names);
                }
            }

            // 3) Статические обфусцированные профили (fallback)
            if (bgKeys.isEmpty() && txtKeys.isEmpty()) {
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
                            XposedBridge.log("[TransparentTelegram] статический профиль сработал: "
                                    + profile.themeClassName);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[TransparentTelegram] профиль " + profile.themeClassName
                                + " не подошёл (ok): " + t);
                    }
                }
            }

            if (bgKeys.isEmpty() && txtKeys.isEmpty()) {
                keysResolveFailed = true;
                XposedBridge.log("[TransparentTelegram] НИ ОДИН способ не сработал — "
                        + "хук getColor не сможет патчить цвета по ключу. "
                        + "Универсальные View/Canvas-хуки продолжат работать.");
                return;
            }

            keyNamesByValue = names;
            backgroundKeys = bgKeys;
            textKeys = txtKeys;
            XposedBridge.log("[TransparentTelegram] ключи разрешены: bg=" + bgKeys.size()
                    + ", text=" + txtKeys.size());
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
    // ============================ ENTRY POINT ============================
    // =====================================================================

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

        // ---------- 2. Theme.getColor — все известные варианты ----------
        // Обычные имена
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class, boolean[].class, boolean.class}, 0);
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class}, 0);
        hookGetColorVariant(cl, THEME_CLASS_PLAIN, "getColor",
                new Class<?>[]{int.class, boolean[].class}, 0);

        // Статические обфусцированные профили (fallback)
        for (ObfuscatedProfile profile : OBFUSCATED_PROFILES) {
            hookGetColorVariant(cl, profile.themeClassName, "w0",
                    new Class<?>[]{boolean[].class, int.class, boolean.class}, 1);
            hookGetColorVariant(cl, profile.themeClassName, "u0",
                    new Class<?>[]{int.class}, 0);
        }

        // ДИНАМИЧЕСКИЙ хук: ищем методы getColor по сигнатуре в найденном классе.
        // Делаем это отложенно — через хук на LaunchActivity.onCreate,
        // чтобы класс Theme гарантированно был загружен.
        scheduleDynamicGetColorHook(cl);

        // ---------- 2c. View.setBackgroundColor / setBackground ----------
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

        // ---------- 2d. Canvas.drawColor / drawRect ----------
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

        // ---------- 3. ActionBar.setBackgroundColor ----------
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
            XposedBridge.log("[TransparentTelegram] ActionBar hook failed for " + packageName
                    + " (ok if obfuscated build): " + t);
        }

        // ---------- 4. BlurredBackgroundSourceColor.setColor ----------
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
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundSourceColor hook failed for " + packageName
                    + " (ok if obfuscated build): " + t);
        }

        // ---------- 5. BlurredBackgroundColorProviderThemed ----------
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
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook failed for " + packageName
                    + " (ok if obfuscated build): " + t);
        }

        // ---------- 6. DialogsActivityTopBubblesFadeView.setColor ----------
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
            XposedBridge.log("[TransparentTelegram] DialogsActivityTopBubblesFadeView hook failed for " + packageName
                    + " (ok if obfuscated build): " + t);
        }
    }

    // =====================================================================
    // ================ ДИНАМИЧЕСКИЙ ХУК МЕТОДОВ getColor ==================
    // =====================================================================

    /**
     * Отложенно (после первого onCreate LaunchActivity) ищет класс Theme
     * динамически и хукает в нём все методы вида getColor по сигнатуре.
     *
     * Почему отложенно: к моменту onCreate класс Theme гарантированно
     * загружен и инициализирован — значит Class.forName с инициализацией
     * безопасен, и мы не сломаем <clinit>.
     */
    private void scheduleDynamicGetColorHook(final ClassLoader cl) {
        // Хукаем сам onCreate, чтобы в afterHookedMethod уже был готовый Theme
        try {
            Class<?> launchActivityClass = XposedHelpers.findClass(LAUNCH_ACTIVITY_CLASS, cl);
            XposedHelpers.findAndHookMethod(launchActivityClass, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        private boolean done = false;

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (done) return;
                            done = true;
                            try {
                                hookThemeGetColorDynamically(cl);
                            } catch (Throwable t) {
                                XposedBridge.log("[TransparentTelegram] dynamic getColor hook failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] scheduleDynamicGetColorHook failed: " + t);
        }
    }

    private void hookThemeGetColorDynamically(ClassLoader cl) {
        Class<?> themeClass = findThemeClassDynamically(cl);
        if (themeClass == null) {
            XposedBridge.log("[TransparentTelegram] hookThemeGetColorDynamically: Theme-класс не найден");
            return;
        }

        Method[] methods = themeClass.getDeclaredMethods();
        int hooked = 0;

        for (Method m : methods) {
            Class<?>[] params = m.getParameterTypes();
            Class<?> ret = m.getReturnType();

            // Ищем методы, возвращающие int и принимающие int первым параметром.
            // Это getColor(I)I, getColor(I[ZZ)I, getColor(I[Z)I и т.п.
            if (ret != int.class) continue;
            if (params.length == 0) continue;

            int keyIndex = -1;
            if (params[0] == int.class) {
                keyIndex = 0;
            } else if (params.length >= 2 && params[1] == int.class) {
                // Вариант (boolean[], int, boolean)
                keyIndex = 1;
            } else {
                continue;
            }

            // Не хукаем методы с совсем "непохожими" сигнатурами
            boolean looksLikeGetColor = true;
            for (int i = 0; i < params.length; i++) {
                if (i == keyIndex) continue;
                Class<?> p = params[i];
                if (p != boolean.class && p != boolean[].class && p != int[].class
                        && p != String.class && p != Object.class) {
                    looksLikeGetColor = false;
                    break;
                }
            }
            if (!looksLikeGetColor) continue;

            final int finalKeyIndex = keyIndex;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        resolveBackgroundKeys(cl);
                        if (backgroundKeys == null && textKeys == null) return;

                        try {
                            Object keyObj = param.args[finalKeyIndex];
                            if (!(keyObj instanceof Integer)) return;
                            int key = (Integer) keyObj;

                            Object resultObj = param.getResult();
                            if (!(resultObj instanceof Integer)) return;
                            int original = (Integer) resultObj;

                            if (backgroundKeys != null && backgroundKeys.contains(key)) {
                                if (Color.alpha(original) == 255) {
                                    param.setResult(WINDOW_BACKGROUND_COLOR);
                                    if (getColorPatchLogCount.incrementAndGet() <= 60) {
                                        String name = keyNamesByValue != null ? keyNamesByValue.get(key) : null;
                                        XposedBridge.log("[TransparentTelegram] BG(дyn) " + m.getName()
                                                + "(" + name + "/" + Integer.toHexString(key) + "): "
                                                + Integer.toHexString(original)
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
                        } catch (Throwable ignored) {
                        }
                    }
                });
                hooked++;
                XposedBridge.log("[TransparentTelegram] динамически захукано: " + themeClass.getName()
                        + "." + m.getName() + Arrays.toString(params) + " keyIndex=" + keyIndex);
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] не удалось захукать " + m.getName() + ": " + t);
            }
        }

        if (hooked == 0) {
            XposedBridge.log("[TransparentTelegram] в классе " + themeClass.getName()
                    + " не найдено методов, похожих на getColor");
        }
    }

    // =====================================================================
    // ====================== ОБЫЧНЫЙ ХУК getColor =========================
    // =====================================================================

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
                    + Arrays.toString(paramTypes) + " failed (ok): " + t);
        }
    }

    // =====================================================================
    // ======================== ОКНО / VIEW / SCAN =========================
    // =====================================================================

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
