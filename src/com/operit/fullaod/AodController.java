package com.operit.fullaod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 运行在 com.android.systemui 进程内。
 */
public final class AodController {

    public static final String TAG = "FullAOD";
    private static final String CONFIG_URI = "content://com.operit.fullaod.config";

    private static AodController sInst;

    public static synchronized AodController get() {
        if (sInst == null) sInst = new AodController();
        return sInst;
    }

    private final Handler mH = new Handler(Looper.getMainLooper());
    private final Random mRand = new Random();

    private Context mCtx;
    private ViewGroup mParent;
    private WallpaperAodView mView;
    private Bitmap mBitmap;
    private Object mScrimController;

    private boolean mAod = false;
    private boolean mIntervene = false;
    private boolean mInApply = false;
    private boolean mCfgLoaded = false;
    private long mLastCfg = 0L;
    private String mLastCfgJson = null;
    private ValueAnimator mAnim;

    // 配置（默认值与 Prefs 保持一致）
    private boolean cfgEnabled = true;
    private float cfgMask = 0.65f;
    private float cfgBright = 2.0f;
    private boolean cfgShift = true;
    private boolean cfgNight = true;
    private int cfgNS = 23;
    private int cfgNE = 7;
    private int cfgAnimIn = 550;
    private int cfgAnimOut = 350;
    private float cfgScale = 1.03f;
    private boolean cfgTop = false;
    private boolean cfgTimeoutOn = false;
    private int cfgTimeoutMin = 30;
    private int cfgTimeoutMode = 0;
    private int cfgNightMode = 0;
    private boolean cfgNightSmart = false;
    private boolean cfgFlipOff = true;
    private boolean cfgLiftWake = true;
    private boolean mNightActive = false;
    private int mNightSessDay = -999;
    private boolean mNightSessEnded = false;
    private long mAodEnterTs = 0L;
    private boolean mTimedOut = false;
    private Object mDozeMachine = null;
    private boolean cfgShade = false;
    private float cfgShadeMask = 0.80f;
    private boolean mShadeMode = false;

    // 姿态检测（反扣关闭所有 AOD / 拿起自动亮屏）与壁纸更新监听
    private boolean mWallpaperReceiverRegistered = false;
    private boolean mFaceDownPaused = false;
    private boolean mSensorRegistered = false;
    private boolean mPickupTriggerRegistered = false;
    private Sensor mPickupSensor = null;
    private PowerManager.WakeLock mFaceDownWakeLock = null;
    private boolean mGravityInit = false;
    private float mGx = 0f, mGy = 0f, mGz = 9.8f;
    private boolean mBaseGravityInit = false;
    private float mBaseGx = 0f, mBaseGy = 0f, mBaseGz = 9.8f;
    private boolean mProximityNear = false;
    private long mFaceDownSince = 0L;
    private long mFaceUpSince = 0L;
    private long mSensorStartElapsed = 0L;
    private long mLastMotionTs = 0L;
    private long mLastLiftWakeTs = 0L;
    private boolean mScrimCancelled = false;
    private boolean mScrimRestored = false;
    private boolean mScrimApplied = false;
    private boolean mRootDimmed = false;
    private boolean mBouncerBg = false;
    private long mBouncerLast = 0L;
    private View mClockView = null;
    private float mClockOldAlpha = 1f;
    private final java.util.ArrayList<View> mClocks = new java.util.ArrayList<View>();
    private final java.util.ArrayList<Float> mClocksAlpha = new java.util.ArrayList<Float>();
    private android.graphics.drawable.Drawable mRootDimBg = null;
    private int mScrimHeal = 0;

    private final java.util.ArrayList<View> mClearedViews = new java.util.ArrayList<View>();
    private final java.util.ArrayList<android.graphics.drawable.Drawable> mClearedBgs =
            new java.util.ArrayList<android.graphics.drawable.Drawable>();

    private final Runnable mShiftTask = new Runnable() {
        @Override
        public void run() {
            if (!mAod || mView == null) return;
            float d = cfgShift ? 3f : 0f;
            float x = d == 0f ? 0f : (mRand.nextFloat() * 2f - 1f) * d;
            float y = d == 0f ? 0f : (mRand.nextFloat() * 2f - 1f) * d;
            mView.setPixelShift(x, y);
            mH.postDelayed(this, 90000L);
        }
    };

    private AodController() {
    }

    public void setContext(Context c) {
        if (c != null && mCtx == null) {
            mCtx = c;
            log("context set");
            registerWallpaperReceiver();
        }
    }

