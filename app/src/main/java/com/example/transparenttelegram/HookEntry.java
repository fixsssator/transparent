package com.example.transparenttelegram;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
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
 * Воспроизводит эффект "прозрачной темы" из патченной сборки Telegram
 * (Theme.TMessages / Theme.TMessages.Dark / Theme.TMessages.Start в
 * res/values/styles.xml, где android:windowBackground сделан
 * полупрозрачным и добавлен android:windowShowWallpaper=true) —
 * но не патчингом ресурсов конкретного APK, а хуком в рантайме,
 * применимым к любому Telegram-based приложению без пересборки.
 *
 * ВАЖНО: протестировано только статически (нет Android-устройства
 * в среде, где это писалось). Перед использованием проверьте на
 * реальном телефоне и подстройте ALPHA / TARGET_PACKAGES под себя.
 */
public class HookEntry implements IXposedHookLoadPackage {

    // Пакеты, к которым применяется хук. LaunchActivity должен существовать
    // именно по этому имени класса — актуально для официального Telegram
    // и большинства форков (все они держат класс org.telegram.ui.LaunchActivity,
    // т.к. форкают исходники DrKLO/Telegram и обычно не переименовывают базовые
    // классы). AyuGram и Nekogram проверены напрямую по их манифестам/исходникам.
    //
    // Не проверял на реальном устройстве (нет Android-рантайма в среде, где это
    // писалось) — если для какого-то клиента хук не срабатывает, смотрите лог
    // через LSPosed Manager -> Logs (там будет строка "[TransparentTelegram] ...
    // failed for <package>") и, если понадобится, замените имя класса-активности
    // под этот конкретный форк.
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",        // Telegram (официальный)
            "org.telegram.messenger.beta",   // Telegram Beta
            "org.telegram.messenger.web",    // Telegram (некоторые сборки/архивы)
            "com.radolyn.ayugram",           // AyuGram — проверено по манифесту
            "tw.nekomimi.nekogram",          // Nekogram — проверено (F-Droid/GitLab RFP)
            "nekox.messenger",               // NekoX — проверено (F-Droid)
            "com.radolyn.ayugram.web",       // AyuGram Web/альтернативная сборка (если есть)

            // Ниже — форки, для которых applicationId не проверял лично.
            // Раскомментируйте и протестируйте перед использованием:
            // "org.telegram.plus",           // Plus Messenger — уточните реальный id
            // "com.nextalone.nagram",        // Nagram — уточните реальный id
            // "org.telegram.messenger.foss", // Telegram-FOSS — уточните реальный id
            // "com.gogolev.forkgram",        // Forkgram — уточните реальный id
    ));

    private static final String LAUNCH_ACTIVITY_CLASS = "org.telegram.ui.LaunchActivity";

    // 0x80000000 — ровно то же значение alpha (50%), что было в найденном патче.
    // Первый байт — альфа (0x80 = 128/255 ≈ 50%), дальше — RGB.
    private static final int WINDOW_BACKGROUND_COLOR = 0x80000000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        try {
            Class<?> launchActivityClass = XposedHelpers.findClass(
                    LAUNCH_ACTIVITY_CLASS, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    launchActivityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyTransparency((Activity) param.thisObject, lpparam.packageName);
                        }
                    });

            XposedBridge.log("[TransparentTelegram] hook installed for " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] failed for " + lpparam.packageName + ": " + t);
        }
    }

    private void applyTransparency(Activity activity, String packageName) {
        try {
            Window window = activity.getWindow();

            // Эквивалент android:windowShowWallpaper="true" в теме —
            // окно реально показывает системные обои позади себя.
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

            // Эквивалент android:windowBackground="#80000000" в теме.
            window.setBackgroundDrawable(new ColorDrawable(WINDOW_BACKGROUND_COLOR));

            XposedBridge.log("[TransparentTelegram] applied to " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] applyTransparency failed: " + t);
        }
    }
}
