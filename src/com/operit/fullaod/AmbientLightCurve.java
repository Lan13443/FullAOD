package com.operit.fullaod;

import java.util.Locale;

/**
 * Shared perceptual logarithmic curve utilities for ambient light AOD brightness
 * and dynamic wallpaper mask computation. Used by both AodController and MainActivity.
 */
public final class AmbientLightCurve {

    public static final float LUX_MAX = 10000.0f;
    public static final float INDOOR_REF_NORM = 0.5011f;
    public static final float CURVE_GAMMA = 1.35f;
    public static final float DARK_MASK_BOOST_MAX = 0.20f;
    public static final float BRIGHT_MASK_REDUCE_MAX = 0.25f;
    public static final float MASK_AUTO_FLOOR = 0.15f;
    public static final float MASK_AUTO_CEIL = 0.92f;

    private static final double LOG_DENOM = Math.log10(1.0 + LUX_MAX);

    private AmbientLightCurve() {
    }

    /**
     * Maps raw illuminance (lux) onto a normalized logarithmic range [0, 1].
     */
    public static float luxToNorm(float lux) {
        if (Float.isNaN(lux) || lux <= 0f) {
            return 0f;
        }
        if (Float.isInfinite(lux) || lux >= LUX_MAX) {
            return 1f;
        }
        float norm = (float) (Math.log10(1.0 + lux) / LOG_DENOM);
        return clampFloat(norm, 0f, 1f);
    }

    /**
     * Converts normalized log illuminance [0, 1] into a perceptual brightness factor [0, 1].
     */
    public static float normToPerceptualFactor(float norm) {
        float clamped = clampFloat(norm, 0f, 1f);
        return clampFloat((float) Math.pow(clamped, CURVE_GAMMA), 0f, 1f);
    }

    /**
     * Computes hardware AOD brightness percentage in [minPct, maxPct] for the given lux.
     */
    public static int computeBrightnessPct(float lux, int minPct, int maxPct) {
        int lo = clampInt(minPct, 1, 100);
        int hi = clampInt(Math.max(lo, maxPct), lo, 100);
        float factor = normToPerceptualFactor(luxToNorm(lux));
        return clampInt(Math.round(lo + (hi - lo) * factor), lo, hi);
    }

    /**
     * Converts a 1..100 brightness percentage to Android integer Doze brightness (1..255).
     */
    public static int pctToDozeInt(int pct) {
        int clampedPct = clampInt(pct, 1, 100);
        return clampInt(Math.round(clampedPct * 255f / 100f), 1, 255);
    }

    /**
     * Converts a 1..100 brightness percentage to Android float Doze brightness (0.004..1.0).
     */
    public static float pctToDozeFloat(int pct) {
        int clampedPct = clampInt(pct, 1, 100);
        return clampFloat(clampedPct / 100f, 0.004f, 1.0f);
    }

    /**
     * Computes dynamic wallpaper dimming mask around the user's indoor baseline (~100 lux).
     * Dark environments deepen the mask (up to +0.20); bright environments lighten it (up to -0.25).
     */
    public static float computeDynamicMask(float lux, float baseMask) {
        float base = clampFloat(baseMask, 0f, 0.95f);
        float norm = luxToNorm(lux);
        if (norm <= INDOOR_REF_NORM) {
            float darkRatio = (INDOOR_REF_NORM - norm) / INDOOR_REF_NORM;
            float ceil = Math.min(0.95f, Math.max(base, MASK_AUTO_CEIL));
            return clampFloat(base + darkRatio * DARK_MASK_BOOST_MAX, base, ceil);
        }
        float brightRatio = (norm - INDOOR_REF_NORM) / (1f - INDOOR_REF_NORM);
        float floor = Math.max(0f, Math.min(base, MASK_AUTO_FLOOR));
        return clampFloat(base - brightRatio * BRIGHT_MASK_REDUCE_MAX, floor, base);
    }

    /**
     * Interpolates hardware Doze brightness (1..255) in perceptual log-space.
     */
    public static float lerpLogBrightness(float startHw, float targetHw, float t) {
        float s = clampFloat(startHw, 1f, 255f);
        float e = clampFloat(targetHw, 1f, 255f);
        float f = clampFloat(t, 0f, 1f);
        if (Math.abs(e - s) < 0.001f) {
            return e;
        }
        double logS = Math.log(s);
        double logE = Math.log(e);
        return clampFloat((float) Math.exp(logS + (logE - logS) * f), 1f, 255f);
    }

    /**
     * Applies subtle sub-step wallpaper mask compensation between integer hardware steps (1..255).
     */
    public static float compensateMaskForHwStep(float idealMask, float idealHwFloat, int actualHwInt) {
        float base = clampFloat(idealMask, 0f, 0.95f);
        if (actualHwInt <= 0 || Float.isNaN(idealHwFloat) || idealHwFloat <= 0f) {
            return base;
        }
        float ratio = clampFloat(idealHwFloat / (float) actualHwInt, 0.88f, 1.12f);
        float compVisible = clampFloat((1f - base) * ratio, 0.05f, 1f);
        return clampFloat(1f - compVisible, 0f, 0.95f);
    }

    /**
     * Computes adaptive transition duration: 750-1100ms when brightening, 1100-1600ms when darkening.
     */
    public static int computeTransitionDurationMs(float fromNorm, float toNorm, boolean brightening) {
        float span = clampFloat(Math.abs(toNorm - fromNorm), 0f, 1f);
        if (brightening) {
            return clampInt(Math.round(750f + 350f * span), 750, 1100);
        }
        return clampInt(Math.round(1100f + 500f * span), 1100, 1600);
    }

    /**
     * Returns a short Chinese description of the ambient light level.
     */
    public static String describeLightLevel(float lux) {
        float safe = Float.isNaN(lux) ? 0f : lux;
        if (safe < 2f) return "极暗";
        if (safe < 25f) return "暗光";
        if (safe < 300f) return "室内";
        if (safe < 2000f) return "明亮";
        return "强光";
    }

    /**
     * Formats a lux value cleanly for logs and UI preview readouts.
     */
    public static String formatLux(float lux) {
        float safe = (Float.isNaN(lux) || lux < 0f) ? 0f : (Float.isInfinite(lux) ? LUX_MAX : lux);
        if (safe < 10f) {
            return String.format(Locale.US, "%.1f lux", safe);
        }
        return Math.round(safe) + " lux";
    }

    private static float clampFloat(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
