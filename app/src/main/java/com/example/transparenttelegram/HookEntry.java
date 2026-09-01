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
import java.util.concurrent.atomic.AtomicInteger;

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

    // Счётчик срабатываний диагностического хука Paint.setColor -- ограничиваем,
    // чтобы не залить лог (setColor может вызываться тысячи раз за кадр).
    private static final AtomicInteger paintDiagCount = new AtomicInteger(0);

    private static final int ALPHA = 0x80;
    private static final int WINDOW_BACKGROUND_COLOR = Color.argb(ALPHA, 0, 0, 0);
    // Блюр-плашки (BlurredBackgroundWithFadeDrawable и т.п.) под статус-баром/
    // шапкой блюрят реальную обоину + свой fade-тон -- смотрится "пересвеченно",
    // если оставить как есть. Приглушаем сильнее, чем обычную стену.
    private static final int BLUR_ALPHA = 0x40;
    private static final int MAX_DEPTH = 12;      // насколько глубоко логируем дерево View
    private static final int MAX_CHILDREN = 12;   // максимум детей на уровень (чтобы не залить лог)

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

            // Отдельный, гораздо более точный хук: ActionBar.setBackgroundColor()
            // ПЕРЕОПРЕДЕЛЁН и НЕ создаёт обычный background-Drawable (см. смали:
            // просто сохраняет цвет в поле actionBarColor и красит Paint'ом в
            // dispatchDraw()) -- поэтому обход дерева (stripOpaqueBackgrounds)
            // его в принципе не видит, getBackground() всегда null. Ловим сам
            // сеттер -- сработает для ЛЮБОГО экрана (Настройки, список чатов,
            // чат), независимо от того, когда и сколько раз он вызывается.
            try {
                Class<?> actionBarClass = XposedHelpers.findClass(
                        "org.telegram.ui.ActionBar.ActionBar", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(actionBarClass, "setBackgroundColor", int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                int original = (Integer) param.args[0];
                                if (Color.alpha(original) == 255) {
                                    int patched = (original & 0x00FFFFFF) | (ALPHA << 24);
                                    param.args[0] = patched;
                                    XposedBridge.log("[TransparentTelegram] ActionBar.setBackgroundColor: "
                                            + Integer.toHexString(original) + " -> " + Integer.toHexString(patched)
                                            + " (instance " + param.thisObject.getClass().getName() + ")");
                                } else {
                                    XposedBridge.log("[TransparentTelegram] ActionBar.setBackgroundColor пропущен (уже не opaque): "
                                            + Integer.toHexString(original));
                                }
                            }
                        });
                XposedBridge.log("[TransparentTelegram] ActionBar.setBackgroundColor hook installed for " + packageName);
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] ActionBar hook failed for " + packageName + ": " + t);
            }

            // НОВЫЙ, современный механизм ("glass"-редизайн): ActionBar в
            // этой версии красит себя НЕ через setBackgroundColor(), а через
            // setupGlass(...)/setDrawBlurBackground(...), которые берут цвет
            // из отдельного объекта BlurredBackgroundColorProvider (интерфейс
            // с методом getBackgroundColor()) -- он опрашивается каждый раз
            // при отрисовке блюра. Хук на setBackgroundColor его вообще не
            // видит -- нужно ловить именно этот провайдер. Хукаем конкретную
            // реализацию (интерфейсы напрямую не хукаются в classic Xposed API).
            try {
                Class<?> providerClass = XposedHelpers.findClass(
                        "org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed",
                        lpparam.classLoader);
                XposedHelpers.findAndHookMethod(providerClass, "getBackgroundColor",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                int original = (Integer) param.getResult();
                                if (Color.alpha(original) == 255) {
                                    int patched = (original & 0x00FFFFFF) | (ALPHA << 24);
                                    param.setResult(patched);
                                    XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed.getBackgroundColor: "
                                            + Integer.toHexString(original) + " -> " + Integer.toHexString(patched));
                                }
                            }
                        });
                XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook installed for " + packageName);
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook failed for " + packageName + ": " + t);
            }

            // ============================================================
            // ВРЕМЕННЫЙ ДИАГНОСТИЧЕСКИЙ ХУК: три попытки найти правильный
            // высокоуровневый метод (setBackgroundColor, BlurredBackground-
            // ColorProviderThemed.getBackgroundColor) НЕ подтвердились --
            // хуки ставятся, но реально ни разу не вызываются для видимого
            // баннера. Вместо дальнейшего угадывания перехватываем сам
            // ПРИМИТИВ отрисовки -- Paint.setColor(int) -- глобально в
            // процессе, с логом стека вызова. Кто бы ни красил этот пиксель
            // (glass, blur, RenderNode, обычный View), он обязан в итоге
            // вызвать это. Фильтруем по "светлый и непрозрачный" цвет, чтобы
            // не залить лог, и ограничиваем число сработок.
            // ============================================================
            try {
                XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setColor", int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (paintDiagCount.get() >= 40) {
                                    return;
                                }
                                int color = (Integer) param.args[0];
                                int a = Color.alpha(color);
                                int r = Color.red(color);
                                int g = Color.green(color);
                                int b = Color.blue(color);
                                // светлый (близко к белому/серому) и непрозрачный
                                boolean light = a > 200 && r > 180 && g > 180 && b > 180;
                                if (!light) {
                                    return;
                                }
                                paintDiagCount.incrementAndGet();
                                StringBuilder sb = new StringBuilder();
                                sb.append("[TransparentTelegram][PAINTDIAG] setColor(#")
                                        .append(Integer.toHexString(color)).append(") stack:");
                                StackTraceElement[] st = new Throwable().getStackTrace();
                                for (int i = 0; i < Math.min(8, st.length); i++) {
                                    sb.append("\n    at ").append(st[i].toString());
                                }
                                XposedBridge.log(sb.toString());
                            }
                        });
                XposedBridge.log("[TransparentTelegram] Paint.setColor diag hook installed for " + packageName);
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] Paint.setColor diag hook failed for " + packageName + ": " + t);
            }

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
        clearSystemBars(window);
    }

    /**
     * Статус-бар и навигационный бар Android рисует ОТДЕЛЬНО от DecorView
     * (через WindowManager.LayoutParams.statusBarColor/navigationBarColor),
     * это не View и не Drawable -- обход дерева (stripOpaqueBackgrounds)
     * физически не может их достать. По скриншоту видно сплошную белую
     * полосу ровно в области статус-бара -- это именно оно.
     */
    private void clearSystemBars(Window window) {
        try {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] clearSystemBars failed: " + t);
        }
    }

    // Флаг на Activity: слушатель layout вешаем один раз за жизнь окна,
    // а не при каждом applyTransparency() (иначе будут копиться дубликаты).
    private static final java.util.WeakHashMap<View, Boolean> LISTENER_ATTACHED = new java.util.WeakHashMap<>();

    // Троттлинг: layout в Android может дёргаться десятки раз в секунду
    // (анимации, клавиатура, скролл) -- гонять полный обход дерева на
    // каждый чих дорого и заспамит лог. Не чаще одного раза в 400 мс.
    private static volatile long lastScanTime = 0L;
    private static final long SCAN_THROTTLE_MS = 400L;

    // Диагностический дамп теперь ПОВТОРЯЕМЫЙ (не одноразовый), привязан
    // к тому же слушателю layout, свой (более редкий) троттлинг -- чтобы
    // при заходе на новый экран (Настройки и т.п.) в логе рано или поздно
    // появился дамп именно оттуда, а не только с экрана при запуске.
    private static volatile long lastDiagTime = 0L;
    private static final long DIAG_THROTTLE_MS = 5000L;

    private void applyTransparency(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        final Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));
        clearSystemBars(window);

        final View root = window.getDecorView();
        if (root == null) {
            return;
        }

        // .post() -- выполнится ПОСЛЕ того, как View пройдут layout,
        // иначе getWidth()/getHeight() ещё вернут 0 и фильтр по
        // размеру в stripOpaqueBackgrounds отсеет всё подряд.
        root.post(new Runnable() {
            @Override
            public void run() {
                scanNow(root);
            }
        });

        // ГЛАВНЫЙ ФИКС: Telegram -- однооконное приложение, переходы между
        // экранами (Настройки, поиск, любой внутренний фрагмент) НЕ вызывают
        // повторный onCreate/onResume самой Activity -- наш обход дерева
        // просто никогда не запускался бы для этих экранов. Вешаем
        // постоянный слушатель на изменения layout всего дерева -- он
        // сработает при появлении/пересоздании ЛЮБОГО View, включая новые
        // экраны, попапы, вкладки поиска и т.п. С троттлингом, чтобы не
        // гонять полный обход на каждый мелкий layout (клавиатура, анимации).
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
                                if (now - lastDiagTime >= DIAG_THROTTLE_MS) {
                                    lastDiagTime = now;
                                    try {
                                        dumpDiagnostics(activity);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[TransparentTelegram][DIAG] dump failed: " + t);
                                    }
                                }
                            }
                        });
                XposedBridge.log("[TransparentTelegram] OnGlobalLayoutListener attached");
            }
        }
    }

    private void scanNow(View root) {
        try {
            stripOpaqueBackgrounds(root, root.getWidth(), root.getHeight(), 0);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] stripOpaqueBackgrounds failed: " + t);
        }

        XposedBridge.log("[TransparentTelegram] Transparency applied");
    }

    /**
     * ГЛУБОКИЙ обход дерева View (в отличие от прежней версии, которая
     * трогала только 1-2 верхних уровня). Найдено по реальным логам с
     * устройства: настоящая "стена" -- org.telegram.ui.MainTabsActivity$2
     * с ColorDrawable(#FF212332, alpha=255), на 8 уровней глубже
     * DecorView, размером ровно во весь экран.
     *
     * Критерий отбора: непрозрачный фон (alpha==255 либо
     * Drawable.getOpacity()==OPAQUE) И размер вида ~= размеру экрана
     * (>=85% ширины и высоты DecorView). Это отсекает кнопки/пузыри
     * сообщений/карточки -- у них обычно осмысленный сплошной цвет
     * меньшего размера, трогать их не нужно (испортит читаемость чата).
     *
     * Через Drawable.setAlpha() (а не создание нового ColorDrawable через
     * setBackgroundColor) -- это работает для ЛЮБого типа Drawable, включая
     * NinePatchDrawable/BitmapDrawable (например обои чата), не только
     * для сплошных цветов. mutate() обязателен, чтобы не задеть другие
     * View, которые могут шарить тот же закэшированный Drawable.
     */
    private void stripOpaqueBackgrounds(View view, int rootWidth, int rootHeight, int depth) {
        if (view == null || depth > 40) { // защита от аномально глубоких/циклических деревьев
            return;
        }

        Drawable bg = view.getBackground();

        if (bg != null && isBlurDrawable(bg)) {
            // Отдельное правило ДО общей проверки на "во весь экран":
            // блюр-плашки (BlurredBackgroundWithFadeDrawable и т.п.) обычно
            // маленькие (полоска под статус-баром/шапкой) и НЕ считаются
            // "непрозрачными" (opacity != OPAQUE) -- общий фильтр их не
            // ловит. Раньше они блюрили обычный фон чата, теперь блюрят
            // реальную обоину + свой fade-тон поверх -- визуально "пересвет".
            //
            // ВАЖНО: просто bg.setAlpha(...) на самом composite-дровейбле
            // не помогло на практике -- судя по названию "WithFade" у него
            // внутри отдельный слой градиента-затухания со своей фиксированной
            // альфой/цветом, который не подчиняется внешнему Drawable.setAlpha().
            // Поэтому ПОЛНОСТЬЮ ЗАМЕНЯЕМ фон на простой ColorDrawable -- тот
            // же приём, что уже надёжно работает для обычных "стен" ниже.
            // Цена: пропадает сам эффект блюра позади шапки/поиска, остаётся
            // ровный полупрозрачный тон -- визуально это лучше, чем "пересвет".
            try {
                view.setBackgroundColor(WINDOW_BACKGROUND_COLOR);
                XposedBridge.log("[TransparentTelegram] Блюр заменён на плоский тон: "
                        + view.getClass().getName() + " (было " + bg.getClass().getName()
                        + ", " + view.getWidth() + "x" + view.getHeight() + ")");
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] blur patch failed on "
                        + view.getClass().getName() + ": " + t);
            }
        } else if (bg != null && isEffectivelyOpaque(bg)) {
            boolean fullWidth = view.getWidth() >= rootWidth * 0.85f;
            boolean fullHeight = view.getHeight() >= rootHeight * 0.85f;
            if (fullWidth && fullHeight) {
                try {
                    if (bg instanceof ColorDrawable) {
                        // ЗАМЕНЯЕМ цвет целиком на единый фиксированный тон
                        // (как в оригинальном патче: android:windowBackground
                        // жёстко заменили на #80000000, а не просто снизили
                        // альфу исходному белому/светлому цвету). Если вместо
                        // этого просто снижать альфу СВЕТЛОМУ фону (список
                        // чатов в светлой теме и т.п.) -- получается "пересвеченное"
                        // мутно-белое стекло поверх обоины, а не аккуратный
                        // тёмный тон, как в чате.
                        view.setBackgroundColor(WINDOW_BACKGROUND_COLOR);
                    } else {
                        // Для НЕ-сплошного фона (обои чата, паттерны, битмапы) --
                        // заменить целиком нельзя, там важна сама картинка, поэтому
                        // просто снижаем альфу как раньше.
                        bg.mutate().setAlpha(ALPHA);
                    }
                    XposedBridge.log("[TransparentTelegram] Стена найдена и пробита: "
                            + view.getClass().getName() + " (" + view.getWidth() + "x" + view.getHeight() + ")");
                } catch (Throwable t) {
                    XposedBridge.log("[TransparentTelegram] patch failed on "
                            + view.getClass().getName() + ": " + t);
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
     * Ловим блюр-дровейблы по имени класса, а не по opacity/размеру --
     * они сами по себе полупрозрачные по задумке (opacity != OPAQUE),
     * поэтому общий фильтр их пропускает, но визуально именно они дают
     * "пересвеченный блюр" поверх живой обоины.
     */
    private boolean isBlurDrawable(Drawable d) {
        String name = d.getClass().getName().toLowerCase();
        return name.contains("blur");
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

    private final int[] locBuf = new int[2];

    private void dumpViewTree(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        int screenX = -1;
        int screenY = -1;
        try {
            view.getLocationOnScreen(locBuf);
            screenX = locBuf[0];
            screenY = locBuf[1];
        } catch (Throwable ignored) {
        }

        String line = indent + "[" + depth + "] " + view.getClass().getName()
                + " bg=" + describeDrawable(view.getBackground())
                + " alpha=" + view.getAlpha()
                + " vis=" + visibilityToString(view.getVisibility())
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " pos=(" + screenX + "," + screenY + ")-(" + (screenX + view.getWidth())
                + "," + (screenY + view.getHeight()) + ")";
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
