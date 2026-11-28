package com.operit.fullaod;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    public static final String NAME = "fullaod";

    public static final String K_ENABLED = "enabled";
    public static final String K_MASK = "mask";
    public static final String K_BRIGHT = "bright";
    public static final String K_SHIFT = "shift";
    public static final String K_NIGHT = "night";
    public static final String K_NS = "nightStart";
    public static final String K_NE = "nightEnd";
    public static final String K_IN = "animIn";
    public static final String K_OUT = "animOut";
    public static final String K_SCALE = "scale";
    public static final String K_SHADE = "shade";
    public static final String K_SHADE_MASK = "shadeMask";
    public static final String K_TIMEOUT_ON = "aodTimeoutOn";
    public static final String K_TIMEOUT_MIN = "aodTimeoutMin";
    public static final String K_TIMEOUT_MODE = "aodTimeoutMode";
    public static final String K_NIGHT_MODE = "nightMode";
    public static final String K_NIGHT_SMART = "nightSmart";
    public static final String K_FLIP_OFF = "flipOff";
    public static final String K_LIFT_WAKE = "liftWake";
    public static final String K_AUTO_BRIGHT = "autoBright";
    public static final String K_AUTO_BRIGHT_MIN = "autoBrightMin";
    public static final String K_AUTO_BRIGHT_MAX = "autoBrightMax";

    public static final boolean D_ENABLED = true;
    public static final int D_MASK = 65;
    public static final int D_BRIGHT = 200;
    public static final boolean D_SHIFT = true;
    public static final boolean D_NIGHT = true;
    public static final int D_NS = 23;
    public static final int D_NE = 7;
    public static final int D_IN = 550;
    public static final int D_OUT = 350;
    public static final int D_SCALE = 103;
    public static final boolean D_SHADE = false;
    public static final int D_SHADE_MASK = 80;
    public static final boolean D_TIMEOUT_ON = false;
    public static final int D_TIMEOUT_MIN = 30;
    public static final int D_TIMEOUT_MODE = 0;
    public static final int D_NIGHT_MODE = 0;
    public static final boolean D_NIGHT_SMART = false;
    public static final boolean D_FLIP_OFF = true;
    public static final boolean D_LIFT_WAKE = true;
    public static final boolean D_AUTO_BRIGHT = true;
    public static final int D_AUTO_BRIGHT_MIN = 4;
    public static final int D_AUTO_BRIGHT_MAX = 75;

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void reset(Context c) {
        sp(c).edit()
                .putBoolean(K_ENABLED, D_ENABLED)
                .putInt(K_MASK, D_MASK)
                .putInt(K_BRIGHT, D_BRIGHT)
                .putBoolean(K_SHIFT, D_SHIFT)
                .putBoolean(K_NIGHT, D_NIGHT)
                .putInt(K_NS, D_NS)
                .putInt(K_NE, D_NE)
                .putInt(K_IN, D_IN)
                .putInt(K_OUT, D_OUT)
                .putInt(K_SCALE, D_SCALE)
                .putBoolean(K_SHADE, D_SHADE)
                .putInt(K_SHADE_MASK, D_SHADE_MASK)
                .putBoolean(K_TIMEOUT_ON, D_TIMEOUT_ON)
                .putInt(K_TIMEOUT_MIN, D_TIMEOUT_MIN)
                .putInt(K_TIMEOUT_MODE, D_TIMEOUT_MODE)
                .putInt(K_NIGHT_MODE, D_NIGHT_MODE)
                .putBoolean(K_NIGHT_SMART, D_NIGHT_SMART)
                .putBoolean(K_FLIP_OFF, D_FLIP_OFF)
                .putBoolean(K_LIFT_WAKE, D_LIFT_WAKE)
                .putBoolean(K_AUTO_BRIGHT, D_AUTO_BRIGHT)
                .putInt(K_AUTO_BRIGHT_MIN, D_AUTO_BRIGHT_MIN)
                .putInt(K_AUTO_BRIGHT_MAX, D_AUTO_BRIGHT_MAX)
                .apply();
    }
}