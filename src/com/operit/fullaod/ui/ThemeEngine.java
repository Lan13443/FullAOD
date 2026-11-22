package com.operit.fullaod.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;

public final class ThemeEngine {

    public static final String UI_PREFS = "fullaod_ui";

    public static final String K_THEME_MODE = "theme_mode"; // 0=Follow System, 1=Light, 2=Dark
    public static final String K_MONET = "monet_enabled";
    public static final String K_COLOR_INDEX = "theme_color_idx"; // 0=系统壁纸/紫色, 1=紫色, 2=蓝色, 3=青色, 4=绿色, 5=琥珀, 6=珊瑚红, 7=樱花粉
    public static final String K_COLOR_STYLE = "color_style"; // 0=TonalSpot, 1=Vibrant, 2=Expressive, 3=Neutral, 4=Monochrome
    public static final String K_COLOR_SPEC = "color_spec"; // 0=SPEC_2025, 1=SPEC_2021

    public static final String K_BLUR = "blur_enabled";
    public static final String K_FLOATING_BAR = "floating_bar";
    public static final String K_LIQUID_GLASS = "liquid_glass";
    public static final String K_BOTTOM_BADGE = "bottom_badge";

    public static final String K_PREDICTIVE_BACK = "predictive_back";
    public static final String K_EDGE_SWIPE_BACK = "edge_swipe_back";
    public static final String K_PAGE_GESTURE = "page_switch_gesture"; // 0=跨轴拦截, 1=自由滑动, 2=关闭
    public static final String K_HIDE_ICON = "hide_launcher_icon";

    public static final String[] COLOR_NAMES = {
            "动态壁纸", "紫色", "湛蓝", "青碧", "翠绿", "琥珀", "珊瑚红", "樱花粉"
    };
    public static final int[] COLOR_SEEDS = {
            0xFFD0BCFF, // 动态默认回落紫
            0xFFD0BCFF, // 紫色
            0xFFA8C7FA, // 湛蓝
            0xFF7DDBFF, // 青碧
            0xFF81D89E, // 翠绿
            0xFFFFCC80, // 琥珀
            0xFFFFB4AB, // 珊瑚红
            0xFFFFB1C8  // 樱花粉
    };

    public static final String[] STYLE_NAMES = {
            "TonalSpot", "Vibrant", "Expressive", "Neutral", "Monochrome"
    };

    public static final String[] SPEC_NAMES = {
            "SPEC_2025", "SPEC_2021"
    };

    public static final String[] PAGE_GESTURE_NAMES = {
            "跨轴拦截", "自由滑动", "关闭"
    };

    public final boolean isDark;
    public final int bgColor;
    public final int cardColor;
    public final int cardElevatedColor;
    public final int cardStrokeColor;

    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;

    public final int heroCardBg;
    public final int heroWatermarkColor;
    public final int heroTextPrimary;
    public final int heroTextSecondary;

    public final int textPrimary;
    public final int textSecondary;
    public final int textTertiary;
    public final int sectionTitleColor;
    public final int dividerColor;

    public final int switchTrackOn;
    public final int switchThumbOn;
    public final int switchTrackOff;
    public final int switchThumbOff;
    public final int switchStrokeOff;

    public final int glassSurfaceColor;
    public final int glassPillActiveColor;
    public final int glassBorderColor;

    public ThemeEngine(Context ctx) {
        SharedPreferences sp = sp(ctx);
        int mode = sp.getInt(K_THEME_MODE, 0);
        if (mode == 1) {
            isDark = false;
        } else if (mode == 2) {
            isDark = true;
        } else {
            int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
        }

        boolean monet = sp.getBoolean(K_MONET, true);
        int colorIdx = sp.getInt(K_COLOR_INDEX, 0);
        int styleIdx = sp.getInt(K_COLOR_STYLE, 0);
        int specIdx = sp.getInt(K_COLOR_SPEC, 0);

        int seedAccent = resolveSeedColor(ctx, monet, colorIdx);
        float[] hsv = new float[3];
        Color.colorToHSV(seedAccent, hsv);
        float hue = hsv[0];
        float baseSat = hsv[1];

        // Modulate saturation by Color Style (TonalSpot, Vibrant, Expressive, Neutral, Monochrome)
        float satMult = 1.0f;
        if (styleIdx == 1) { // Vibrant
            satMult = 1.35f;
        } else if (styleIdx == 2) { // Expressive
            hue = (hue + 18f) % 360f;
            satMult = 1.25f;
        } else if (styleIdx == 3) { // Neutral
            satMult = 0.35f;
        } else if (styleIdx == 4) { // Monochrome
            satMult = 0.0f;
        }

        boolean is2025 = (specIdx == 0);

        if (isDark) {
            float pSat = clamp(baseSat * 0.48f * satMult, 0f, 0.85f);
            float cSat = clamp(baseSat * 0.42f * satMult, 0f, 0.75f);
            float nSat = clamp(baseSat * (is2025 ? 0.16f : 0.10f) * satMult, 0f, 0.25f);

            primary = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_200, hsvColor(hue, pSat, 0.96f))
                    : hsvColor(hue, pSat, 0.96f);

            onPrimary = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_800, hsvColor(hue, clamp(pSat * 1.3f, 0f, 0.9f), 0.28f))
                    : hsvColor(hue, clamp(pSat * 1.3f, 0f, 0.9f), 0.28f);

