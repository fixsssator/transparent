package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
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

/**
 * Воспроизводит эффект "прозрачной темы" (окно показывает системные обои
 * позади интерфейса) для Telegram-based клиентов, чьи собственные
 * res/values/styles.xml НЕ содержат android:windowShowWallpaper /
 * полупрозрачный android:colorBackground (как в оригинальной патченной
 * сборке Telegram 12.9.0).
 *
 * КЛЮЧЕВОЙ ФИКС по сравнению с первой версией: одного addFlags(FLAG_SHOW_
 * WALLPAPER) + setBackgroundDrawable(...) недостаточно, если сам Window
 * остаётся в PixelFormat.OPAQUE (это значение обычно фиксируется из
 * скомпилированной темы при создании PhoneWindow, ДО onCreate()). Поэтому
 * явно переключаем формат окна в PixelFormat.TRANSLUCENT программно —
 * это официальный публичный API (Window#setFormat), а не хак, и именно
 * его не хватало в предыдущей версии.
 *
 * Мы НЕ используем XC_InitPackageResources/XResources для подмены атрибута
 * темы: этот механизм перехватывает Resources.getColor()/getDrawable() для
 * ИМЕНОВАННЫХ ресурсов, а windowShowWallpaper/colorBackground — это атрибуты
 * ВНУТРИ <style>, которые Android резолвит через obtainStyledAttributes()
 * в нативном коде при создании PhoneWindow — до того, как какой-либо
 * Java-метод Resources, перехватываемый XResources, вообще вызывается.
 * Долбить это через рефлексию в TypedArray.mData возможно, но раскладка
 * этого массива зависит от версии Android/прошивки — слишком хрупко и
 * рискованно ронять чужой процесс ради этого. Runtime setFormat() —
 * тот же результат официальным путём.
 *
 * ВАЖНО: не протестировано на реальном устройстве (нет Android-рантайма
 * в среде, где это писалось). Проверяйте через LSPosed Manager -> Logs.
 */
public class HookEntry implements IXposedHookLoadPackage {

    // Пакеты, к которым применяется хук. Требуется класс
    // org.telegram.ui.LaunchActivity — сохраняется в большинстве форков,
    // т.к. они форкают исходники DrKLO/Telegram и не переименовывают
    // базовые классы. AyuGram и Nekogram проверены напрямую по манифестам/
    // исходникам, остальные — нет (см. закомментированный блок ниже).
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",        // Telegram (официальный)
            "org.telegram.messenger.beta",   // Telegram Beta
            "org.telegram.messenger.web",    // Telegram (некоторые сборки/архивы)
            "com.radolyn.ayugram",           // AyuGram — проверено по манифесту
            "com.radolyn.ayugram.web",       // AyuGram Web/альтернативная сборка (если есть)
            "tw.nekomimi.nekogram",          // Nekogram — проверено (F-Droid/GitLab RFP)
            "nekox.messenger"                // NekoX — проверено (F-Droid)

            // Ниже — форки, для которых applicationId не проверял лично.
            // Раскомментируйте и протестируйте перед использованием (не забудьте
            // добавить запятую после предыдущей строки, если раскомментируете):
            // ,"org.telegram.plus"           // Plus Messenger — уточните реальный id
            // ,"com.nextalone.nagram"        // Nagram — уточните реальный id
            // ,"org.telegram.messenger.foss" // Telegram-FOSS — уточните реальный id
            // ,"com.gogolev.forkgram"        // Forkgram — уточните реальный id
    ));

    private static final String LAUNCH_ACTIVITY_CLASS = "org.telegram.ui.LaunchActivity";

    // Alpha = 0x80 (50%) — то же значение, что в найденном патче styles.xml
    // (android:windowBackground="#80000000", android:colorBackground="#80008b8b").
    // Мы используем тот же чёрный тон для обоих, чтобы не гадать про teal.
    private static final int ALPHA = 0x80;
    private static final int WINDOW_BACKGROUND_COLOR = Color.argb(ALPHA, 0, 0, 0);

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

            // before onCreate: выставляем формат/флаги/фон ДО того, как
            // приложение само вызовет setContentView() и начнёт рисовать
            // свои (непрозрачные) View поверх.
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

            // onResume: многие Telegram-форки перерисовывают/переприменяют
            // тему при каждом возврате на экран (смена темы, ресайз, свап
            // фрагментов) — переприменяем прозрачность, чтобы пережить это.
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

            XposedBridge.log("[TransparentTelegram] Hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Failed for " + packageName + ": " + t);
        }
    }

    /**
     * Вызывается ДО super.onCreate()/setContentView() приложения.
     * Главное здесь — setFormat(PixelFormat.TRANSLUCENT): без него
     * FLAG_SHOW_WALLPAPER и полупрозрачный фон ничего не дают, потому что
     * окно остаётся в непрозрачном пиксельном формате.
     */
    private void prepareWindow(Activity activity) {
        Window window = activity.getWindow();

        // Ключевой фикс: программно переключаем формат окна в TRANSLUCENT.
        // Это ровно то, что при обычной работе Android делает автоматически,
        // читая android:windowIsTranslucent/windowShowWallpaper из
        // скомпилированной темы -- но раз в целевом приложении этого
        // атрибута нет, выставляем его сами через официальный публичный API.
        window.setFormat(PixelFormat.TRANSLUCENT);

        // Эквивалент android:windowShowWallpaper="true" -- окно реально
        // показывает системные обои позади себя.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

        // Убираем стандартное затемнение под диалогами/меню, чтобы оно не
        // перекрывало эффект отдельным серым слоем.
        window.setDimAmount(0f);

        // Эквивалент android:windowBackground="#80000000".
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));
    }

    /**
     * Вызывается ПОСЛЕ onCreate()/onResume() -- переприменяет то же самое
     * (на случай, если приложение само перезаписало фон/формат внутри
     * своего onCreate, как это делает AyuGram: он явно вызывает
     * Window.setBackgroundDrawableResource(R.drawable.transparent)),
     * и дополнительно расчищает фон верхних 1-2 уровней View-иерархии --
     * многие Telegram-форки красят непрозрачный фон не на самом Window,
     * а на корневом FrameLayout/DecorView.
     */
    private void applyTransparency(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        Window window = activity.getWindow();
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setDimAmount(0f);
        window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));

        View root = window.getDecorView();
        if (root != null) {
            makeViewTransparent(root);
        }

        XposedBridge.log("[TransparentTelegram] Transparency applied");
    }

    /**
     * Расчищает непрозрачный фон у DecorView и (если есть ровно один
     * дочерний ViewGroup -- типичная структура "один корневой контейнер")
     * у этого дочернего контейнера тоже. Специально НЕ лезем глубже --
     * дальше начинаются реальные экраны чатов/списков, куда лучше не
     * соваться вслепую без теста на устройстве.
     */
    private void makeViewTransparent(View view) {
        if (view == null) {
            return;
        }

        if (view.getBackground() != null) {
            view.setBackgroundColor(Color.argb(ALPHA, 0, 0, 0));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getChildCount() == 1) {
                View child = group.getChildAt(0);
                if (child instanceof ViewGroup) {
                    child.setBackgroundColor(Color.argb(ALPHA, 0, 0, 0));
                }
            }
        }
    }
}