    private void registerWallpaperReceiver() {
        if (mWallpaperReceiverRegistered || mCtx == null) return;
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
            mCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    log("wallpaper changed broadcast -> reload");
                    loadWallpaper(2);
                }
            }, filter);
            mWallpaperReceiverRegistered = true;
        } catch (Throwable t) {
            log("registerWallpaperReceiver fail: " + t);
        }
    }

    /* ---------------- 入口：挂载视图 ---------------- */

    public void attachShadeView(ViewGroup root, Context ctx) {
        if (root == null) return;
        try {
            setContext(ctx);
            mParent = root;
            if (mView == null) {
                mView = new WallpaperAodView(ctx);
                mView.setAlpha(0f);
                mView.setVisibility(View.GONE);
                mView.setMaskAlpha(cfgMask);
            } else if (mView.getParent() == root) {
                log("attach: already attached, children=" + root.getChildCount());
                ensureWallpaperLoaded();
                return;
            } else if (mView.getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) mView.getParent()).removeView(mView);
                } catch (Throwable ignored) {
                }
            }

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            try {
                root.addView(mView, 0, lp);
            } catch (Throwable t) {
                log("attach index0 fail: " + t);
                root.addView(mView, lp);
            }
            log("attach ok: inserted at index 0, children=" + root.getChildCount()
                    + " root=" + root.getClass().getName());
            ensureWallpaperLoaded();
            refreshConfig();
            startTick();
        } catch (Throwable t) {
            log("attachShadeView fail: " + t);
        }
    }

    private void ensureWallpaperLoaded() {
        if (mBitmap != null) return;
        mH.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadWallpaper(3);
            }
        }, 1500L);
    }

    /* ---------------- 壁纸加载 ---------------- */

    private void loadWallpaper(final int retries) {
        final Context c = mCtx;
        if (c == null) {
            log("loadWallpaper: no context");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(c);
                    Drawable d = wm.getDrawable();
                    Bitmap src = null;
                    if (d instanceof BitmapDrawable) {
                        src = ((BitmapDrawable) d).getBitmap();
                    } else if (d != null) {
                        int w = Math.max(1, c.getResources().getDisplayMetrics().widthPixels);
                        int h = Math.max(1, c.getResources().getDisplayMetrics().heightPixels);
                        Bitmap tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(tmp);
                        d.setBounds(0, 0, w, h);
                        d.draw(cv);
                        src = tmp;
                    }
                    if (src == null) throw new IllegalStateException("wallpaper drawable null");

                    int tw = Math.min(1080, src.getWidth());
                    if (tw < 1) tw = 1;
                    int th = (int) ((float) tw * src.getHeight() / Math.max(1, src.getWidth()));
                    if (th < 1) th = 1;
                    Bitmap scaled = Bitmap.createScaledBitmap(src, tw, th, true);
                    Bitmap out = scaled;
                    try {
                        out = scaled.copy(Bitmap.Config.RGB_565, false);
                    } catch (Throwable ignored) {
                    }
                    final Bitmap fout = out;
                    mBitmap = fout;
                    mH.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mView != null) {
                                mView.setBitmap(fout);
                                mView.setMaskAlpha(cfgMask);
                            }
                            log("wallpaper loaded " + fout.getWidth() + "x" + fout.getHeight()
                                    + " " + fout.getConfig());
                        }
                    });
                } catch (final Throwable t) {
                    log("wallpaper load fail(" + retries + "): " + t);
                    if (retries > 0) {
                        mH.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                loadWallpaper(retries - 1);
                            }
                        }, 3000L);
                    }
                }
            }
        }).start();
    }

    /* ---------------- 配置 ---------------- */

    public void refreshConfig() {
        long now = SystemClock.uptimeMillis();
        if (mCfgLoaded && now - mLastCfg < 3000L) return;
        mLastCfg = now;
        Context c = mCtx;
        if (c == null) return;
        String json = null;
        try {
            String s = android.provider.Settings.Secure.getString(
                    c.getContentResolver(), "fullaod_cfg");
            if (s != null && s.length() > 2) {
                json = s;
            }
        } catch (Throwable t2) {
            log("cfg settings fail: " + t2);
        }
        if (json == null || json.length() <= 2) {
            try {
                Bundle b = c.getContentResolver().call(Uri.parse(CONFIG_URI), "get", null, null);
                if (b != null) json = b.getString("json");
            } catch (Throwable t) {
                log("cfg provider fail: " + t);
            }
        }
        try {
            if (json == null || json.length() <= 2) return;
            if (json.equals(mLastCfgJson)) return;
            mLastCfgJson = json;
            log("cfg updated -> " + json);
            JSONObject o = new JSONObject(json);
            cfgEnabled = o.optBoolean("enabled", true);
            cfgMask = (float) o.optDouble("mask", 0.65);
            cfgBright = (float) o.optDouble("bright", 2.0);
            cfgShift = o.optBoolean("shift", true);
            cfgNight = o.optBoolean("night", true);
            cfgNS = o.optInt("nightStart", 23);
            cfgNE = o.optInt("nightEnd", 7);
            cfgAnimIn = o.optInt("animIn", 550);
            cfgAnimOut = o.optInt("animOut", 350);
            cfgScale = (float) o.optDouble("scale", 1.03);
            cfgTop = o.optBoolean("top", false);
            cfgShade = o.optBoolean("shade", false);
            cfgShadeMask = (float) o.optDouble("shadeMask", 0.80);
            cfgTimeoutOn = o.optBoolean("aodTimeoutOn", false);
            cfgTimeoutMin = o.optInt("aodTimeoutMin", 30);
            cfgTimeoutMode = o.optInt("aodTimeoutMode", 0);
            cfgNightMode = o.optInt("nightMode", 0);
            cfgNightSmart = o.optBoolean("nightSmart", false);
            cfgFlipOff = o.optBoolean("flipOff", true);
            cfgLiftWake = o.optBoolean("liftWake", true);
            mCfgLoaded = true;
            if (mView != null) mView.setMaskAlpha(cfgMask);
            if (!cfgFlipOff && mFaceDownPaused) {
                exitFaceDownAodResume();
            }
            updatePostureSensors();
        } catch (Throwable t) {
            log("cfg read fail: " + t);
        }
    }

    /** 夜间会话编号：跨零点时段凌晨归属前一晚，非跨零点时段归属当天，支持跨年 */
    private int nightSessionKey(Calendar cal) {
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int yearDay = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR);
        if (cfgNS > cfgNE) {
            return (h >= cfgNS) ? yearDay : yearDay - 1;
        }
        return yearDay;
    }

    /**
     * 维护"当前是否处于夜间退化"。
     * 智能模式：在夜间时间窗内进入，直到"次日 05:00 后第一次亮屏并解锁"才结束；
     * 结束标记按会话记录，当天不会再重新进入。
     */
    private void updateNightActive() {
        if (!cfgEnabled || !cfgNight) {
            mNightActive = false;
            return;
        }
        if (!cfgNightSmart && mNightSessEnded) {
            mNightSessEnded = false;
        }
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int key = nightSessionKey(cal);
        if (key != mNightSessDay) {
            mNightSessDay = key;
            mNightSessEnded = false;
        }
        if (!isNight() || mNightSessEnded) {
            mNightActive = false;
            return;
        }
        if (!mNightActive) {
            mNightActive = true;
            log("night: active smart=" + cfgNightSmart + " key=" + key);
        }
        if (cfgNightSmart) {
            boolean unlocked = isScreenOn()
                    && readScrimState().indexOf("UNLOCKED") >= 0;
            if (h >= 5 && unlocked) {
                mNightActive = false;
                mNightSessEnded = true;
                log("night: ended by first unlock at " + h + "h");
            }
        }
    }

    private boolean isNight() {
        if (cfgNS == cfgNE) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (cfgNS < cfgNE) return h >= cfgNS && h < cfgNE;
        return h >= cfgNS || h < cfgNE;
    }

    /* ---------------- doze 状态 ---------------- */

    public void onDozeState(String state) {
        if (state == null) return;
        boolean on = state.startsWith("DOZE_AOD");
        if (on) {
            if (mAod) {
                refreshConfig();
                updateNightActive();
                boolean night = mNightActive;
                boolean now = cfgEnabled && !night && !mFaceDownPaused && !mTimedOut;
                if (now != mIntervene) {
                    mIntervene = now;
                    if (mIntervene && mView != null) {
                        mView.setMaskAlpha(cfgMask);
                        mView.setVisibility(View.VISIBLE);
                        animateTo(1f, cfgAnimIn);
                    } else {
                        animateTo(0f, cfgAnimOut);
                        if ((night && cfgNightMode == 1) || mFaceDownPaused) {
                            if (requestAodPaused()) log("aod paused (screen off)");
                        }
                    }
                    log("AOD re-eval intervene=" + mIntervene + " night=" + night + " faceDown=" + mFaceDownPaused);
                }
                applyScrimNow();
                if (mIntervene) ensureAodVisible();
                return;
            }
            applyAod(true);
        } else {
            if (!mAod) return;
            applyAod(false);
        }
    }

    private void applyAod(boolean on) {
        if (mInApply) {
            log("applyAod skip(reentry) on=" + on + " cur=" + mAod);
            return;
        }
        mInApply = true;
        try {
            applyAodInner(on);
        } finally {
            mInApply = false;
        }
    }

    private void applyAodInner(boolean on) {
        refreshConfig();
        mAod = on;
        if (!on && isScreenOn()) {
            mFaceDownPaused = false;
            releaseFaceDownWakeLock();
        }
        updateNightActive();
        boolean night = mNightActive;
        mIntervene = on && cfgEnabled && !night && !mFaceDownPaused;
        updatePostureSensors();

        if (mView == null) {
            log("AOD " + (on ? "on" : "off") + " but view null");
            return;
        }
        if (mIntervene) {
            mScrimCancelled = false;
            mScrimRestored = false;
            mScrimApplied = false;
            mScrimHeal = 0;
            mAodEnterTs = SystemClock.elapsedRealtime();
            mTimedOut = false;
            clearRootDim();
            hideBouncerBg();
            mView.setMaskAlpha(cfgMask);
            if (mView.getVisibility() != View.VISIBLE) mView.setVisibility(View.VISIBLE);
            mH.post(new Runnable() {
                @Override
                public void run() {
                    placeBelowPanel();
                }
            });
            clearOpaqueBackgrounds();
            startScrimKeeper();
            animateTo(1f, cfgAnimIn);
            kickShift();
            final WallpaperAodView dv = mView;
            mH.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ViewGroup p = dv.getParent() instanceof ViewGroup
                            ? (ViewGroup) dv.getParent() : null;
                    log("diag w=" + dv.getWidth() + " h=" + dv.getHeight()
                            + " alpha=" + dv.getAlpha() + " vis=" + dv.getVisibility()
                            + " idx=" + (p == null ? -1 : p.indexOfChild(dv))
                            + " children=" + (p == null ? -1 : p.getChildCount()));
                }
            }, 1500L);
        } else {
            mScrimCancelled = false;
            mH.removeCallbacks(mShiftTask);
            restoreOpaqueBackgrounds();
            stopScrimKeeper();
            restoreScrimAlphas();
            animateTo(0f, cfgAnimOut);
            if (on && ((night && cfgNightMode == 1) || mFaceDownPaused)) {
                if (mFaceDownPaused) hideAllClocks();
                if (requestAodPaused()) log("aod paused (screen off)");
            }
        }
        if (on) applyScrimNow();
        log("AOD " + (on ? "ON" : "OFF") + " intervene=" + mIntervene + " night=" + night
                + " faceDown=" + mFaceDownPaused
                + " mask=" + cfgMask + " bright=" + cfgBright + " scale=" + cfgScale
                + " anim=" + cfgAnimIn + "/" + cfgAnimOut);
    }

    private void animateTo(final float target, int dur) {
        if (mView == null) return;
        if (mAnim != null) {
            try {
                mAnim.cancel();
            } catch (Throwable ignored) {
            }
            mAnim = null;
        }
        float from = mView.getAlpha();
        if (Math.abs(from - target) < 0.002f) {
            mView.setAlpha(target);
            if (target <= 0.002f) mView.setVisibility(View.GONE);
            return;
        }
        if (target > 0f) mView.setVisibility(View.VISIBLE);

        ValueAnimator anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(Math.max(60, dur));
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (mView == null) return;
                float v = (Float) a.getAnimatedValue();
                mView.setAlpha(v);
                float s = 1f + (cfgScale - 1f) * v;
                mView.setScaleX(s);
                mView.setScaleY(s);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (mAnim == a) mAnim = null;
                if (mView != null && target <= 0.002f) {
                    mView.setVisibility(View.GONE);
                    mView.setScaleX(1f);
                    mView.setScaleY(1f);
                }
            }
        });
        mAnim = anim;
        anim.start();
    }

    private void kickShift() {
        mH.removeCallbacks(mShiftTask);
        mH.postDelayed(mShiftTask, 90000L);
    }

    /* ---------------- scrim ---------------- */

    public void onScrimChanged(Object ctrl) {
        if (ctrl == null) return;
        mScrimController = ctrl;
        boolean dozing = false;
        String state = "";
        try {
            Object d = XposedHelpers.getObjectField(ctrl, "mDozing");
            if (d instanceof Boolean) dozing = ((Boolean) d).booleanValue();
        } catch (Throwable ignored) {
        }
        try {
            Object s = XposedHelpers.getObjectField(ctrl, "mState");
            if (s != null) state = String.valueOf(s);
        } catch (Throwable ignored) {
        }
        boolean aod = dozing || state.indexOf("AOD") >= 0;
        if (aod) {
            if (!mAod) log("scrim->AOD dozing=" + dozing + " state=" + state);
            onDozeState("DOZE_AOD");
        } else if (mAod) {
            if (!isScreenOn()) {
                log("scrim->leave ignored (screen off, state=" + state + ")");
                return;
            }
            log("scrim->leave dozing=" + dozing + " state=" + state);
            onDozeState("OTHER");
        }
    }

    private void clearOpaqueBackgrounds() {
        try {
            if (!mClearedViews.isEmpty()) {
                restoreOpaqueBackgrounds();
            }
            ViewGroup root = (mView != null && mView.getParent() instanceof ViewGroup)
                    ? (ViewGroup) mView.getParent() : mParent;
            if (root == null) return;
            int rw = root.getWidth();
            int rh = root.getHeight();
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                if (c == mView) continue;
                if (c.getVisibility() != View.VISIBLE) continue;
                if (c.getWidth() < rw - 4 || c.getHeight() < rh - 4) continue;
                android.graphics.drawable.Drawable bg = c.getBackground();
                int col = 0;
                boolean opaque = false;
                if (bg instanceof android.graphics.drawable.ColorDrawable) {
                    col = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                    opaque = true;
                }
                if (opaque) {
                    mClearedViews.add(c);
                    mClearedBgs.add(bg);
                    c.setBackground(null);
                    log("clear bg[" + i + "] " + c.getClass().getSimpleName()
                            + " #" + Integer.toHexString(col));
                } else {
                    log("full[" + i + "] " + c.getClass().getSimpleName()
                            + " bg=" + (bg == null ? "null" : bg.getClass().getSimpleName()));
                }
            }
        } catch (Throwable t) {
            log("clearOpaque fail " + t);
        }
    }

    private void restoreOpaqueBackgrounds() {
        try {
            for (int i = 0; i < mClearedViews.size(); i++) {
                mClearedViews.get(i).setBackground(mClearedBgs.get(i));
            }
            if (!mClearedViews.isEmpty()) log("restored " + mClearedViews.size() + " bg");
            mClearedViews.clear();
            mClearedBgs.clear();
        } catch (Throwable t) {
            log("restore fail " + t);
        }
    }

    private final Runnable mScrimKeeper = new Runnable() {
        @Override
        public void run() {
            if (!mAod || !mIntervene) return;
            if (mScrimController != null) applyScrim(mScrimController);
            mH.postDelayed(this, 200L);
        }
    };

    private void startScrimKeeper() {
        mH.removeCallbacks(mScrimKeeper);
        mH.post(mScrimKeeper);
    }

    private void stopScrimKeeper() {
        mH.removeCallbacks(mScrimKeeper);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            try {
                tickLogic();
            } catch (Throwable t) {
                log("tick fail " + t);
            }
            mH.postDelayed(this, 300L);
        }
    };

    private void startTick() {
        mH.removeCallbacks(mTick);
        mH.post(mTick);
    }

    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    mCtx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private void tickLogic() {
        if (mCtx == null) return;
        refreshConfig();
        if (mView == null) return;
        updateNightActive();
        boolean screenOn = isScreenOn();
        String st = "";
        if (mScrimController != null) {
            try {
                Object s = XposedHelpers.getObjectField(mScrimController, "mState");
                if (s != null) st = String.valueOf(s);
            } catch (Throwable ignored) {
            }
        }
        boolean aodState = st.indexOf("AOD") >= 0;
        updatePostureSensors();

        if (!screenOn) {
            if (aodState) {
                if (!mAod) {
                    log("tick: enter AOD state=" + st);
                    applyAod(true);
                } else if (mIntervene) {
                    applyScrim(mScrimController);
                    ensureAodVisible();
                    checkAodTimeout();
                }
            } else if (mAod) {
                log("tick: leave AOD state=" + st);
                applyAod(false);
            }
            mShadeMode = false;
            return;
        }

        if (mFaceDownPaused) {
            mFaceDownPaused = false;
            releaseFaceDownWakeLock();
            restoreClocks();
        }
        if (mAod) {
            log("tick: screen on -> leave AOD");
            applyAod(false);
        }
        boolean shadeState = st.indexOf("SHADE") >= 0 || st.indexOf("UNLOCKED") >= 0;
        if (cfgEnabled && cfgShade && shadeState) {
            if (!mShadeMode) {
                mShadeMode = true;
                log("tick: shade wallpaper on mask=" + cfgShadeMask);
            }
            if (mAnim != null) {
                try {
                    mAnim.cancel();
                } catch (Throwable ignored) {
                }
                mAnim = null;
            }
            if (mView.getVisibility() != View.VISIBLE) mView.setVisibility(View.VISIBLE);
            mView.setAlpha(1f);
            mView.setScaleX(1f);
            mView.setScaleY(1f);
            mView.setMaskAlpha(cfgShadeMask);
            if (mScrimController != null) applyScrim(mScrimController);
        } else {
            if (mShadeMode) {
                mShadeMode = false;
                log("tick: shade wallpaper off");
                try {
                    XposedHelpers.callMethod(mScrimController, "applyAndDispatchState");
                } catch (Throwable ignored) {
                }
            }
            if (!mAod && !mBouncerBg) {
                mView.setAlpha(0f);
                mView.setVisibility(View.GONE);
            }
        }
        ensureScrimForKeyguard();
        ensureKeyguardDim();
    }

    public void setDozeMachine(Object dm) {
        mDozeMachine = dm;
    }

    private void checkAodTimeout() {
        if (!cfgTimeoutOn || cfgTimeoutMin <= 0 || mTimedOut) return;
        long el = SystemClock.elapsedRealtime() - mAodEnterTs;
        if (el < cfgTimeoutMin * 60000L) return;
        mTimedOut = true;
        applyAodTimeout();
    }

    private void applyAodTimeout() {
        log("AOD timeout " + cfgTimeoutMin + "min mode=" + cfgTimeoutMode);
        if (cfgTimeoutMode == 1) {
            if (requestAodPaused()) {
                log("timeout: aod paused (screen off)");
                return;
            }
            log("timeout: pause failed, fallback clock-only");
        }
        degradeToClockOnly();
    }

    /** 退化为系统原生"仅时钟" AOD：撤掉壁纸与 scrim 压制 */
    private void degradeToClockOnly() {
        try {
            mIntervene = false;
            mH.removeCallbacks(mShiftTask);
            stopScrimKeeper();
            restoreOpaqueBackgrounds();
            if (mView != null) {
                mView.setBlur(0f);
                mView.setAlpha(0f);
                mView.setVisibility(View.GONE);
            }
            restoreScrimAlphas();
            log("degraded: clock-only AOD");
        } catch (Throwable t) {
            log("degrade fail " + t);
        }
    }

    /** 请求系统真正关掉 AOD（进入 DOZE_AOD_PAUSED，屏幕熄灭） */
    private boolean requestAodPaused() {
        if (mDozeMachine == null) return false;
        try {
            ClassLoader cl2 = mDozeMachine.getClass().getClassLoader();
            Class<?> stCls = XposedHelpers.findClass(
                    "com.android.systemui.doze.DozeMachine$State", cl2);
            Object target = XposedHelpers.getStaticObjectField(stCls, "DOZE_AOD_PAUSED");
            XposedHelpers.callMethod(mDozeMachine, "requestState", target);
            return true;
        } catch (Throwable t) {
            log("requestAodPaused fail " + t);
        }
        return false;
    }

    /** 请求从 DOZE_AOD_PAUSED 恢复到 DOZE_AOD（反扣翻转朝上时恢复显示 AOD） */
    private boolean requestAodResume() {
        if (mDozeMachine == null) return false;
        try {
            ClassLoader cl2 = mDozeMachine.getClass().getClassLoader();
            Class<?> stCls = XposedHelpers.findClass(
                    "com.android.systemui.doze.DozeMachine$State", cl2);
            Object target = XposedHelpers.getStaticObjectField(stCls, "DOZE_AOD");
            XposedHelpers.callMethod(mDozeMachine, "requestState", target);
            return true;
        } catch (Throwable t) {
            log("requestAodResume fail " + t);
        }
        return false;
    }

    private void ensureAodVisible() {
        if (mView == null || !mIntervene) return;
        try {
            if (mView.getVisibility() != View.VISIBLE) {
                log("heal: visibility GONE -> VISIBLE");
                mView.setVisibility(View.VISIBLE);
            }
            float targetScale = 1f + (cfgScale - 1f);
            if (mAnim == null) {
                if (mView.getAlpha() < 0.95f) {
                    log("heal: alpha " + mView.getAlpha() + " -> 1.0");
                    mView.setAlpha(1f);
                }
                if (Math.abs(mView.getScaleX() - targetScale) > 0.02f) {
                    mView.setScaleX(targetScale);
                    mView.setScaleY(targetScale);
                }
            }
            if (mView.getParent() == null) {
                log("heal: no parent -> re-attach");
                attachShadeView(mParent, mCtx);
            }
        } catch (Throwable t) {
            log("heal fail " + t);
        }
    }

    private void placeBelowPanel() {
        try {
            if (mView == null) return;
            ViewGroup p = mView.getParent() instanceof ViewGroup
                    ? (ViewGroup) mView.getParent() : null;
            if (p == null) return;
            View panel = null;
            for (int i = 0; i < p.getChildCount(); i++) {
                View c = p.getChildAt(i);
                if (c.getClass().getName().endsWith("NotificationPanelView")) {
                    panel = c;
                    break;
                }
            }
            if (panel == null) {
                log("place: panel not found");
                return;
            }
            int myIdx = p.indexOfChild(mView);
            int pIdx = p.indexOfChild(panel);
            if (myIdx >= 0 && myIdx == pIdx - 1) {
                log("place: ok idx=" + myIdx + " panel=" + pIdx);
                return;
            }
            p.removeView(mView);
            int target = p.indexOfChild(panel);
            if (target < 0) target = 0;
            FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            p.addView(mView, target, lp2);
            log("place: moved idx=" + p.indexOfChild(mView) + " panel=" + p.indexOfChild(panel)
                    + " children=" + p.getChildCount());
        } catch (Throwable t) {
            log("place fail " + t);
        }
    }

    private String readScrimState() {
        String st = "";
        if (mScrimController != null) {
            try {
                Object s = XposedHelpers.getObjectField(mScrimController, "mState");
                if (s != null) st = String.valueOf(s);
            } catch (Throwable ignored) {
            }
        }
        return st;
    }

    /**
     * 指纹/密码界面（KEYGUARD / BOUNCER）需要 ScrimController 自己的暗化+模糊层。
     * 我在 AOD 期间把三层 ScrimView 的 alpha 压成 0，如果退出时系统没重算，
     * 密码键盘就会直接印在锁屏上（无背景、无模糊）。这里做兜底自愈。
     */
    /**
     * 这个 ROM 的 ScrimView 在 AOD 之后内部绘制矩形会卡成 0x0（mBoundsRectF 为零面积），
     * 改 alpha / tint 都画不出东西。所以这里自己补一层暗化：
     * 只要系统 scrim 实际不可见，就在 NotificationShadeWindowView 上铺半透明黑，
     * 密码键盘/时钟在其上层保持清晰，视觉上等于原生锁屏的压暗层。
     */
    private void ensureKeyguardDim() {
        if (mParent == null) return;
        try {
            if (mAod || mIntervene) {
                clearRootDim();
                return;
            }
            String st = readScrimState();
            // 只有"密码键盘弹出"才需要暗化背景；
            // 普通锁屏（KEYGUARD：时钟/通知/人脸）必须保持原生、壁纸可见。
            boolean kd = st.indexOf("BOUNCER") >= 0;
            if (!kd && st.indexOf("KEYGUARD") >= 0) {
                kd = hasBouncerView(mParent, 0);
            }
            long nowTs = SystemClock.uptimeMillis();
            if (kd) {
                mBouncerLast = nowTs;
            } else if (mBouncerBg) {
                boolean definitelyOut = st.indexOf("UNLOCKED") >= 0
                        || st.indexOf("SHADE") >= 0 || st.indexOf("DREAMING") >= 0;
                if (!definitelyOut && nowTs - mBouncerLast < 1500L) {
                    kd = true; // 状态抖动时不急于撤掉模糊背景
                }
            }
            if (!kd) {
                clearRootDim();
                hideBouncerBg();
                return;
            }
            View behind = null;
            try {
                Object o = XposedHelpers.getObjectField(mScrimController, "mScrimBehind");
                if (o instanceof View) behind = (View) o;
            } catch (Throwable ignored) {
            }
            // 真正的判据：ScrimDrawable 内部的绘制矩形是不是零面积。
            // 这个 ROM 的 ScrimView 在 AOD 之后 mBoundsRectF 会卡成 0x0，
            // 此时 getGlobalVisibleRect() 仍可能返回 true，但实际什么都没画。
            boolean drawableOk = false;
            if (behind != null) {
                try {
                    Object dr = XposedHelpers.getObjectField(behind, "mDrawable");
                    if (dr != null) {
                        Object rf = XposedHelpers.getObjectField(dr, "mBoundsRectF");
                        if (rf instanceof android.graphics.RectF) {
                            android.graphics.RectF f = (android.graphics.RectF) rf;
                            drawableOk = f.width() > 100f && f.height() > 100f;
                        } else {
                            drawableOk = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!mBouncerBg) showBouncerBg();
            // 系统 scrim 能正常绘制时不做额外处理；画不出来时再叠一层淡暗化兜底
            if (drawableOk) {
                clearRootDim();
                return;
            }
            if (!mRootDimmed) {
                mRootDimmed = true;
                try {
                    mRootDimBg = mParent.getBackground();
                } catch (Throwable ignored) {
                }
                mParent.setBackgroundColor(0x4D000000);
                log("dim root fallback state=" + st + " drawableOk=" + drawableOk);
            }
        } catch (Throwable t) {
            log("dim fail " + t);
        }
    }

    private void hideAllClocks() {
        if (!mClocks.isEmpty()) {
            restoreClocks();
        }
        mClocks.clear();
        mClocksAlpha.clear();
        collectClocks(mParent, 0);
        for (int i = 0; i < mClocks.size(); i++) {
            float curA = mClocks.get(i).getAlpha();
            mClocksAlpha.add(Float.valueOf(curA > 0.05f ? curA : 1.0f));
            mClocks.get(i).setAlpha(0f);
        }
        log("clocks hidden n=" + mClocks.size());
    }

    private void collectClocks(View v, int depth) {
        if (v == null || depth > 12) return;
        String n = v.getClass().getSimpleName();
        boolean hit = n.contains("Clock") || n.endsWith("KeyguardStatusView")
                || n.contains("SmartSpace");
        if (hit) {
            mClocks.add(v);
            log("clock-cand[" + depth + "] " + v.getClass().getName()
                    + " vis=" + v.getVisibility() + " a=" + v.getAlpha());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectClocks(g.getChildAt(i), depth + 1);
            }
        }
    }

    private void restoreClocks() {
        for (int i = 0; i < mClocks.size(); i++) {
            try {
                mClocks.get(i).setAlpha(mClocksAlpha.get(i).floatValue());
            } catch (Throwable ignored) {
            }
        }
        if (!mClocks.isEmpty()) log("clocks restored n=" + mClocks.size());
        mClocks.clear();
        mClocksAlpha.clear();
    }

    private View findViewByClassName(View v, String suffix, int depth) {
        if (v == null || depth > 12) return null;
        if (v.getClass().getName().endsWith(suffix)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findViewByClassName(g.getChildAt(i), suffix, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private boolean hasBouncerView(View v, int depth) {
        if (v == null || depth > 10) return false;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE) continue;
            String n = c.getClass().getName();
            if (n.endsWith("PinView") || n.endsWith("PasswordView")
                    || n.endsWith("PatternView") || n.contains("Bouncer")
                    || n.contains("KeyInput") || n.contains("NumPad")) {
                return true;
            }
            if (hasBouncerView(c, depth + 1)) return true;
        }
        return false;
    }

    /** 密码键盘界面：用模糊壁纸当背景（原生 bouncer 的观感） */
    private void showBouncerBg() {
        if (mView == null) return;
        mBouncerBg = true;
        try {
            if (mAnim != null) {
                try {
                    mAnim.cancel();
                } catch (Throwable ignored) {
                }
                mAnim = null;
            }
            mView.setBlur(80f);
            mView.setMaskAlpha(0.60f);
            mView.setScaleX(1f);
            mView.setScaleY(1f);
            mView.setAlpha(1f);
            mView.setVisibility(View.VISIBLE);
            mH.post(new Runnable() {
                @Override
                public void run() {
                    placeBelowPanel();
                }
            });
            hideAllClocks();
            log("bouncer bg on (blur 80)");
        } catch (Throwable t) {
            log("bouncer bg fail " + t);
        }
    }

    private void hideBouncerBg() {
        if (!mBouncerBg) return;
        mBouncerBg = false;
        try {
            if (mView != null) {
                mView.setBlur(0f);
                mView.setMaskAlpha(cfgMask);
                mView.setAlpha(0f);
                mView.setVisibility(View.GONE);
            }
            restoreClocks();
            log("bouncer bg off");
        } catch (Throwable ignored) {
        }
    }

    private void clearRootDim() {
        if (!mRootDimmed || mParent == null) return;
        mRootDimmed = false;
        try {
            mParent.setBackground(mRootDimBg);
            log("dim root off");
        } catch (Throwable ignored) {
        }
        mRootDimBg = null;
    }

    private void ensureScrimForKeyguard() {
        if (mAod || mIntervene) return;
        if (mScrimController == null) return;
        try {
            String st = readScrimState();
            boolean kd = st.indexOf("KEYGUARD") >= 0 || st.indexOf("BOUNCER") >= 0
                    || st.indexOf("OCCLUDED") >= 0;
            if (!kd) {
                mScrimHeal = 0;
                mScrimApplied = false;
                return;
            }
            if (mScrimApplied) return;
            View behind = null;
            try {
                Object o = XposedHelpers.getObjectField(mScrimController, "mScrimBehind");
                if (o instanceof View) behind = (View) o;
            } catch (Throwable ignored) {
            }
            mScrimApplied = true;
            boolean ok = callApplyState();
            if (!ok) restoreScrimAlphas();
            log("heal scrim: state=" + st + " behind="
                    + (behind == null ? "null" : String.valueOf(behind.getAlpha()))
                    + " applyState=" + ok);
            log("scrimview " + dumpScrim(behind));
        } catch (Throwable t) {
            log("heal scrim fail " + t);
        }
    }

    /**
     * 按当前 ScrimState 枚举自带的 alpha 值，直接写回三层 ScrimView。
     * 不能用 applyAndDispatchState()：它用的是 ScrimController 内部缓存的
     * mScrimBehindAlpha，而那个值在 AOD 期间已经被算成 0，重算只会再写一遍 0。
     */
    private void restoreScrimAlphas() {
        if (mScrimController == null) return;
        try {
            Object cur = null;
            try {
                cur = XposedHelpers.getObjectField(mScrimController, "mState");
            } catch (Throwable ignored) {
            }
            String st = cur == null ? "" : String.valueOf(cur);
            if (st.indexOf("AOD") >= 0) return;

            float ba = 0.5f, na = 0f, fa = 0f;
            boolean fromState = false;
            if (cur != null) {
                try {
                    ba = XposedHelpers.getFloatField(cur, "mBehindAlpha");
                    fromState = true;
                } catch (Throwable ignored) {
                }
                try {
                    na = XposedHelpers.getFloatField(cur, "mNotifAlpha");
                } catch (Throwable ignored) {
                }
                try {
                    fa = XposedHelpers.getFloatField(cur, "mFrontAlpha");
                } catch (Throwable ignored) {
                }
            }
            int bb = readNum(cur, "mBehindBlurRadius");
            int nb = readNum(cur, "mNotifBlurRadius");
            int fb = readNum(cur, "mFrontBlurRadius");

            setScrimAlpha("mScrimBehind", ba);
            setScrimAlpha("mNotificationsScrim", na);
            setScrimAlpha("mScrimInFront", fa);
            setScrimBlur("mScrimBehind", bb);
            setScrimBlur("mNotificationsScrim", nb);
            setScrimBlur("mScrimInFront", fb);
            if (ba > 0.01f) setScrimTint("mScrimBehind", 0xB3000000);
            if (na > 0.01f) setScrimTint("mNotificationsScrim", 0xB3000000);
            if (fa > 0.01f) setScrimTint("mScrimInFront", 0xB3000000);
            if (!mScrimRestored) {
                mScrimRestored = true;
                log("restore scrim " + st + " alpha=" + ba + "/" + na + "/" + fa
                        + " blur=" + bb + "/" + nb + "/" + fb
                        + (fromState ? "" : " (fallback)"));
            }
        } catch (Throwable t) {
            log("restore scrim fail " + t);
        }
    }

    /**
     * 让 ScrimController 按当前 mState 完整重算一遍。
     * 这一步不仅重算 alpha，还会重设 ScrimView 的 tint 颜色与模糊半径；
     * 只改 alpha 的话，颜色在 AOD 期间被改成透明的，恢复 alpha 也画不出东西。
     */
    private String dumpScrim(Object v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName()).append("( ");
        try {
            sb.append("vis=").append(((View) v).getVisibility())
                    .append(" w=").append(((View) v).getWidth())
                    .append(" h=").append(((View) v).getHeight())
                    .append(" a=").append(((View) v).getAlpha()).append(" ");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> c = v.getClass();
            int guard = 0;
            while (c != null && c != Object.class && guard++ < 6) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    String n = f.getName();
                    if (!(n.contains("lpha") || n.contains("int") || n.contains("lur")
                            || n.contains("rawable"))) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(v);
                        if (val instanceof android.graphics.drawable.Drawable) {
                            sb.append(n).append("=").append(val.getClass().getSimpleName());
                            try {
                                sb.append("[");
                                Class<?> dc = val.getClass();
                                int dg = 0;
                                while (dc != null && dc != Object.class && dg++ < 4) {
                                    for (java.lang.reflect.Field df : dc.getDeclaredFields()) {
                                        try {
                                            df.setAccessible(true);
                                            Object dv = df.get(val);
                                            if (dv instanceof int[]) {
                                                int[] arr = (int[]) dv;
                                                sb.append(df.getName()).append("=");
                                                for (int i = 0; i < Math.min(arr.length, 6); i++) {
                                                    sb.append(Integer.toHexString(arr[i])).append("|");
                                                }
                                            } else {
                                                sb.append(df.getName()).append("=").append(dv);
                                            }
                                            sb.append(",");
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    dc = dc.getSuperclass();
                                }
                                sb.append("]");
                            } catch (Throwable ignored) {
                            }
                            sb.append(" ");
                        } else {
                            sb.append(n).append("=").append(val).append(" ");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            sb.append("ERR");
        }
        return sb.append(")").toString();
    }

    private boolean callApplyState() {
        for (String m : new String[]{"applyState", "applyScrimState"}) {
            try {
                XposedHelpers.callMethod(mScrimController, m);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void setScrimTint(String field, int color) {
        try {
            Object o = XposedHelpers.getObjectField(mScrimController, field);
            if (o == null) return;
            XposedHelpers.callMethod(o, "setTint", color);
        } catch (Throwable ignored) {
        }
    }

    private int readNum(Object o, String field) {
        if (o == null) return 0;
        try {
            Object v = XposedHelpers.getObjectField(o, field);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void setScrimBlur(String field, int radius) {
        if (radius <= 0) return;
        try {
            Object o = XposedHelpers.getObjectField(mScrimController, field);
            if (o == null) return;
            XposedHelpers.callMethod(o, "setBlurRadius", radius);
        } catch (Throwable ignored) {
        }
    }

    private void setScrimAlpha(String field, float a) {
        try {
            Object o = XposedHelpers.getObjectField(mScrimController, field);
            if (o instanceof View) ((View) o).setAlpha(a);
        } catch (Throwable ignored) {
        }
    }

    private void applyScrimNow() {
        if (mIntervene && mScrimController != null) applyScrim(mScrimController);
    }

    private void applyScrim(Object ctrl) {
        try {
            if (!mScrimCancelled) {
                mScrimCancelled = true;
                try {
                    Object anim = XposedHelpers.getObjectField(ctrl, "mScrimAnimator");
                    if (anim instanceof android.animation.Animator) {
                        ((android.animation.Animator) anim).cancel();
                    }
                } catch (Throwable ignored) {
                }
            }
            int cleared = 0;
            StringBuilder info = new StringBuilder();
            for (String f : new String[]{"mScrimBehind", "mNotificationsScrim", "mScrimInFront"}) {
                try {
                    Object o = XposedHelpers.getObjectField(ctrl, f);
                    if (o instanceof View) {
                        View v = (View) o;
                        float a = v.getAlpha();
                        if (a > 0.001f) {
                            v.setAlpha(0f);
                            cleared++;
                        }
                        info.append(f).append("=").append(a).append(" ");
                    }
                } catch (Throwable t) {
                    info.append(f).append("=ERR ");
                }
            }
            if (cleared > 0) log("scrim cleared " + cleared + " [" + info.toString().trim() + "]");
        } catch (Throwable t) {
            log("applyScrim fail: " + t);
        }
    }

    /* ---------------- 姿态传感器（反扣关闭所有 AOD / 拿起自动亮屏） ---------------- */

    private final SensorEventListener mPostureSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null || event.values == null) return;
            int type = event.sensor.getType();
            long now = SystemClock.elapsedRealtime();

            if (type == Sensor.TYPE_PROXIMITY) {
                float dist = event.values[0];
                float maxRange = event.sensor.getMaximumRange();
                mProximityNear = (dist >= 0f && dist < Math.min(5f, maxRange));
                return;
            }

            if (type != Sensor.TYPE_ACCELEROMETER || event.values.length < 3) return;
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];

            if (!mGravityInit) {
                mGx = ax;
                mGy = ay;
                mGz = az;
                mGravityInit = true;
            } else {
                mGx = 0.76f * mGx + 0.24f * ax;
                mGy = 0.76f * mGy + 0.24f * ay;
                mGz = 0.76f * mGz + 0.24f * az;
            }

            if (!mBaseGravityInit) {
                mBaseGx = mGx;
                mBaseGy = mGy;
                mBaseGz = mGz;
                mBaseGravityInit = true;
            }

            float normA = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            float linearAccel = Math.abs(normA - 9.81f);
            if (linearAccel > 0.55f) {
                mLastMotionTs = now;
            }

            // 1) 反扣检测：屏幕朝下水平放置时关闭所有 AOD（全屏壁纸 + 系统时钟），翻转朝上时恢复
            if (cfgFlipOff && (!isScreenOn() || mAod || mFaceDownPaused)) {
                double horiz = Math.hypot(mGx, mGy);
                boolean isFlatFaceDown = (mGz < -7.2f) && (horiz < 4.6f);
                boolean isFaceUpAgain = (mGz > -4.2f) || (horiz > 5.8f);

                if (isFlatFaceDown) {
                    mFaceUpSince = 0L;
                    if (!mFaceDownPaused) {
                        if (mFaceDownSince == 0L) {
                            mFaceDownSince = now;
                        } else if (now - mFaceDownSince >= 550L) {
                            enterFaceDownAodOff();
                        }
                    }
                } else {
                    mFaceDownSince = 0L;
                    if (mFaceDownPaused && isFaceUpAgain) {
                        if (mFaceUpSince == 0L) {
                            mFaceUpSince = now;
                        } else if (now - mFaceUpSince >= 240L) {
                            exitFaceDownAodResume();
                        }
                    } else {
                        mFaceUpSince = 0L;
                    }
                }
            }

            // 2) 姿态检测：拿起手机并抬起朝向用户时自动亮屏
            if (cfgLiftWake && !isScreenOn() && !mProximityNear && (now - mSensorStartElapsed >= 1400L)) {
                boolean viewingTilt = (mGz > 1.2f && mGz < 9.0f)
                        && (mGy > 2.75f || (Math.abs(mGx) > 3.4f && mGy > -1.0f));
                float deltaPitch = mGy - mBaseGy;
                float deltaVec = (float) Math.sqrt(
                        (mGx - mBaseGx) * (mGx - mBaseGx)
                                + (mGy - mBaseGy) * (mGy - mBaseGy)
                                + (mGz - mBaseGz) * (mGz - mBaseGz));

                if (viewingTilt && (deltaPitch > 2.15f || deltaVec > 3.1f)
                        && (now - mLastMotionTs < 950L)
                        && (now - mLastLiftWakeTs > 2500L)) {
                    mLastLiftWakeTs = now;
                    mBaseGx = mGx;
                    mBaseGy = mGy;
                    mBaseGz = mGz;
                    wakeUpScreen("posture_lift");
                    return;
                }

                // 当手机处于平放、反扣或静止低倾角状态时缓慢更新基准姿态
                if (linearAccel < 0.40f && (mGz > 7.6f || mGz < -6.5f || mGy < 1.8f)) {
                    mBaseGx = 0.85f * mBaseGx + 0.15f * mGx;
                    mBaseGy = 0.85f * mBaseGy + 0.15f * mGy;
                    mBaseGz = 0.85f * mBaseGz + 0.15f * mGz;
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final TriggerEventListener mPickupTriggerListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            mPickupTriggerRegistered = false;
            long now = SystemClock.elapsedRealtime();
            if (cfgLiftWake && !isScreenOn() && !mProximityNear && !mFaceDownPaused
                    && (now - mSensorStartElapsed >= 1400L)) {
                wakeUpScreen("pickup_sensor");
            } else {
                updatePostureSensors();
            }
        }
    };

    private void updatePostureSensors() {
        if (mCtx == null) return;
        boolean need = (!isScreenOn() || mAod || mFaceDownPaused) && (cfgFlipOff || cfgLiftWake);
        try {
            SensorManager sm = (SensorManager) mCtx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;

            if (need && !mSensorRegistered) {
                Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
                if (accel == null) accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accel != null) {
                    mGravityInit = false;
                    mBaseGravityInit = false;
                    mProximityNear = false;
                    mFaceDownSince = 0L;
                    mFaceUpSince = 0L;
                    mSensorStartElapsed = SystemClock.elapsedRealtime();
                    sm.registerListener(mPostureSensorListener, accel,
                            SensorManager.SENSOR_DELAY_NORMAL, mH);
                    mSensorRegistered = true;
                }
                Sensor prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
                if (prox == null) prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                if (prox != null) {
                    sm.registerListener(mPostureSensorListener, prox,
                            SensorManager.SENSOR_DELAY_NORMAL, mH);
                }
                log("posture sensors registered (flipOff=" + cfgFlipOff + " liftWake=" + cfgLiftWake + ")");
            } else if (!need && mSensorRegistered) {
                sm.unregisterListener(mPostureSensorListener);
                mSensorRegistered = false;
                if (mPickupTriggerRegistered && mPickupSensor != null) {
                    try {
                        sm.cancelTriggerSensor(mPickupTriggerListener, mPickupSensor);
                    } catch (Throwable ignored) {
                    }
                    mPickupTriggerRegistered = false;
                }
                releaseFaceDownWakeLock();
                log("posture sensors unregistered");
            }

            // Hardware Wake-up Pick-Up Gesture Sensor (Type 25)
            if (need && cfgLiftWake && !mPickupTriggerRegistered) {
                if (mPickupSensor == null) {
                    mPickupSensor = sm.getDefaultSensor(25); // TYPE_PICK_UP_GESTURE
                }
                if (mPickupSensor != null) {
                    mPickupTriggerRegistered = sm.requestTriggerSensor(mPickupTriggerListener, mPickupSensor);
                }
            } else if ((!need || !cfgLiftWake) && mPickupTriggerRegistered && mPickupSensor != null) {
                try {
                    sm.cancelTriggerSensor(mPickupTriggerListener, mPickupSensor);
                } catch (Throwable ignored) {
                }
                mPickupTriggerRegistered = false;
            }
        } catch (Throwable t) {
            log("updatePostureSensors fail: " + t);
        }
    }

    private void acquireFaceDownWakeLock() {
        try {
            if (mCtx == null) return;
            if (mFaceDownWakeLock == null) {
                PowerManager pm = (PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    mFaceDownWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SystemUI:FullAODFlip");
                    mFaceDownWakeLock.setReferenceCounted(false);
                }
            }
            if (mFaceDownWakeLock != null && !mFaceDownWakeLock.isHeld()) {
                mFaceDownWakeLock.acquire(2 * 3600 * 1000L); // 最长 2 小时安全兜底
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseFaceDownWakeLock() {
        try {
            if (mFaceDownWakeLock != null && mFaceDownWakeLock.isHeld()) {
                mFaceDownWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void enterFaceDownAodOff() {
        if (mFaceDownPaused) return;
        mFaceDownPaused = true;
        acquireFaceDownWakeLock();
        log("posture: face-down -> turn OFF all AOD (clock + wallpaper)");
        try {
            if (mIntervene) {
                mIntervene = false;
                mH.removeCallbacks(mShiftTask);
                restoreOpaqueBackgrounds();
                stopScrimKeeper();
                restoreScrimAlphas();
                animateTo(0f, cfgAnimOut);
            } else if (mView != null) {
                mView.setAlpha(0f);
                mView.setVisibility(View.GONE);
            }
            hideAllClocks();
            requestAodPaused();
        } catch (Throwable t) {
            log("enterFaceDownAodOff fail: " + t);
        }
    }

    private void exitFaceDownAodResume() {
        if (!mFaceDownPaused) return;
        mFaceDownPaused = false;
        releaseFaceDownWakeLock();
        log("posture: face-up -> resume AOD");
        try {
            restoreClocks();
            requestAodResume();
            if (!isScreenOn()) {
                updateNightActive();
                boolean shouldIntervene = cfgEnabled && !mNightActive && !mTimedOut;
                if (shouldIntervene && mView != null) {
                    mAod = true;
                    mIntervene = true;
                    mScrimCancelled = false;
                    mView.setMaskAlpha(cfgMask);
                    mView.setVisibility(View.VISIBLE);
                    placeBelowPanel();
                    clearOpaqueBackgrounds();
                    startScrimKeeper();
                    applyScrimNow();
                    animateTo(1f, cfgAnimIn);
                    kickShift();
                }
            }
        } catch (Throwable t) {
            log("exitFaceDownAodResume fail: " + t);
        }
    }

    private void wakeUpScreen(String reason) {
        if (mCtx == null) return;
        try {
            if (mFaceDownPaused) {
                mFaceDownPaused = false;
                releaseFaceDownWakeLock();
                restoreClocks();
            }
            PowerManager pm = (PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return;
            log("posture: wakeUpScreen reason=" + reason);
            boolean woke = false;
            try {
                XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), 2, "fullaod:" + reason);
                woke = true;
            } catch (Throwable ignored) {
                try {
                    XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis(), "fullaod:" + reason);
                    woke = true;
                } catch (Throwable ignored2) {
                    try {
                        XposedHelpers.callMethod(pm, "wakeUp", SystemClock.uptimeMillis());
                        woke = true;
                    } catch (Throwable ignored3) {
                    }
                }
            }
            if (!woke) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "SystemUI:FullAODLiftWake");
                wl.acquire(120L);
            }
        } catch (Throwable t) {
            log("wakeUpScreen fail: " + t);
        }
    }

    /* ---------------- 亮度 ---------------- */

    public float onBrightness(float in) {
        if (mFaceDownPaused) return 0f;
        if (!mAod || !mIntervene) return in;
        float max = in <= 1.0f ? 1.0f : 255.0f;
        float out = in * cfgBright;
        if (out > max) out = max;
        if (out < 0f) out = 0f;
        return out;
    }

    public int onBrightnessInt(int in) {
        if (mFaceDownPaused) return 0;
        if (!mAod || !mIntervene) return in;
        int out = Math.round(in * cfgBright);
        if (out > 255) out = 255;
        if (out < 0) out = 0;
        return out;
    }

    /* ---------------- 日志 ---------------- */

    public void log(String s) {
        XposedBridge.log("FullAOD: " + s);
        try {
            if (mCtx == null) return;
            File f = new File(mCtx.getFilesDir(), "fullaod.log");
            if (f.length() > 300000L) f.delete();
            FileOutputStream fo = new FileOutputStream(f, true);
            String line = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date()) + " " + s + "\n";
            fo.write(line.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }
    }
}