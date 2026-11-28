package com.operit.fullaod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.operit.fullaod.config";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        String json = "{}";
        try {
            SharedPreferences sp = Prefs.sp(getContext());
            JSONObject o = new JSONObject();
            o.put("enabled", sp.getBoolean(Prefs.K_ENABLED, Prefs.D_ENABLED));
            o.put("mask", sp.getInt(Prefs.K_MASK, Prefs.D_MASK) / 100.0);
            o.put("bright", sp.getInt(Prefs.K_BRIGHT, Prefs.D_BRIGHT) / 100.0);
            o.put("shift", sp.getBoolean(Prefs.K_SHIFT, Prefs.D_SHIFT));
            o.put("night", sp.getBoolean(Prefs.K_NIGHT, Prefs.D_NIGHT));
            o.put("nightStart", sp.getInt(Prefs.K_NS, Prefs.D_NS));
            o.put("nightEnd", sp.getInt(Prefs.K_NE, Prefs.D_NE));
            o.put("animIn", sp.getInt(Prefs.K_IN, Prefs.D_IN));
            o.put("animOut", sp.getInt(Prefs.K_OUT, Prefs.D_OUT));
            o.put("scale", sp.getInt(Prefs.K_SCALE, Prefs.D_SCALE) / 100.0);
            o.put("shade", sp.getBoolean(Prefs.K_SHADE, Prefs.D_SHADE));
            o.put("shadeMask", sp.getInt(Prefs.K_SHADE_MASK, Prefs.D_SHADE_MASK) / 100.0);
            o.put("aodTimeoutOn", sp.getBoolean(Prefs.K_TIMEOUT_ON, Prefs.D_TIMEOUT_ON));
            o.put("aodTimeoutMin", sp.getInt(Prefs.K_TIMEOUT_MIN, Prefs.D_TIMEOUT_MIN));
            o.put("aodTimeoutMode", sp.getInt(Prefs.K_TIMEOUT_MODE, Prefs.D_TIMEOUT_MODE));
            o.put("nightMode", sp.getInt(Prefs.K_NIGHT_MODE, Prefs.D_NIGHT_MODE));
            o.put("nightSmart", sp.getBoolean(Prefs.K_NIGHT_SMART, Prefs.D_NIGHT_SMART));
            o.put("flipOff", sp.getBoolean(Prefs.K_FLIP_OFF, Prefs.D_FLIP_OFF));
            o.put("liftWake", sp.getBoolean(Prefs.K_LIFT_WAKE, Prefs.D_LIFT_WAKE));
            o.put("autoBright", sp.getBoolean(Prefs.K_AUTO_BRIGHT, Prefs.D_AUTO_BRIGHT));
            o.put("autoBrightMin", sp.getInt(Prefs.K_AUTO_BRIGHT_MIN, Prefs.D_AUTO_BRIGHT_MIN));
            o.put("autoBrightMax", sp.getInt(Prefs.K_AUTO_BRIGHT_MAX, Prefs.D_AUTO_BRIGHT_MAX));
            json = o.toString();
        } catch (Throwable ignored) {
        }
        b.putString("json", json);
        b.putLong("ts", System.currentTimeMillis());
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}