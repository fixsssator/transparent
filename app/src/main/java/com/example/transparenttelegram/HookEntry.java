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
    private static final String THEME_CLASS = "org.telegram.ui.ActionBar.Theme";

    private static final int ALPHA = 0x80;
    private static final int WINDOW_BACKGROUND_COLOR = Color.argb(ALPHA, 0, 0, 0);
    private static final int BLUR_ALPHA = 0x40;
    private static final int TEXT_COLOR_LIGHT = Color.argb(0xFF, 0xEE, 0xEE, 0xEE);

    private static final String[] BACKGROUND_KEY_NAMES = {
            "key_windowBackgroundWhite",
            "key_windowBackgroundGray",
            "key_windowBackgroundUnchecked",
            "key_actionBarDefault",
            "key_actionBarDefaultArchived",
            "key_graySection",
            "key_divider",
            "key_listSelector",
            "key_backgroundChecked",
            "key_inactiveTab",
            "key_inactiveTabActive",
    };

    private static final String[] TEXT_KEY_NAMES = {
            "key_windowBackgroundWhiteBlackText",
            "key_windowBackgroundWhiteGrayText",
            "key_windowBackgroundWhiteGrayText2",
            "key_windowBackgroundWhiteGrayText3",
            "key_windowBackgroundWhiteGrayText4",
            "key_windowBackgroundWhiteGrayText5",
            "key_windowBackgroundWhiteGrayText6",
            "key_windowBackgroundWhiteGrayText7",
            "key_windowBackgroundWhiteGrayText8",
            "key_windowBackgroundWhiteHintText",
            "key_windowBackgroundWhiteValueText",
            "key_windowBackgroundWhiteLinkText",
            "key_windowBackgroundWhiteBlueText",
            "key_windowBackgroundWhiteBlueText2",
            "key_windowBackgroundWhiteBlueText3",
            "key_windowBackgroundWhiteBlueText4",
            "key_windowBackgroundWhiteBlueText5",
            "key_windowBackgroundWhiteBlueText6",
            "key_windowBackgroundWhiteBlueText7",
            "key_windowBackgroundWhiteBlueHeader",
            "key_windowBackgroundWhiteInputField",
            "key_windowBackgroundWhiteInputFieldActivated",
            "key_text_RedRegular",
            "key_text_RedBold",
            "key_actionBarDefaultTitle",
            "key_actionBarDefaultSubtitle",
            "key_actionBarDefaultIcon",
            "key_dialogTextBlack",
            "key_dialogTextGray",
            "key_dialogTextGray2",
            "key_dialogTextGray3",
            "key_dialogTextLink",
            "key_dialogTextBlue",
            "key_dialogTextBlue2",
            "key_dialogTextHint",
            "key_dialogTextRed",
            "key_graySectionText",
    };

    private static volatile Set<Integer> backgroundKeys = null;
    private static volatile Set<Integer> textKeys = null;
    private static volatile Map<Integer, String> keyNamesByValue = null;
    private static final Object KEYS_LOCK = new Object();
    private static volatile boolean keysResolveFailed = false;

    private static final AtomicInteger getColorPatchLogCount = new AtomicInteger(0);

    private static void resolveBackgroundKeys(ClassLoader cl) {
        if (backgroundKeys != null || keysResolveFailed) {
            return;
        }
        synchronized (KEYS_LOCK) {
            if (backgroundKeys != null || keysResolveFailed) {
                return;
            }
            try {
                Class<?> themeClass = XposedHelpers.findClass(THEME_CLASS, cl);
                Set<Integer> bgKeys = new HashSet<>();
                Set<Integer> txtKeys = new HashSet<>();
                Map<Integer, String> names = new HashMap<>();

                for (String keyName : BACKGROUND_KEY_NAMES) {
                    try {
                        int keyValue = XposedHelpers.getStaticIntField(themeClass, keyName);
                        bgKeys.add(keyValue);
                        names.put(keyValue, keyName);
                    } catch (Throwable t) {
                        XposedBridge.log("[TransparentTelegram] bg key " + keyName
                                + " not found (ok): " + t);
                    }
                }
                for (String keyName : TEXT_KEY_NAMES) {
                    try {
                        int keyValue = XposedHelpers.getStaticIntField(themeClass, keyName);
                        txtKeys.add(keyValue);
                        names.put(keyValue, keyName);
                    } catch (Throwable t) {
                        XposedBridge.log("[TransparentTelegram] text key " + keyName
                                + " not found (ok): " + t);
                    }
                }

                keyNamesByValue = names;
                backgroundKeys = bgKeys;
                textKeys = txtKeys;
                XposedBridge.log("[TransparentTelegram] resolved lazily: bg="
                        + bgKeys.size() + ", text=" + txtKeys.size());
            } catch (Throwable t) {
                keysResolveFailed = true;
                XposedBridge.log("[TransparentTelegram] resolveBackgroundKeys failed: " + t);
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final String packageName = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;
        XposedBridge.log("[TransparentTelegram] Loading: " + packageName);

        // ---------- 1. LaunchActivity ----------
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

        // ---------- 2. Theme.getColor(I[ZZ)I ----------
        try {
            Class<?> themeClass = XposedHelpers.findClass(THEME_CLASS, cl);

            XposedHelpers.findAndHookMethod(themeClass, "getColor",
                    int.class, boolean[].class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            resolveBackgroundKeys(cl);
                            if (backgroundKeys == null) return;
                            int key = (Integer) param.args[0];
                            int original = (Integer) param.getResult();

                            if (Color.alpha(original) == 255) {
                                XposedBridge.log("[TT-DIAG] getColor key=" + key
                                        + " -> #" + Integer.toHexString(original));
                            }

                            if (backgroundKeys.contains(key)) {
                                if (Color.alpha(original) == 255) {
                                    param.setResult(WINDOW_BACKGROUND_COLOR);
                                    if (getColorPatchLogCount.incrementAndGet() <= 40) {
                                        XposedBridge.log("[TransparentTelegram] BG getColor("
                                                + keyNamesByValue.get(key) + "): "
                                                + Integer.toHexString(original) + " -> "
                                                + Integer.toHexString(WINDOW_BACKGROUND_COLOR));
                                    }
                                }
                                return;
                            }

                            if (textKeys != null && textKeys.contains(key)) {
                                if (Color.alpha(original) == 255 && isDarkColor(original)) {
                                    param.setResult(TEXT_COLOR_LIGHT);
                                    if (getColorPatchLogCount.incrementAndGet() <= 80) {
                                        XposedBridge.log("[TransparentTelegram] TXT getColor("
                                                + keyNamesByValue.get(key) + "): "
                                                + Integer.toHexString(original) + " -> "
                                                + Integer.toHexString(TEXT_COLOR_LIGHT));
                                    }
                                }
                            }
                        }
                    });
            XposedBridge.log("[TransparentTelegram] Theme.getColor hook installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Theme.getColor hook failed for " + packageName + ": " + t);
        }

        // ---------- 2b. Theme.getColor -- остальные перегрузки ----------
        try {
            Class<?> themeClass = XposedHelpers.findClass(THEME_CLASS, cl);

            try {
                XposedHelpers.findAndHookMethod(themeClass, "getColor", int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                resolveBackgroundKeys(cl);
                                if (backgroundKeys == null) return;
                                int key = (Integer) param.args[0];
                                int original = (Integer) param.getResult();

                                if (Color.alpha(original) == 255) {
                                    XposedBridge.log("[TT-DIAG] getColor(int) key=" + key
                                            + " -> #" + Integer.toHexString(original));
                                }

                                if (backgroundKeys.contains(key) && Color.alpha(original) == 255) {
                                    param.setResult(WINDOW_BACKGROUND_COLOR);
                                } else if (textKeys != null && textKeys.contains(key)
                                        && Color.alpha(original) == 255 && isDarkColor(original)) {
                                    param.setResult(TEXT_COLOR_LIGHT);
                                }
                            }
                        });
                XposedBridge.log("[TransparentTelegram] getColor(int) hooked");
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] getColor(int) hook failed: " + t);
            }

            try {
                XposedHelpers.findAndHookMethod(themeClass, "getColor", int.class, boolean[].class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                resolveBackgroundKeys(cl);
                                if (backgroundKeys == null) return;
                                int key = (Integer) param.args[0];
                                int original = (Integer) param.getResult();

                                if (Color.alpha(original) == 255) {
                                    XposedBridge.log("[TT-DIAG] getColor(int,[]) key=" + key
                                            + " -> #" + Integer.toHexString(original));
                                }

                                if (backgroundKeys.contains(key) && Color.alpha(original) == 255) {
                                    param.setResult(WINDOW_BACKGROUND_COLOR);
                                } else if (textKeys != null && textKeys.contains(key)
                                        && Color.alpha(original) == 255 && isDarkColor(original)) {
                                    param.setResult(TEXT_COLOR_LIGHT);
                                }
                            }
                        });
                XposedBridge.log("[TransparentTelegram] getColor(int, boolean[]) hooked");
            } catch (Throwable t) {
                XposedBridge.log("[TransparentTelegram] getColor(int, boolean[]) hook failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] getColor overloads hook failed: " + t);
        }

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
                                        param.args[0] = new ColorDrawable(
                                                (c & 0x00FFFFFF) | (ALPHA << 24));
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

        // ---------- 2d. Canvas.drawColor / drawRect -- добиваем тёмные плашки ----------
        try {
            // drawColor(int)
            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int c = (Integer) param.args[0];
                                if (Color.alpha(c) == 255 && isDarkColor(c)) {
                                    param.args[0] = WINDOW_BACKGROUND_COLOR;
                                }
                            } catch (Throwable ignored) {}
                        }
                    });

            // drawRect(float, float, float, float, Paint)
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
                            } catch (Throwable ignored) {}
                        }
                    });

            // drawRect(RectF, Paint)
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
                            } catch (Throwable ignored) {}
                        }
                    });

            XposedBridge.log("[TransparentTelegram] Canvas hooks installed for " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("[TransparentTelegram] Canvas hooks failed for " + packageName + ": " + t);
        }

        // ---------- 3. ActionBar.setBackgroundColor ----------
        try {
            Class<?> actionBarClass = XposedHelpers.findClass(
                    "org.telegram.ui.ActionBar.ActionBar", cl);
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
            XposedBridge.log("[TransparentTelegram] ActionBar hook failed for " + packageName + ": " + t);
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
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundSourceColor hook failed for " + packageName + ": " + t);
        }

        // ---------- 5. BlurredBackgroundColorProviderThemed ----------
        try {
            Class<?> providerClass = XposedHelpers.findClass(
                    "org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed", cl);
            for (String methodName : new String[]{
                    "getBackgroundColor", "getStrokeColorTop", "getStrokeColorBottom"}) {
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
            XposedBridge.log("[TransparentTelegram] BlurredBackgroundColorProviderThemed hook failed for " + packageName + ": " + t);
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
            XposedBridge.log("[TransparentTelegram] DialogsActivityTopBubblesFadeView hook failed for " + packageName + ": " + t);
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