            primaryContainer = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_700, hsvColor(hue, cSat, 0.36f))
                    : hsvColor(hue, cSat, 0.36f);

            onPrimaryContainer = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_100, hsvColor(hue, pSat * 0.6f, 0.97f))
                    : hsvColor(hue, pSat * 0.6f, 0.97f);

            bgColor = is2025
                    ? hsvColor(hue, nSat * 0.85f, 0.065f)
                    : hsvColor(hue, nSat * 0.6f, 0.08f);

            cardColor = is2025
                    ? hsvColor(hue, nSat, 0.115f)
                    : hsvColor(hue, nSat * 0.8f, 0.13f);

            cardElevatedColor = hsvColor(hue, nSat * 1.1f, 0.16f);
            cardStrokeColor = withAlpha(primary, 24);

            // Hero card on Home screen (matches Image 2's tonal slate/monet status card)
            heroCardBg = hsvColor(hue, clamp(cSat * 0.72f, 0f, 0.50f), 0.27f);
            heroWatermarkColor = withAlpha(primary, 150);
            heroTextPrimary = 0xFFF5F3F9;
            heroTextSecondary = withAlpha(0xFFEAE6F2, 200);

            textPrimary = 0xFFF3EFF7;
            textSecondary = 0xFFA29DAB;
            textTertiary = 0xFF736E7C;
            sectionTitleColor = primary;
            dividerColor = 0x1AFFFFFF;

            switchTrackOn = primary;
            switchThumbOn = hsvColor(hue, clamp(pSat * 1.2f, 0f, 0.8f), 0.26f);
            switchTrackOff = hsvColor(hue, nSat, 0.22f);
            switchThumbOff = 0xFF96919E;
            switchStrokeOff = 0x33FFFFFF;

            glassSurfaceColor = withAlpha(hsvColor(hue, nSat, 0.12f), 145);
            glassPillActiveColor = withAlpha(primary, 48);
            glassBorderColor = withAlpha(0xFFFFFFFF, 72);
        } else {
            float pSat = clamp(baseSat * 0.78f * satMult, 0f, 0.92f);
            float cSat = clamp(baseSat * 0.24f * satMult, 0f, 0.45f);
            float nSat = clamp(baseSat * (is2025 ? 0.07f : 0.04f) * satMult, 0f, 0.12f);

            primary = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_600, hsvColor(hue, pSat, 0.56f))
                    : hsvColor(hue, pSat, 0.56f);

            onPrimary = 0xFFFFFFFF;

            primaryContainer = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_100, hsvColor(hue, cSat, 0.94f))
                    : hsvColor(hue, cSat, 0.94f);

            onPrimaryContainer = (monet && colorIdx == 0 && styleIdx == 0)
                    ? getSysColor(ctx, android.R.color.system_accent1_900, hsvColor(hue, pSat, 0.22f))
                    : hsvColor(hue, pSat, 0.22f);

            bgColor = hsvColor(hue, nSat * 0.7f, 0.965f);
            cardColor = 0xFFFFFFFF;
            cardElevatedColor = hsvColor(hue, nSat * 1.2f, 0.93f);
            cardStrokeColor = withAlpha(primary, 28);

            heroCardBg = primaryContainer;
            heroWatermarkColor = withAlpha(primary, 110);
            heroTextPrimary = onPrimaryContainer;
            heroTextSecondary = withAlpha(onPrimaryContainer, 190);

            textPrimary = 0xFF1C1B1F;
            textSecondary = 0xFF5E5A66;
            textTertiary = 0xFF8A8593;
            sectionTitleColor = primary;
            dividerColor = 0x16000000;

            switchTrackOn = primary;
            switchThumbOn = 0xFFFFFFFF;
            switchTrackOff = hsvColor(hue, nSat * 1.5f, 0.86f);
            switchThumbOff = 0xFF74707D;
            switchStrokeOff = 0x22000000;

            glassSurfaceColor = withAlpha(0xFFFFFFFF, 168);
            glassPillActiveColor = withAlpha(primary, 38);
            glassBorderColor = withAlpha(primary, 64);
        }
    }

    private static int resolveSeedColor(Context ctx, boolean monet, int colorIdx) {
        if (colorIdx > 0 && colorIdx < COLOR_SEEDS.length) {
            return COLOR_SEEDS[colorIdx];
        }
        if (monet && Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(android.R.color.system_accent1_200);
            } catch (Throwable ignored) {
            }
        }
        return COLOR_SEEDS[1];
    }

    private static int getSysColor(Context ctx, int resId, int fallback) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(resId);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    public static int hsvColor(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, clamp(s, 0f, 1f), clamp(v, 0f, 1f)});
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clampInt(alpha, 0, 255) << 24);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isBlurEnabled(Context ctx) {
        return sp(ctx).getBoolean(K_BLUR, true);
    }

    public static boolean isFloatingBar(Context ctx) {
        return sp(ctx).getBoolean(K_FLOATING_BAR, true);
    }

    public static boolean isLiquidGlass(Context ctx) {
        return sp(ctx).getBoolean(K_LIQUID_GLASS, true);
    }

    public static boolean isBottomBadge(Context ctx) {
        return sp(ctx).getBoolean(K_BOTTOM_BADGE, true);
    }

    public static boolean isPredictiveBack(Context ctx) {
        return sp(ctx).getBoolean(K_PREDICTIVE_BACK, true);
    }

    public static boolean isEdgeSwipeBack(Context ctx) {
        return sp(ctx).getBoolean(K_EDGE_SWIPE_BACK, true);
    }

    public static int getPageGestureMode(Context ctx) {
        return sp(ctx).getInt(K_PAGE_GESTURE, 0);
    }

    public static boolean isLauncherIconHidden(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, "com.operit.fullaod.LauncherActivity");
            int state = ctx.getPackageManager().getComponentEnabledSetting(cn);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                return true;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return sp(ctx).getBoolean(K_HIDE_ICON, false);
    }

    public static void setLauncherIconHidden(Context ctx, boolean hide) {
        sp(ctx).edit().putBoolean(K_HIDE_ICON, hide).apply();
        try {
            ComponentName cn = new ComponentName(ctx, "com.operit.fullaod.LauncherActivity");
            ctx.getPackageManager().setComponentEnabledSetting(
                    cn,
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Throwable ignored) {
        }
    }

    public static String getSysProp(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            String val = (String) m.invoke(null, key, def);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    public static String getDeviceName(Context ctx) {
        String market = getSysProp("ro.product.marketname", "");
        if (!market.isEmpty()) return market;
        try {
            String devName = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (devName != null && !devName.trim().isEmpty()) {
                return devName.trim();
            }
        } catch (Throwable ignored) {
        }
        String brand = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "Android Device";
        if (model.toLowerCase().startsWith(brand.toLowerCase())) {
            return model;
        }
        return (brand + " " + model).trim();
    }

    public static String getDeviceModel() {
        String model = Build.MODEL != null ? Build.MODEL : "Unknown";
        String device = Build.DEVICE != null ? Build.DEVICE : "";
        if (!device.isEmpty() && !model.equalsIgnoreCase(device)) {
            return model + " (" + device + ")";
        }
        return model;
    }

    public static String getRomVersion() {
        String[] romProps = {
                "ro.pixelos.version",
                "ro.pixelos.build.version",
                "ro.custom.build.version",
                "ro.lineage.version",
                "ro.modversion",
                "ro.evolution.version",
                "ro.crdroid.version",
                "ro.miui.ui.version.name"
        };
        for (String p : romProps) {
            String v = getSysProp(p, "");
            if (!v.isEmpty()) {
                String disp = Build.DISPLAY != null ? Build.DISPLAY : "";
                if (!disp.isEmpty() && !disp.contains(v)) {
                    return "PixelOS / " + v + " (" + disp + ")";
                }
                return v;
            }
        }
        String disp = Build.DISPLAY != null ? Build.DISPLAY : "";
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT : "";
        if (fingerprint.toLowerCase().contains("pixel") && !disp.toLowerCase().contains("pixel")) {
            return "PixelOS · " + disp;
        }
        return disp.isEmpty() ? Build.ID : disp;
    }

    public static String getAndroidVersion() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }
}
