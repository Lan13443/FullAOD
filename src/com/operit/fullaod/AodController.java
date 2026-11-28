package com.operit.fullaod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.json.JSONObject;

public class AodController {

    public static final String TAG = "FullAOD";
    private static final String CONFIG_URI = "content://com.operit.fullaod.config";
    private static final String SECURE_CFG_KEY = "fullaod_cfg";
    private static final int SENSOR_TYPE_TILT_DETECTOR = 22;
    private static final int SENSOR_TYPE_PICK_UP_GESTURE = 25;
    private static final long SHIFT_INTERVAL_MS = 15000L;
    private static final long BOUNCER_TICK_MS = 250L;
    private static final long SCRIM_KEEPER_INTERVAL_MS = 120L;
    private static final int SCRIM_KEEPER_MAX_TICKS = 10;
    private static final long BURST_WAKELOCK_MAX_MS = 3000L;
    private static final float LIGHT_DEADBAND_NORM = 0.045f;
    private static final long LIGHT_DEBOUNCE_BRIGHTEN_MS = 180L;
    private static final long LIGHT_DEBOUNCE_DARKEN_MS = 1200L;
    private static final long LIGHT_WAKELOCK_MAX_MS = 2500L;
    private static final long LIGHT_STALE_REREGISTER_MS = 45000L;
    private static final long LOG_MAX_BYTES = 128L * 1024L;
    private static final int LOG_KEEP_TAIL_BYTES = 32 * 1024;

    private static AodController sInst;

    public static synchronized AodController get() {
        if (sInst == null) {
            sInst = new AodController();
        }
        return sInst;
    }

    private final Handler mH = new Handler(Looper.getMainLooper());
    private HandlerThread mBgThread;
    private Handler mBgH;

    private Context mCtx;
    private ViewGroup mRoot;
    private WallpaperAodView mView;
    private Bitmap mBitmap;
    private volatile boolean mWpLoading = false;
    private long mLastWpLoadTs = 0L;

    private Object mDozeMachine;
    private Object mScrimController;
    private Object mBrightnessForwarder;
    private Object mDozeService;
    private int mLastRawBrightnessInt = -1;
    private float mLastRawBrightnessFloat = -1f;
    private int mLastAppliedBrightnessInt = -1;
    private int mLastLogRaw = -1;
    private int mLastLogOut = -1;
    private boolean mPushingBrightness = false;
    private boolean mInApplyState = false;
    private boolean mScrimTouched = false;
    private float mSavedBehindViewAlpha = -1f;
    private float mSavedFrontViewAlpha = -1f;

    // Config fields (preserving all 20 FullAOD v1.4 JSON keys)
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
    private float mBaseWallpaperScale = 1.0f;
    private boolean cfgTop = false;
    private boolean cfgShade = false;
    private float cfgShadeMask = 0.8f;
    private boolean cfgTimeoutOn = false;
    private int cfgTimeoutMin = 30;
    private int cfgTimeoutMode = 0;
    private int cfgNightMode = 0;
    private boolean cfgNightSmart = false;
    private boolean cfgFlipOff = true;
    private boolean cfgLiftWake = true;
    private boolean cfgAutoBright = true;
    private int cfgAutoBrightMin = 4;
    private int cfgAutoBrightMax = 75;

    private boolean mCfgLoaded = false;
    private long mLastCfgReadTs = 0L;
    private boolean mCfgObserverRegistered = false;
    private boolean mReceiversRegistered = false;

    // State fields
    private boolean mAod = false;
    private boolean mDozing = false;
    private String mDozeState = "UNINITIALIZED";
    private long mAodEnterTs = 0L;
    private boolean mAodTimedOut = false;
    private boolean mNightActive = false;
    private int mNightClearedSession = -1;
    private boolean mBouncerBg = false;
    private long mBouncerLast = 0L;
    private boolean mClocksHidden = false;
    private boolean mShowingTarget = false;
    private float mTargetAlpha = 0f;
    private ValueAnimator mAnim;

    // Clocks & opaque backgrounds saved for Bouncer/Shade
    private final ArrayList<View> mClocks = new ArrayList<>();
    private final ArrayList<Float> mClocksAlpha = new ArrayList<>();
    private final ArrayList<View> mClearedViews = new ArrayList<>();
    private final ArrayList<Drawable> mClearedBgs = new ArrayList<>();
    private View mClockView;
    private float mClockOldAlpha = 1f;

    // Pixel shift
    private final Random mRand = new Random();
    private long mLastShiftTs = 0L;

    // Tick & ScrimKeeper
    private boolean mTickRunning = false;
    private boolean mScrimKeeperRunning = false;
    private int mScrimKeeperTicks = 0;

    // Posture sensors & ambient light sensor
    private SensorManager mSm;
    private Sensor mProxSensor;
    private Sensor mAccelSensor;
    private Sensor mTiltSensor;
    private Sensor mPickupSensor;
    private Sensor mLightSensor;
    private boolean mSensorsProbed = false;
    private boolean mProxRegistered = false;
    private boolean mTiltRegistered = false;
    private boolean mAccelRegistered = false;
    private boolean mPickupTriggerRegistered = false;
    private boolean mLightRegistered = false;
    private boolean mInitialBurstDoneForOff = false;

    private boolean mHasAppliedLight = false;
    private boolean mForceImmediateAnimatedLight = false;
    private float mRawLightLux = -1f;
    private float mAppliedLightLux = -1f;
    private float mAppliedLightNorm = -1f;
    private long mLastLightEventTs = 0L;
    private boolean mPendingLuxActive = false;
    private float mPendingLux = -1f;
    private int mPendingLuxDir = 0;
    private long mPendingLuxDueTs = 0L;
    private int mAutoBrightTargetInt = -1;
    private float mAutoBrightTargetFloat = -1f;
    private float mCurrentAnimHwFloat = -1f;
    private float mCurrentIdealMask = -1f;
    private float mCurrentAutoMask = -1f;
    private ValueAnimator mAutoBrightAnim;

    private boolean mProximityNear = false;
    private boolean mFaceDownPaused = false;
    private long mFaceDownSince = 0L;
    private long mFaceUpSince = 0L;
    private boolean mGravityInit = false;
    private float mGx = 0f;
    private float mGy = 0f;
    private float mGz = 9.8f;
    private boolean mBaseGravityInit = false;
    private float mBaseGx = 0f;
    private float mBaseGy = 0f;
    private float mBaseGz = 9.8f;
    private long mSensorStartElapsed = 0L;
    private long mAccelStopAtElapsed = 0L;
    private long mLastMotionTs = 0L;
    private long mLastLiftWakeTs = 0L;

    private PowerManager mPm;
    private PowerManager.WakeLock mFaceDownWakeLock;
    private PowerManager.WakeLock mAutoLightWakeLock;
    private Method mApplyStateMethod;
    private boolean mApplyStateMethodResolved = false;
    private Method mWakeUpMethod;
    private int mWakeUpArgCount = 0;

    private File mLogFile;
    private final Object mLogLock = new Object();

    private AodController() {
        ensureBgThread();
    }

    private synchronized void ensureBgThread() {
        if (mBgThread == null || !mBgThread.isAlive()) {
            mBgThread = new HandlerThread("FullAOD-Worker", Process.THREAD_PRIORITY_BACKGROUND);
            mBgThread.start();
            mBgH = new Handler(mBgThread.getLooper());
        }
    }

    public void log(final String msg) {
        if (msg == null) return;
        XposedBridge.log(TAG + ": " + msg);
        ensureBgThread();
        final long nowMs = System.currentTimeMillis();
        mBgH.post(() -> writeLogAsync(nowMs, msg));
    }

    private void writeLogAsync(long nowMs, String msg) {
        File lf = mLogFile;
        if (lf == null && mCtx != null) {
            try {
                File dir = null;
                if (Build.VERSION.SDK_INT >= 24) {
                    Context deCtx = mCtx.createDeviceProtectedStorageContext();
                    if (deCtx != null) dir = deCtx.getFilesDir();
                }
                if (dir == null) dir = mCtx.getFilesDir();
                if (dir != null) {
                    lf = new File(dir, "fullaod.log");
                    mLogFile = lf;
                }
            } catch (Throwable ignored) {
            }
        }
        if (lf == null) return;

        synchronized (mLogLock) {
            try {
                if (lf.exists() && lf.length() > LOG_MAX_BYTES) {
                    rotateLogFile(lf);
                }
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(nowMs));
                String line = ts + " " + msg + "\n";
                try (FileOutputStream fos = new FileOutputStream(lf, true)) {
                    fos.write(line.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void rotateLogFile(File lf) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(lf.toPath());
            int start = Math.max(0, data.length - LOG_KEEP_TAIL_BYTES);
            while (start < data.length && data[start] != '\n') start++;
            if (start < data.length) start++;
            try (FileOutputStream fos = new FileOutputStream(lf, false)) {
                fos.write("--- log rotated ---\n".getBytes("UTF-8"));
                fos.write(data, start, data.length - start);
            }
        } catch (Throwable ignored) {
            try {
                new FileOutputStream(lf, false).close();
            } catch (Throwable ignored2) {
            }
        }
    }

    public void setContext(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        mCtx = app != null ? app : ctx;
        if (mPm == null) {
            mPm = (PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
        }
        if (mSm == null) {
            mSm = (SensorManager) mCtx.getSystemService(Context.SENSOR_SERVICE);
        }
        registerConfigObserver();
        registerReceivers();
    }

    public void setDozeMachine(Object dm) {
        if (dm != null) {
            mDozeMachine = dm;
        }
    }

    public void attachShadeView(final ViewGroup root, final Context ctx) {
        if (root == null) return;
        runOnMain(() -> attachShadeViewMain(root, ctx));
    }

    private void attachShadeViewMain(ViewGroup root, Context ctx) {
        Context c = ctx != null ? ctx : root.getContext();
        setContext(c);
        if (mRoot == root && mView != null && mView.getParent() == root) {
            return;
        }

        if (mView != null && mView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mView.getParent()).removeView(mView);
        }
        mRoot = root;
        loadBaseWallpaperScale();
        root.post(this::ensureLockscreenCenterOffset);
        if (mView == null) {
            mView = new WallpaperAodView(mCtx);
            mView.setAlpha(0f);
            mView.setVisibility(View.GONE);
        }
        if (mBitmap != null && !mBitmap.isRecycled()) {
            mView.setBitmap(mBitmap);
        }

        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(mView, 0, lp);
        placeBelowPanel();
        refreshConfig(true);
        loadWallpaper(0);
        updatePostureSensors();
        log("attachShadeView ok root=" + root.getClass().getSimpleName() + " children=" + root.getChildCount());
    }

    private void registerConfigObserver() {
        if (mCfgObserverRegistered || mCtx == null) return;
        mCfgObserverRegistered = true;
        try {
            ContentObserver obs = new ContentObserver(mH) {
                @Override
                public void onChange(boolean selfChange) {
                    onConfigUpdated();
                }
            };
            Uri secureUri = Settings.Secure.getUriFor(SECURE_CFG_KEY);
            if (secureUri != null) {
                mCtx.getContentResolver().registerContentObserver(secureUri, false, obs);
            }
            mCtx.getContentResolver().registerContentObserver(Uri.parse(CONFIG_URI), true, obs);
        } catch (Throwable t) {
            log("registerConfigObserver err: " + t);
        }
    }

    private void registerReceivers() {
        if (mReceiversRegistered || mCtx == null) return;
        mReceiversRegistered = true;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_WALLPAPER_CHANGED);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_TIME_TICK);
            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    final String act = intent.getAction();
                    if (Intent.ACTION_WALLPAPER_CHANGED.equals(act)) {
                        log("ACTION_WALLPAPER_CHANGED received");
                        loadBaseWallpaperScale();
                        ensureLockscreenCenterOffset();
                        mH.postDelayed(AodController.this::ensureLockscreenCenterOffset, 200L);
                        loadWallpaper(1);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                        onScreenOffEvent();
                    } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                        ensureLockscreenCenterOffset();
                        onScreenOnEvent();
                    } else if (Intent.ACTION_USER_PRESENT.equals(act)) {
                        onUserPresentEvent();
                    } else if (Intent.ACTION_TIME_TICK.equals(act)) {
                        onTimeTickEvent();
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    mCtx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
                    return;
                } catch (Throwable ignored) {
                }
            }
            mCtx.registerReceiver(rx, f);
        } catch (Throwable t) {
            log("registerReceivers err: " + t);
        }
    }

    private void onConfigUpdated() {
        loadBaseWallpaperScale();
        refreshConfig(true);
        updateNightActive();
        if (mAod) {
            applyAod(true);
            if (isWallpaperAodVisible() && cfgAutoBright && mHasAppliedLight) {
                commitAutoBrightness(mAppliedLightLux, false);
            } else if (isWallpaperAodVisible() && !cfgAutoBright && mView != null) {
                cancelAutoBrightAnim();
                mCurrentAutoMask = -1f;
                mCurrentIdealMask = -1f;
                mCurrentAnimHwFloat = -1f;
                mForceImmediateAnimatedLight = false;
                releaseAutoLightWakeLock();
                mView.setMaskAlpha(cfgMask);
                syncDozeBrightness(true);
            }
        } else if (mBouncerBg) {
            if (!cfgEnabled) {
                hideBouncerBg();
            } else if (mView != null) {
                applyViewScale(getTargetWallpaperScale());
                boolean isShade = cfgShade && isShadeOverlayActive(readScrimState(mScrimController));
                mView.setMaskAlpha(isShade ? cfgShadeMask : cfgMask);
            }
        }
        updatePostureSensors();
        updateLightSensor();
    }

    public void refreshConfig() {
        refreshConfig(false);
    }

    private void refreshConfig(boolean force) {
        if (mCtx == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && mCfgLoaded && (now - mLastCfgReadTs) < 500L) {
            return;
        }
        mLastCfgReadTs = now;

        String jsonStr = null;
        try {
            jsonStr = Settings.Secure.getString(mCtx.getContentResolver(), SECURE_CFG_KEY);
        } catch (Throwable ignored) {
        }
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            try {
                Bundle res = mCtx.getContentResolver().call(Uri.parse(CONFIG_URI), "get", null, null);
                if (res != null) {
                    jsonStr = res.getString("json");
                }
            } catch (Throwable ignored) {
            }
        }
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            mCfgLoaded = true;
            return;
        }

        try {
            JSONObject o = new JSONObject(jsonStr);
            cfgEnabled = o.optBoolean("enabled", true);
            cfgMask = clampFloat((float) o.optDouble("mask", 0.65), 0f, 1f);
            cfgBright = clampFloat((float) o.optDouble("bright", 2.0), 0.1f, 5.0f);
            cfgShift = o.optBoolean("shift", true);
            cfgNight = o.optBoolean("night", true);
            cfgNS = clampInt(o.optInt("nightStart", 23), 0, 23);
            cfgNE = clampInt(o.optInt("nightEnd", 7), 0, 23);
            cfgAnimIn = clampInt(o.optInt("animIn", 550), 0, 5000);
            cfgAnimOut = clampInt(o.optInt("animOut", 350), 0, 5000);
            cfgScale = clampFloat((float) o.optDouble("scale", 1.03), 0.5f, 2.0f);
            cfgTop = o.optBoolean("top", false);
            cfgShade = o.optBoolean("shade", false);
            cfgShadeMask = clampFloat((float) o.optDouble("shadeMask", 0.8), 0f, 1f);
            cfgTimeoutOn = o.optBoolean("aodTimeoutOn", false);
            cfgTimeoutMin = clampInt(o.optInt("aodTimeoutMin", 30), 1, 1440);
            cfgTimeoutMode = clampInt(o.optInt("aodTimeoutMode", 0), 0, 1);
            cfgNightMode = clampInt(o.optInt("nightMode", 0), 0, 1);
            cfgNightSmart = o.optBoolean("nightSmart", false);
            cfgFlipOff = o.optBoolean("flipOff", true);
            cfgLiftWake = o.optBoolean("liftWake", true);
            cfgAutoBright = o.optBoolean("autoBright", true);
            cfgAutoBrightMin = clampInt(o.optInt("autoBrightMin", 4), 1, 50);
            cfgAutoBrightMax = clampInt(o.optInt("autoBrightMax", 75), 20, 100);
            if (cfgAutoBrightMax < cfgAutoBrightMin) {
                cfgAutoBrightMax = cfgAutoBrightMin;
            }
            mCfgLoaded = true;
        } catch (Throwable t) {
            log("parse cfg err: " + t);
        }
    }

    public void setBrightnessForwarder(Object fwd) {
        if (fwd != null) mBrightnessForwarder = fwd;
    }

    public void setDozeService(Object svc) {
        if (svc != null) mDozeService = svc;
    }

    private boolean isAodBrightnessEligible() {
        if (!cfgEnabled || mAodTimedOut || mFaceDownPaused) {
            return false;
        }
        updateNightActive();
        if (mNightActive) {
            return false;
        }
        if (mAod) {
            return true;
        }
        if (mDozeMachine != null) {
            try {
                Object st = XposedHelpers.callMethod(mDozeMachine, "getState");
                String s = String.valueOf(st);
                if ("DOZE_AOD".equals(s) || "DOZE_AOD_DOCKED".equals(s) || "DOZE_AOD_PAUSING".equals(s)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public int onBrightnessInt(int orig) {
        return onBrightnessInt(orig, false);
    }

    public int onBrightnessInt(int orig, boolean fromDream) {
        if (mPushingBrightness || orig <= 0) {
            return orig;
        }
        if (fromDream && mBrightnessForwarder != null) {
            return orig;
        }
        mLastRawBrightnessInt = orig;
        int out = orig;
        if (isAodBrightnessEligible()) {
            if (cfgAutoBright) {
                if (mAutoBrightAnim != null && mCurrentAnimHwFloat >= 1f) {
                    out = clampInt(Math.round(mCurrentAnimHwFloat), 1, 255);
                } else if (mAutoBrightTargetInt > 0) {
                    out = mAutoBrightTargetInt;
                }
            } else if (Math.abs(cfgBright - 1.0f) >= 0.01f) {
                out = clampInt(Math.round(orig * cfgBright), 1, 255);
            }
        }
        mLastAppliedBrightnessInt = out;
        if (orig != mLastLogRaw || out != mLastLogOut) {
            mLastLogRaw = orig;
            mLastLogOut = out;
            log("brightness: raw=" + orig + " -> out=" + out
                    + (cfgAutoBright ? " (auto lux=" + (mHasAppliedLight ? AmbientLightCurve.formatLux(mAppliedLightLux) : "pending") + ")"
                    : " (cfgBright=" + cfgBright + ")"));
        }
        return out;
    }

    private void pushDozeBrightnessInt(int targetInt) {
        int clamped = clampInt(targetInt, 1, 255);
        if (clamped == mLastAppliedBrightnessInt) return;
        Object host = mBrightnessForwarder != null ? mBrightnessForwarder : mDozeService;
        if (host == null) return;
        mPushingBrightness = true;
        try {
            if (mLastRawBrightnessFloat > 0f && mLastRawBrightnessInt <= 0) {
                try {
                    XposedHelpers.callMethod(host, "setDozeScreenBrightnessFloat",
                            clampFloat(clamped / 255f, 0.004f, 1.0f));
                    mLastAppliedBrightnessInt = clamped;
                    return;
                } catch (Throwable ignored) {
                }
            }
            XposedHelpers.callMethod(host, "setDozeScreenBrightness", clamped);
            mLastAppliedBrightnessInt = clamped;
        } catch (Throwable t1) {
            try {
                XposedHelpers.callMethod(host, "setDozeScreenBrightnessFloat",
                        clampFloat(clamped / 255f, 0.004f, 1.0f));
                mLastAppliedBrightnessInt = clamped;
            } catch (Throwable t2) {
                log("brightness push err: " + t1);
            }
        } finally {
            mPushingBrightness = false;
        }
    }

    private void syncDozeBrightness(boolean scaled) {
        if (!scaled) {
            cancelAutoBrightAnim();
            if (mLastRawBrightnessInt > 0 && mLastRawBrightnessInt != mLastAppliedBrightnessInt) {
                pushDozeBrightnessInt(mLastRawBrightnessInt);
                log("brightness restore: raw=" + mLastRawBrightnessInt);
            } else if (mLastRawBrightnessFloat > 0f && mLastRawBrightnessInt <= 0) {
                Object host = mBrightnessForwarder != null ? mBrightnessForwarder : mDozeService;
                if (host != null) {
                    mPushingBrightness = true;
                    try {
                        XposedHelpers.callMethod(host, "setDozeScreenBrightnessFloat", mLastRawBrightnessFloat);
                        mLastAppliedBrightnessInt = -1;
                    } catch (Throwable ignored) {
                    } finally {
                        mPushingBrightness = false;
                    }
                }
            }
            return;
        }
        if (cfgAutoBright) {
            if (mAutoBrightAnim == null && mAutoBrightTargetInt > 0 && isAodBrightnessEligible()) {
                pushDozeBrightnessInt(mAutoBrightTargetInt);
            }
            return;
        }
        if (mLastRawBrightnessInt <= 0) return;
        int target = (Math.abs(cfgBright - 1.0f) >= 0.01f)
                ? clampInt(Math.round(mLastRawBrightnessInt * cfgBright), 1, 255)
                : mLastRawBrightnessInt;
        if (target != mLastAppliedBrightnessInt) {
            pushDozeBrightnessInt(target);
            log("brightness sync: raw=" + mLastRawBrightnessInt + " -> target=" + target);
        }
    }

    public float onBrightness(float orig) {
        return onBrightness(orig, false);
    }

    public float onBrightness(float orig, boolean fromDream) {
        if (mPushingBrightness || Float.isNaN(orig) || orig <= 0f) {
            return orig;
        }
        if (fromDream && mBrightnessForwarder != null) {
            return orig;
        }
        if (orig <= 1.0f) {
            mLastRawBrightnessFloat = orig;
        }
        if (!isAodBrightnessEligible()) {
            return orig;
        }
        if (cfgAutoBright) {
            if (mAutoBrightAnim != null && mCurrentAnimHwFloat >= 1f) {
                int animInt = clampInt(Math.round(mCurrentAnimHwFloat), 1, 255);
                mLastAppliedBrightnessInt = animInt;
                return (orig <= 1.0f) ? clampFloat(mCurrentAnimHwFloat / 255f, 0.004f, 1.0f) : (float) animInt;
            }
            if (mAutoBrightTargetInt > 0) {
                mLastAppliedBrightnessInt = mAutoBrightTargetInt;
                return (orig <= 1.0f) ? mAutoBrightTargetFloat : (float) mAutoBrightTargetInt;
            }
            return orig;
        }
        if (Math.abs(cfgBright - 1.0f) < 0.01f) {
            return orig;
        }
        if (orig <= 1.0f) {
            return clampFloat(orig * cfgBright, 0.004f, 1.0f);
        }
        return clampFloat(orig * cfgBright, 1.0f, 255.0f);
    }

    public void onDozeState(final String rawState) {
        if (rawState == null) return;
        final String s = rawState.trim();
        runOnMain(() -> handleDozeStateMain(s));
    }

    private void handleDozeStateMain(String s) {
        refreshConfig(false);
        String prev = mDozeState;
        mDozeState = s;

        boolean wasAod = mAod;
        // Treat DOZE_AOD / DOZE_AOD_DOCKED / transient DOZE_AOD_PAUSING as visible AOD;
        // DOZE_AOD_PAUSED / DOZE / FINISH turn AOD wallpaper off.
        boolean isAod = "DOZE_AOD".equals(s) || "DOZE_AOD_DOCKED".equals(s) || "DOZE_AOD_PAUSING".equals(s);
        boolean isDozing = isAod
                || "DOZE".equals(s)
                || "DOZE_AOD_PAUSED".equals(s)
                || "DOZE_REQUEST_PULSE".equals(s)
                || "DOZE_PULSING".equals(s)
                || "DOZE_PULSING_BRIGHT".equals(s)
                || "DOZE_PULSE_DONE".equals(s);

        mDozing = isDozing;
        mAod = isAod;

        if ("INITIALIZED".equals(s) || "FINISH".equals(s) || "UNINITIALIZED".equals(s)) {
            mAodTimedOut = false;
            mFaceDownPaused = false;
            mFaceDownSince = 0L;
            mFaceUpSince = 0L;
            mInitialBurstDoneForOff = false;
            if ("FINISH".equals(s) || "UNINITIALIZED".equals(s)) {
                mLastRawBrightnessInt = -1;
                mLastRawBrightnessFloat = -1f;
                mLastAppliedBrightnessInt = -1;
                mLastLogRaw = -1;
                mLastLogOut = -1;
                mHasAppliedLight = false;
                mForceImmediateAnimatedLight = false;
                mAppliedLightLux = -1f;
                mAppliedLightNorm = -1f;
                mAutoBrightTargetInt = -1;
                mAutoBrightTargetFloat = -1f;
                mCurrentAnimHwFloat = -1f;
                mCurrentIdealMask = -1f;
                mCurrentAutoMask = -1f;
                mPendingLuxActive = false;
                mH.removeCallbacks(mLightDebounceRun);
                cancelAutoBrightAnim();
                releaseAutoLightWakeLock();
            }
        }

        if (!wasAod && isAod) {
            mAodEnterTs = SystemClock.elapsedRealtime();
            mAodTimedOut = false;
            if (!mFaceDownPaused) {
                ensureWallpaperLoaded();
            }
        }

        if (!s.equals(prev)) {
            log("dozeState: " + prev + " -> " + s
                    + " faceDownPaused=" + mFaceDownPaused
                    + " night=" + mNightActive
                    + " timeout=" + mAodTimedOut);
        }

        updateNightActive();
        checkAodTimeout();
        if (wasAod && isAod) {
            // Transient transitions such as DOZE_AOD <-> DOZE_AOD_PAUSING should not re-trigger zoom/diag
            if (isWallpaperAodVisible()) {
                applyScrimNow();
            } else {
                applyAod(false);
            }
        } else {
            applyAod(false);
        }
        updatePostureSensors();
        updateLightSensor();
    }

    private void onScreenOffEvent() {
        mInitialBurstDoneForOff = false;
        updatePostureSensors();
    }

    private void onScreenOnEvent() {
        mFaceDownPaused = false;
        mFaceDownSince = 0L;
        mFaceUpSince = 0L;
        mInitialBurstDoneForOff = false;
        updatePostureSensors();
        updateLightSensor();
    }

    private void onUserPresentEvent() {
        mFaceDownPaused = false;
        mFaceDownSince = 0L;
        mFaceUpSince = 0L;
        mAodTimedOut = false;
        mInitialBurstDoneForOff = false;

        if (cfgNight && cfgNightSmart && isNight()) {
            Calendar cal = Calendar.getInstance();
            if (isInMorningTail(cal, cfgNS, cfgNE)) {
                int sess = nightSessionKey(cal);
                if (mNightClearedSession != sess) {
                    mNightClearedSession = sess;
                    log("nightSmart cleared on user unlock session=" + sess
                            + " hour=" + cal.get(Calendar.HOUR_OF_DAY));
                }
            }
        }
        hideBouncerBg();
        updatePostureSensors();
        updateLightSensor();
        stopTick();
    }

    private void onTimeTickEvent() {
        if (!isWallpaperAodVisible()) return;
        updateNightActive();
        checkAodTimeout();
        if (!isWallpaperAodVisible()) {
            applyAod(false);
            return;
        }
        if (cfgAutoBright) {
            onLightTickCheck();
        }
    }

    private boolean isInMorningTail(Calendar cal, int ns, int ne) {
        if (ns == ne) return false;
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int startMin = ns * 60;
        int endMin = ne * 60;
        int totalMin = endMin > startMin ? (endMin - startMin) : (24 * 60 - startMin + endMin);
        int remMin = endMin >= nowMin ? (endMin - nowMin) : (24 * 60 - nowMin + endMin);
        int morningWindowMin = Math.min(180, Math.max(30, totalMin / 2));
        return remMin > 0 && remMin <= morningWindowMin;
    }

    private int nightSessionKey(Calendar cal) {
        Calendar copy = (Calendar) cal.clone();
        if (copy.get(Calendar.HOUR_OF_DAY) < 12) {
            copy.add(Calendar.DAY_OF_YEAR, -1);
        }
        return copy.get(Calendar.YEAR) * 10000
                + (copy.get(Calendar.MONTH) + 1) * 100
                + copy.get(Calendar.DAY_OF_MONTH);
    }

    private boolean isNight() {
        if (!cfgNight || cfgNS == cfgNE) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (cfgNS < cfgNE) {
            return h >= cfgNS && h < cfgNE;
        }
        return h >= cfgNS || h < cfgNE;
    }

    private void updateNightActive() {
        if (!cfgNight) {
            mNightActive = false;
            return;
        }
        Calendar cal = Calendar.getInstance();
        boolean inNight = isNight();
        if (!inNight) {
            mNightActive = false;
            return;
        }
        if (cfgNightSmart && mNightClearedSession == nightSessionKey(cal)) {
            mNightActive = false;
            return;
        }
        mNightActive = true;
    }

    private void checkAodTimeout() {
        if (!cfgTimeoutOn || !mAod || mAodEnterTs <= 0L) {
            if (!cfgTimeoutOn) mAodTimedOut = false;
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - mAodEnterTs;
        long limitMs = (long) cfgTimeoutMin * 60000L;
        if (elapsed >= limitMs && !mAodTimedOut) {
            mAodTimedOut = true;
            log("AOD timed out after " + cfgTimeoutMin + "m mode=" + cfgTimeoutMode);
            applyAodTimeout();
        }
    }

    private final Runnable mAodSettleRun = new Runnable() {
        @Override
        public void run() {
            if (!isWallpaperAodVisible()) return;
            placeBelowPanel();
            clearOpaqueBackgrounds();
            applyScrimNow();
            syncDozeBrightness(true);
        }
    };

    private final Runnable mDiagRun = new Runnable() {
        @Override
        public void run() {
            if (mView == null) return;
            ViewGroup p = mView.getParent() instanceof ViewGroup ? (ViewGroup) mView.getParent() : null;
            int idx = p != null ? p.indexOfChild(mView) : -1;
            int panelIdx = -1;
            if (p != null) {
                View panel = findPanelChild(p);
                if (panel != null) panelIdx = p.indexOfChild(panel);
            }
            float sb = readScrimViewDirectAlpha("mScrimBehind");
            float sn = readScrimViewDirectAlpha("mNotificationsScrim");
            float sf = readScrimViewDirectAlpha("mScrimInFront");
            log("diag w=" + mView.getWidth() + " h=" + mView.getHeight()
                    + " alpha=" + mView.getAlpha()
                    + " scale=" + mView.getScaleX()
                    + " mask=" + cfgMask
                    + " effMask=" + getEffectiveAodMask()
                    + " autoBright=" + cfgAutoBright
                    + " lux=" + (mHasAppliedLight ? AmbientLightCurve.formatLux(mAppliedLightLux) : "pending")
                    + " bright=" + mLastRawBrightnessInt + "/" + mLastAppliedBrightnessInt
                    + " vis=" + mView.getVisibility()
                    + " idx=" + idx + " panel=" + panelIdx
                    + " children=" + (p != null ? p.getChildCount() : -1)
                    + " scrim=" + sb + "/" + sn + "/" + sf);
        }
    };

    private void applyAodTimeout() {
        mH.removeCallbacks(mAodSettleRun);
        mH.removeCallbacks(mDiagRun);
        updateLightSensor();
        if (cfgTimeoutMode == 1) {
            animateTo(0f, cfgAnimOut);
            restoreOpaqueBackgrounds();
            restoreClocks();
            restoreScrimAlphas();
            stopScrimKeeper();
            stopTick();
            mH.post(this::requestAodPaused);
        } else {
            degradeToClockOnly();
        }
    }

    private void degradeToClockOnly() {
        mH.removeCallbacks(mAodSettleRun);
        mH.removeCallbacks(mDiagRun);
        updateLightSensor();
        syncDozeBrightness(false);
        animateTo(0f, cfgAnimOut);
        restoreOpaqueBackgrounds();
        restoreClocks();
        restoreScrimAlphas();
        stopScrimKeeper();
    }

    private float getEffectiveAodMask() {
        return (cfgAutoBright && mCurrentAutoMask >= 0f) ? mCurrentAutoMask : cfgMask;
    }

    private boolean isWallpaperAodVisible() {
        return cfgEnabled && mAod && !mNightActive && !mAodTimedOut && !mFaceDownPaused;
    }

    private void applyAod(boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mH.post(() -> applyAodInner(force));
        } else {
            applyAodInner(force);
        }
    }

    private void applyAodInner(boolean force) {
        if (mView == null) return;

        if (isWallpaperAodVisible()) {
            mBouncerBg = false;
            ensureWallpaperLoaded();
            placeBelowPanel();
            mH.post(this::placeBelowPanel);
            clearOpaqueBackgrounds();
            mView.setBlur(0f);
            mView.setMaskAlpha(getEffectiveAodMask());
            if (cfgShift) {
                kickShift();
            } else {
                mView.setPixelShift(0f, 0f);
            }
            // Note: never call hideAllClocks() here so AOD system clock stays intact!
            restoreClocks();
            ensureLockscreenCenterOffset();
            if (force && mTargetAlpha >= 0.99f && mAnim == null) {
                applyViewScale(getTargetWallpaperScale());
            }
            animateTo(1.0f, cfgAnimIn);
            applyScrimNow();
            updateLightSensor();
            syncDozeBrightness(true);
            startScrimKeeper();
            startTick();
            mH.removeCallbacks(mAodSettleRun);
            mH.postDelayed(mAodSettleRun, 550L);
            mH.removeCallbacks(mDiagRun);
            mH.postDelayed(mDiagRun, 1500L);
            return;
        }

        mH.removeCallbacks(mAodSettleRun);
        mH.removeCallbacks(mDiagRun);
        updateLightSensor();

        if (mAod && mFaceDownPaused) {
            animateTo(0f, Math.min(cfgAnimOut, 200));
            restoreOpaqueBackgrounds();
            restoreClocks();
            restoreScrimAlphas();
            stopScrimKeeper();
            stopTick();
            mH.post(this::requestAodPaused);
            return;
        }

        if (mAod && mNightActive) {
            if (cfgNightMode == 1) {
                animateTo(0f, cfgAnimOut);
                restoreOpaqueBackgrounds();
                restoreClocks();
                restoreScrimAlphas();
                stopScrimKeeper();
                stopTick();
                mH.post(this::requestAodPaused);
            } else {
                degradeToClockOnly();
                startTick();
            }
            return;
        }

        if (mAod && mAodTimedOut) {
            applyAodTimeout();
            return;
        }

        if (!mBouncerBg) {
            animateTo(0f, cfgAnimOut);
            restoreOpaqueBackgrounds();
            restoreClocks();
            restoreScrimAlphas();
            stopScrimKeeper();
            stopTick();
        }
    }

    private boolean requestAodPaused() {
        if (mDozeMachine == null) return false;
        try {
            Object st = XposedHelpers.callMethod(mDozeMachine, "getState");
            String name = String.valueOf(st);
            if ("DOZE_AOD_PAUSED".equals(name) || "DOZE_AOD_PAUSING".equals(name)) {
                return true;
            }
            if (!"DOZE_AOD".equals(name) && !"DOZE_AOD_DOCKED".equals(name)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Enum> stCls = (Class<? extends Enum>) st.getClass();
            Object target = Enum.valueOf(stCls, "DOZE_AOD_PAUSED");
            XposedHelpers.callMethod(mDozeMachine, "requestState", target);
            log("requestAodPaused -> DOZE_AOD_PAUSED");
            return true;
        } catch (Throwable t) {
            log("requestAodPaused err: " + t);
            return false;
        }
    }

    private boolean requestAodResume() {
        if (mDozeMachine == null) return false;
        try {
            Object st = XposedHelpers.callMethod(mDozeMachine, "getState");
            String name = String.valueOf(st);
            if ("DOZE_AOD".equals(name)) {
                return true;
            }
            if (!"DOZE_AOD_PAUSED".equals(name) && !"DOZE_AOD_PAUSING".equals(name)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Enum> stCls = (Class<? extends Enum>) st.getClass();
            Object target = Enum.valueOf(stCls, "DOZE_AOD");
            XposedHelpers.callMethod(mDozeMachine, "requestState", target);
            log("requestAodResume -> DOZE_AOD");
            return true;
        } catch (Throwable t) {
            log("requestAodResume err: " + t);
            return false;
        }
    }

    public void onScrimChanged(Object sc) {
        if (sc == null) return;
        mScrimController = sc;
        if (mInApplyState) return;

        String st = readScrimState(sc);
        if ("UNLOCKED".equals(st)) {
            if (cfgShade && isShadeOverlayActive(st)) {
                ensureBouncerOrShadeBg(true);
                applyScrim(sc);
                return;
            }
            if (mBouncerBg || (mView != null && mView.getVisibility() == View.VISIBLE && !mAod)) {
                runOnMain(this::hideBouncerBg);
            }
            return;
        }

        if ("AOD".equals(st) && !mAod && !isScreenOn() && !mFaceDownPaused
                && !"DOZE_AOD_PAUSED".equals(mDozeState)) {
            onDozeState("DOZE_AOD");
            return;
        }

        if (isWallpaperAodVisible()) {
            applyScrim(sc);
            return;
        }

        if (cfgEnabled && isScreenOn()) {
            boolean isBouncer = "BOUNCER".equals(st) || "BOUNCER_SCRIMMED".equals(st)
                    || ("KEYGUARD".equals(st) && hasBouncerView(mRoot, 0));
            boolean isShade = cfgShade && isShadeOverlayActive(st);
            if (isBouncer || isShade) {
                ensureBouncerOrShadeBg(isShade && !isBouncer);
                applyScrim(sc);
                return;
            }
        }

        if (mBouncerBg && !isWallpaperAodVisible()) {
            runOnMain(this::hideBouncerBg);
        }
    }

    private String readScrimState(Object sc) {
        if (sc == null) return "";
        try {
            Object st = XposedHelpers.getObjectField(sc, "mState");
            return st != null ? String.valueOf(st) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isShadeOverlayActive(String scState) {
        if (!cfgShade || mRoot == null || !isScreenOn()) return false;
        if (mRoot.getVisibility() != View.VISIBLE || mRoot.getHeight() <= 0) return false;
        if ("SHADE_LOCKED".equals(scState)) return true;
        if (mScrimController == null) return false;
        float notifAlpha = readScrimFloat(mScrimController, "mNotificationsAlpha");
        if (notifAlpha > 0.08f) return true;
        float notifViewAlpha = readScrimViewAlpha(mScrimController, "mNotificationsScrim");
        return notifViewAlpha > 0.08f;
    }

    private void ensureBouncerOrShadeBg(final boolean isShade) {
        runOnMain(() -> {
            if (mView == null) return;
            mBouncerLast = SystemClock.elapsedRealtime();
            boolean changed = !mBouncerBg;
            mBouncerBg = true;
            ensureWallpaperLoaded();
            if (changed) {
                ensureLockscreenCenterOffset();
            }
            placeBelowPanel();
            applyViewScale(getTargetWallpaperScale());
            mView.setBlur(isShade ? 0f : 22f);
            mView.setMaskAlpha(isShade ? cfgShadeMask : cfgMask);
            mView.setPixelShift(0f, 0f);
            ensureKeyguardDim();
            clearOpaqueBackgrounds();
            if (!isShade) {
                hideAllClocks();
            } else {
                restoreClocks();
            }
            if (changed || mTargetAlpha < 0.99f) {
                animateTo(1.0f, Math.min(cfgAnimIn, 220));
            }
            startScrimKeeper();
            startTick();
        });
    }

    private void showBouncerBg() {
        ensureBouncerOrShadeBg(false);
    }

    private void hideBouncerBg() {
        if (!mBouncerBg && !mClocksHidden && !mScrimTouched) return;
        mBouncerBg = false;
        restoreClocks();
        restoreOpaqueBackgrounds();
        clearRootDim();
        restoreScrimAlphas();
        if (!isWallpaperAodVisible()) {
            animateTo(0f, Math.min(cfgAnimOut, 200));
            stopTick();
        }
    }

    private void applyScrimNow() {
        if (mScrimController != null) {
            applyScrim(mScrimController);
        }
    }

    private void applyScrim(Object sc) {
        if (sc == null) return;
        boolean aodVis = isWallpaperAodVisible();
        if (!aodVis && !mBouncerBg) return;

        try {
            if (aodVis) {
                clampScrimViewZero(sc, "mScrimBehind");
                clampScrimViewZero(sc, "mNotificationsScrim");
                clampScrimViewZero(sc, "mScrimInFront");
                mScrimTouched = true;
            } else if (mBouncerBg) {
                clampScrimViewZero(sc, "mScrimBehind");
                mScrimTouched = true;
            }
        } catch (Throwable t) {
            log("applyScrim err: " + t);
        }
    }

    private void clampScrimViewZero(Object sc, String fieldName) {
        try {
            Object sv = XposedHelpers.getObjectField(sc, fieldName);
            if (!(sv instanceof View)) return;
            View v = (View) sv;
            if (v.getAlpha() > 0.001f) {
                v.setAlpha(0f);
            }
        } catch (Throwable ignored) {
        }
    }

    private float readScrimViewDirectAlpha(String fieldName) {
        if (mScrimController == null) return -1f;
        try {
            Object sv = XposedHelpers.getObjectField(mScrimController, fieldName);
            if (sv instanceof View) return ((View) sv).getAlpha();
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    private void restoreScrimAlphas() {
        if (!mScrimTouched || mScrimController == null) {
            mScrimTouched = false;
            return;
        }
        mScrimTouched = false;
        try {
            for (String f : new String[] { "mScrimBehind", "mNotificationsScrim", "mScrimInFront" }) {
                Object sv = XposedHelpers.getObjectField(mScrimController, f);
                if (sv instanceof View && ((View) sv).getAlpha() < 0.999f) {
                    ((View) sv).setAlpha(1f);
                }
            }
        } catch (Throwable ignored) {
        }
        mSavedBehindViewAlpha = -1f;
        mSavedFrontViewAlpha = -1f;
        callApplyState();
    }

    private boolean callApplyState() {
        if (mScrimController == null || mInApplyState) return false;
        mInApplyState = true;
        try {
            if (!mApplyStateMethodResolved) {
                mApplyStateMethodResolved = true;
                Class<?> cls = mScrimController.getClass();
                outer:
                for (String name : new String[] { "applyAndDispatchState", "applyState$1$1", "applyState" }) {
                    Class<?> cur = cls;
                    while (cur != null && cur != Object.class) {
                        try {
                            Method m = cur.getDeclaredMethod(name);
                            m.setAccessible(true);
                            mApplyStateMethod = m;
                            break outer;
                        } catch (NoSuchMethodException ignored) {
                            cur = cur.getSuperclass();
                        }
                    }
                }
            }
            if (mApplyStateMethod != null) {
                mApplyStateMethod.invoke(mScrimController);
                return true;
            }
        } catch (Throwable ignored) {
        } finally {
            mInApplyState = false;
        }
        return false;
    }

    private void ensureKeyguardDim() {
        if (mScrimController == null) return;
        try {
            Object behind = XposedHelpers.getObjectField(mScrimController, "mScrimBehind");
            if (behind instanceof View) {
                Object d = null;
                try {
                    d = XposedHelpers.getObjectField(behind, "mDrawable");
                } catch (Throwable ignored) {
                }
                if (d instanceof Drawable) {
                    Rect b = ((Drawable) d).getBounds();
                    if (b.isEmpty() && mRoot != null && mRoot.getWidth() > 0 && mRoot.getHeight() > 0) {
                        ((Drawable) d).setBounds(0, 0, mRoot.getWidth(), mRoot.getHeight());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearOpaqueBackgrounds() {
        if (mRoot == null) return;
        try {
            int rw = mRoot.getWidth();
            int rh = mRoot.getHeight();
            for (int i = 0; i < mRoot.getChildCount(); i++) {
                View c = mRoot.getChildAt(i);
                if (c == null || c == mView) continue;
                boolean isPanel = c.getClass().getName().endsWith("NotificationPanelView");
                if (!isPanel) {
                    if (c.getVisibility() != View.VISIBLE) continue;
                    if (rw > 0 && rh > 0 && (c.getWidth() < rw - 4 || c.getHeight() < rh - 4)) continue;
                }
                Drawable bg = c.getBackground();
                if (bg instanceof ColorDrawable && !mClearedViews.contains(c)) {
                    mClearedViews.add(c);
                    mClearedBgs.add(bg);
                    c.setBackground(null);
                    log("clear bg[" + i + "] " + c.getClass().getSimpleName()
                            + " #" + Integer.toHexString(((ColorDrawable) bg).getColor()));
                }
            }
        } catch (Throwable t) {
            log("clearOpaque err: " + t);
        }
        if (mBouncerBg) {
            clearOpaqueRecursive(mRoot, 0);
        }
    }

    private void clearOpaqueRecursive(View v, int depth) {
        if (v == null || v == mView || depth > 8) return;
        String cls = v.getClass().getName();
        if (cls.contains("Bouncer") || cls.contains("KeyguardBouncer") || cls.contains("ScrimView")) {
            Drawable bg = v.getBackground();
            if (bg != null && !mClearedViews.contains(v)) {
                mClearedViews.add(v);
                mClearedBgs.add(bg);
                v.setBackground(null);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                clearOpaqueRecursive(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private void restoreOpaqueBackgrounds() {
        for (int i = mClearedViews.size() - 1; i >= 0; i--) {
            View v = mClearedViews.get(i);
            Drawable d = mClearedBgs.get(i);
            if (v != null && d != null && v.getBackground() == null) {
                v.setBackground(d);
            }
        }
        mClearedViews.clear();
        mClearedBgs.clear();
    }

    private void clearRootDim() {
        if (mRoot != null && mRoot.getAlpha() != 1f) {
            mRoot.setAlpha(1f);
        }
    }

    private void hideAllClocks() {
        if (mRoot == null || mClocksHidden) return;
        mClocks.clear();
        mClocksAlpha.clear();
        collectClocks(mRoot, 0);
        for (int i = 0; i < mClocks.size(); i++) {
            View cv = mClocks.get(i);
            cv.setAlpha(0f);
        }
        mClocksHidden = true;
    }

    private void restoreClocks() {
        if (!mClocksHidden && mClocks.isEmpty()) return;
        for (int i = 0; i < mClocks.size(); i++) {
            View cv = mClocks.get(i);
            float orig = mClocksAlpha.get(i);
            if (cv != null) {
                cv.setAlpha(orig);
            }
        }
        mClocks.clear();
        mClocksAlpha.clear();
        if (mClockView != null) {
            mClockView.setAlpha(mClockOldAlpha);
            mClockView = null;
        }
        mClocksHidden = false;
    }

    private void collectClocks(View v, int depth) {
        if (v == null || v == mView || depth > 10) return;
        String cls = v.getClass().getName();
        // Never touch the status bar clock!
        if ("com.android.systemui.statusbar.policy.Clock".equals(cls)
                || cls.contains("StatusBar")
                || cls.contains("PhoneStatusBar")) {
            return;
        }
        String resName = "";
        try {
            if (v.getId() != View.NO_ID) {
                resName = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }
        if ("clock".equals(resName) || resName.contains("keyguard_status_bar")) {
            return;
        }
        boolean isKeyguardClock = cls.contains("KeyguardClock")
                || cls.contains("AnimatableClockView")
                || cls.contains("ClockFrame")
                || cls.contains("KeyguardSliceView")
                || resName.contains("lockscreen_clock")
                || resName.contains("keyguard_clock");
        if (isKeyguardClock) {
            if (!mClocks.contains(v)) {
                mClocks.add(v);
                mClocksAlpha.add(v.getAlpha());
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                collectClocks(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private boolean hasBouncerView(View v, int depth) {
        if (v == null || v == mView || depth > 6) return false;
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return false;
        String cls = v.getClass().getName();
        if (cls.contains("KeyguardBouncer") || cls.contains("BouncerView") || cls.contains("KeyguardSecurityContainer")) {
            return v.getWidth() > 0 && v.getHeight() > 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasBouncerView(vg.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    private View findPanelChild(ViewGroup root) {
        if (root == null) return null;
        int n = root.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = root.getChildAt(i);
            if (c != null && c != mView && c.getClass().getName().endsWith("NotificationPanelView")) {
                return c;
            }
        }
        for (int i = 0; i < n; i++) {
            View c = root.getChildAt(i);
            if (c == null || c == mView) continue;
            String cn = c.getClass().getName();
            if (cn.contains("KeyguardRootView") || cn.endsWith("ShadeRootView") || cn.contains("NotificationPanel")) {
                return c;
            }
        }
        return null;
    }

    private void placeBelowPanel() {
        if (mRoot == null || mView == null) return;
        View panel = findPanelChild(mRoot);
        if (panel == null) {
            log("place: panel not found children=" + mRoot.getChildCount());
            return;
        }
        int curIdx = mRoot.indexOfChild(mView);
        int panelIdx = mRoot.indexOfChild(panel);
        if (!cfgTop) {
            if (curIdx >= 0 && curIdx == panelIdx - 1) {
                return;
            }
            if (curIdx >= 0) {
                mRoot.removeView(mView);
            }
            int target = Math.max(0, mRoot.indexOfChild(panel));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mRoot.addView(mView, target, lp);
            log("place: idx=" + mRoot.indexOfChild(mView)
                    + " panel=" + mRoot.indexOfChild(panel)
                    + " children=" + mRoot.getChildCount());
        } else {
            if (curIdx >= 0 && curIdx == panelIdx + 1) {
                return;
            }
            if (curIdx >= 0) {
                mRoot.removeView(mView);
            }
            int target = Math.min(mRoot.getChildCount(), mRoot.indexOfChild(panel) + 1);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mRoot.addView(mView, target, lp);
            log("place(top): idx=" + mRoot.indexOfChild(mView)
                    + " panel=" + mRoot.indexOfChild(panel)
                    + " children=" + mRoot.getChildCount());
        }
    }

    private void animateTo(final float targetAlpha, int durationMs) {
        if (mView == null) return;
        float clamped = clampFloat(targetAlpha, 0f, 1f);
        if (mShowingTarget && Math.abs(clamped - mTargetAlpha) < 0.01f) {
            if (clamped > 0f && mView.getVisibility() != View.VISIBLE) {
                mView.setVisibility(View.VISIBLE);
            }
            return;
        }
        mShowingTarget = true;
        mTargetAlpha = clamped;

        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }

        float startAlpha = mView.getAlpha();
        if (clamped > 0f && mView.getVisibility() != View.VISIBLE) {
            mView.setAlpha(0f);
            applyViewScale(mBaseWallpaperScale);
            mView.setVisibility(View.VISIBLE);
            startAlpha = 0f;
        }
        if (durationMs <= 16 || Math.abs(startAlpha - clamped) < 0.01f) {
            mView.setAlpha(clamped);
            float s = lerpWallpaperScale(clamped);
            if (clamped <= 0.002f) {
                mView.setVisibility(View.GONE);
                applyViewScale(mBaseWallpaperScale);
            } else {
                applyViewScale(s);
            }
            return;
        }

        ValueAnimator anim = ValueAnimator.ofFloat(startAlpha, clamped);
        anim.setDuration(Math.max(60, durationMs));
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(a -> {
            if (mView != null) {
                float v = (Float) a.getAnimatedValue();
                mView.setAlpha(v);
                applyViewScale(lerpWallpaperScale(v));
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnim == animation) mAnim = null;
                if (cancelled || mView == null) return;
                if (clamped <= 0.002f) {
                    mView.setVisibility(View.GONE);
                    applyViewScale(mBaseWallpaperScale);
                }
            }
        });
        mAnim = anim;
        anim.start();
    }

    private void ensureWallpaperLoaded() {
        if (mBitmap != null && !mBitmap.isRecycled()) return;
        loadWallpaper(0);
    }

    private void loadWallpaper(int reason) {
        if (mCtx == null || mWpLoading) return;
        long now = SystemClock.elapsedRealtime();
        if (reason == 0 && mBitmap != null && !mBitmap.isRecycled() && (now - mLastWpLoadTs) < 5000L) {
            return;
        }
        mWpLoading = true;
        mLastWpLoadTs = now;
        ensureBgThread();
        mBgH.post(() -> {
            Bitmap loaded = null;
            try {
                loaded = decodeWallpaperSafe();
            } catch (Throwable t) {
                log("loadWallpaper err: " + t);
            } finally {
                mWpLoading = false;
            }
            if (loaded != null) {
                final Bitmap finalBmp = loaded;
                mH.post(() -> {
                    mBitmap = finalBmp;
                    if (mView != null) {
                        mView.setBitmap(finalBmp);
                    }
                    log("wallpaper loaded " + finalBmp.getWidth() + "x" + finalBmp.getHeight()
                            + " cfg=" + finalBmp.getConfig());
                });
            }
        });
    }

    private Bitmap decodeWallpaperSafe() {
        WallpaperManager wm = WallpaperManager.getInstance(mCtx);
        if (wm == null) return null;

        int[] flags = new int[] { WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM };
        for (int flag : flags) {
            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(flag)) {
                if (pfd != null) {
                    Bitmap b = decodeBoundedPfd(pfd);
                    if (b != null) return b;
                }
            } catch (Throwable ignored) {
            }
        }

        Drawable d = null;
        try {
            d = wm.getDrawable();
        } catch (Throwable ignored) {
        }
        if (d == null) {
            try {
                d = wm.getFastDrawable();
            } catch (Throwable ignored) {
            }
        }
        if (d == null) return null;

        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                if (Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
                    return b.copy(Bitmap.Config.ARGB_8888, false);
                }
                return ensureArgbOrDownsample(b);
            }
        }

        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        while (Math.max(w, h) > 3200) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return out;
    }

    private static Bitmap decodeBoundedPfd(ParcelFileDescriptor pfd) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        while (Math.max(w / sample, h / sample) > 3600) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = false;
        Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        if (bmp != null && bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap conv = bmp.copy(Bitmap.Config.ARGB_8888, false);
            if (conv != null) {
                bmp.recycle();
                return conv;
            }
        }
        return bmp;
    }

    private static Bitmap ensureArgbOrDownsample(Bitmap b) {
        if (Math.max(b.getWidth(), b.getHeight()) <= 3600 && b.getConfig() == Bitmap.Config.ARGB_8888) {
            return b;
        }
        float scale = 1f;
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max > 3600) {
            scale = 3600f / max;
        }
        int nw = Math.max(1, Math.round(b.getWidth() * scale));
        int nh = Math.max(1, Math.round(b.getHeight() * scale));
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(b, null, new Rect(0, 0, nw, nh), null);
        return out;
    }

    private void kickShift() {
        if (mView == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastShiftTs < SHIFT_INTERVAL_MS && mLastShiftTs > 0L) return;
        mLastShiftTs = now;
        int dx = mRand.nextInt(13) - 6; // [-6..+6] px burn-in protection
        int dy = mRand.nextInt(13) - 6;
        mView.setPixelShift(dx, dy);
    }

    // ---- Adaptive / event-driven Tick & bounded ScrimKeeper ----

    private final Runnable mTickRun = new Runnable() {
        @Override
        public void run() {
            if (!mTickRunning) return;
            tickLogic();
        }
    };

    private void startTick() {
        long delay = nextTickDelayMs();
        if (delay <= 0L) {
            stopTick();
            return;
        }
        mH.removeCallbacks(mTickRun);
        mTickRunning = true;
        mH.postDelayed(mTickRun, delay);
    }

    private void stopTick() {
        mTickRunning = false;
        mH.removeCallbacks(mTickRun);
    }

    private long nextTickDelayMs() {
        if (mFaceDownPaused) return -1L;
        if (mBouncerBg && isScreenOn()) return BOUNCER_TICK_MS;
        if (!mAod) return -1L;
        if (mNightActive && cfgNightMode == 1) return -1L;
        if (mAodTimedOut && cfgTimeoutMode == 1) return -1L;
        if (isWallpaperAodVisible()) {
            long d = cfgShift ? SHIFT_INTERVAL_MS : 30000L;
            if (cfgTimeoutOn && mAodEnterTs > 0L) {
                long rem = (long) cfgTimeoutMin * 60000L - (SystemClock.elapsedRealtime() - mAodEnterTs);
                if (rem > 0L) d = Math.min(d, rem + 50L);
            }
            return Math.max(1000L, d);
        }
        // Clock-only AOD night/timeout watch
        return 30000L;
    }

    private void tickLogic() {
        updateNightActive();
        checkAodTimeout();

        if (mBouncerBg) {
            if (!isScreenOn()) {
                hideBouncerBg();
            } else {
                String st = readScrimState(mScrimController);
                boolean isBouncer = "BOUNCER".equals(st) || "BOUNCER_SCRIMMED".equals(st)
                        || ("KEYGUARD".equals(st) && hasBouncerView(mRoot, 0));
                boolean isShade = cfgShade && isShadeOverlayActive(st);
                if (!isBouncer && !isShade) {
                    hideBouncerBg();
                } else if (SystemClock.elapsedRealtime() - mBouncerLast > 150L) {
                    applyScrimNow();
                }
            }
        } else if (isWallpaperAodVisible()) {
            if (cfgShift) kickShift();
            applyScrimNow();
            if (cfgAutoBright) onLightTickCheck();
        } else if (mAod) {
            applyAodInner(false);
        }

        long next = nextTickDelayMs();
        if (next > 0L && mTickRunning) {
            mH.postDelayed(mTickRun, next);
        } else {
            mTickRunning = false;
        }
    }

    private final Runnable mScrimKeeperRun = new Runnable() {
        @Override
        public void run() {
            if (!mScrimKeeperRunning) return;
            if ((!isWallpaperAodVisible() && !mBouncerBg) || mScrimKeeperTicks >= SCRIM_KEEPER_MAX_TICKS) {
                stopScrimKeeper();
                return;
            }
            mScrimKeeperTicks++;
            applyScrimNow();
            mH.postDelayed(this, SCRIM_KEEPER_INTERVAL_MS);
        }
    };

    private void startScrimKeeper() {
        mScrimKeeperTicks = 0;
        if (!mScrimKeeperRunning) {
            mScrimKeeperRunning = true;
            mH.removeCallbacks(mScrimKeeperRun);
            mH.postDelayed(mScrimKeeperRun, SCRIM_KEEPER_INTERVAL_MS);
        }
    }

    private void stopScrimKeeper() {
        mScrimKeeperRunning = false;
        mH.removeCallbacks(mScrimKeeperRun);
    }

    // ---- Hardware Wakeup Posture Sensors (Flip-Off & Lift-Wake, zero continuous polling!) ----

    private void probeSensorsOnce() {
        if (mSensorsProbed || mSm == null) return;
        mSensorsProbed = true;
        try {
            mProxSensor = mSm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
            if (mProxSensor == null) mProxSensor = mSm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            mAccelSensor = mSm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mTiltSensor = findWakeupSensorByType(SENSOR_TYPE_TILT_DETECTOR);
            mPickupSensor = findWakeupSensorByType(SENSOR_TYPE_PICK_UP_GESTURE);
            mLightSensor = findWakeupSensorByType(Sensor.TYPE_LIGHT);
            log("sensors probed: prox=" + (mProxSensor != null ? mProxSensor.getName() : "none")
                    + " tilt=" + (mTiltSensor != null ? mTiltSensor.getName() : "none")
                    + " pickup=" + (mPickupSensor != null ? mPickupSensor.getName() : "none")
                    + " light=" + (mLightSensor != null
                    ? (mLightSensor.getName() + " wakeup=" + mLightSensor.isWakeUpSensor()) : "none")
                    + " accel=" + (mAccelSensor != null ? mAccelSensor.getName() : "none"));
        } catch (Throwable t) {
            log("probeSensors err: " + t);
        }
    }

    private Sensor findWakeupSensorByType(int type) {
        if (mSm == null) return null;
        try {
            Sensor s = mSm.getDefaultSensor(type, true);
            if (s != null) return s;
        } catch (Throwable ignored) {
        }
        try {
            List<Sensor> list = mSm.getSensorList(type);
            if (list != null) {
                for (Sensor s : list) {
                    if (s != null && s.isWakeUpSensor()) return s;
                }
                if (!list.isEmpty()) return list.get(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updatePostureSensors() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mH.post(this::updatePostureSensors);
            return;
        }
        if (mCtx == null || mSm == null) return;
        probeSensorsOnce();

        boolean screenOn = isScreenOn();
        boolean needPosture = cfgEnabled && (cfgFlipOff || cfgLiftWake) && (mDozing || !screenOn || mFaceDownPaused);
        boolean needProxForLight = cfgEnabled && cfgAutoBright && isWallpaperAodVisible();
        boolean needProx = needPosture || needProxForLight;

        if (mProxSensor != null && needProx && !mProxRegistered) {
            mProxRegistered = mSm.registerListener(mProxListener, mProxSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
        } else if (!needProx && mProxRegistered) {
            mSm.unregisterListener(mProxListener);
            mProxRegistered = false;
            mProximityNear = false;
        }

        if (!needPosture) {
            stopMotionPostureSensorsOnly();
            return;
        }

        if (mTiltSensor != null && !mTiltRegistered) {
            mTiltRegistered = mSm.registerListener(mTiltListener, mTiltSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
        }
        armPickupTriggerIfNeeded();

        if (!mInitialBurstDoneForOff && (cfgFlipOff || !mBaseGravityInit)) {
            mInitialBurstDoneForOff = true;
            mBaseGravityInit = false;
            startAccelBurst(5000L, false);
        }
    }

    private void stopMotionPostureSensorsOnly() {
        if (mSm == null) return;
        if (mTiltRegistered) {
            mSm.unregisterListener(mTiltListener);
            mTiltRegistered = false;
        }
        stopAccelBurst();
        if (mPickupTriggerRegistered && mPickupSensor != null) {
            try {
                mSm.cancelTriggerSensor(mPickupTrigger, mPickupSensor);
            } catch (Throwable ignored) {
            }
            mPickupTriggerRegistered = false;
        }
        releaseFaceDownWakeLock();
    }

    private void stopAllPostureSensors() {
        if (mSm == null) return;
        if (mProxRegistered) {
            mSm.unregisterListener(mProxListener);
            mProxRegistered = false;
            mProximityNear = false;
        }
        stopMotionPostureSensorsOnly();
    }

    // ---- Ambient Light Auto-Brightness (Bounded WakeLock for Debounce/Ramp Only) ----

    private void acquireAutoLightWakeLock(long timeoutMs) {
        if (mPm == null) return;
        try {
            if (mAutoLightWakeLock == null) {
                mAutoLightWakeLock = mPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":AutoLight");
                mAutoLightWakeLock.setReferenceCounted(false);
            }
            mAutoLightWakeLock.acquire(Math.max(400L, Math.min(timeoutMs, LIGHT_WAKELOCK_MAX_MS)));
        } catch (Throwable ignored) {
        }
    }

    private void releaseAutoLightWakeLock() {
        try {
            if (mAutoLightWakeLock != null && mAutoLightWakeLock.isHeld()) {
                mAutoLightWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isLightBlockedByProximity(float cleanLux) {
        if (!mProximityNear || !mHasAppliedLight) {
            return false;
        }
        return !(cleanLux >= 50f && cleanLux > mAppliedLightLux * 1.35f);
    }

    private void updateLightSensor() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mH.post(this::updateLightSensor);
            return;
        }
        if (mCtx == null || mSm == null) return;
        probeSensorsOnce();

        boolean need = cfgEnabled && cfgAutoBright && isWallpaperAodVisible();
        if (!need) {
            if (mLightRegistered) {
                try {
                    mSm.unregisterListener(mLightListener);
                } catch (Throwable ignored) {
                }
                mLightRegistered = false;
            }
            mPendingLuxActive = false;
            mForceImmediateAnimatedLight = false;
            mH.removeCallbacks(mLightDebounceRun);
            cancelAutoBrightAnim();
            releaseAutoLightWakeLock();
            if (!mAod || !cfgAutoBright || mFaceDownPaused || mNightActive || mAodTimedOut) {
                mHasAppliedLight = false;
                mAppliedLightLux = -1f;
                mAppliedLightNorm = -1f;
                mAutoBrightTargetInt = -1;
                mAutoBrightTargetFloat = -1f;
                mCurrentAnimHwFloat = -1f;
                mCurrentIdealMask = -1f;
                mCurrentAutoMask = -1f;
            }
            return;
        }

        if (mLightSensor != null && !mLightRegistered) {
            mHasAppliedLight = false;
            mForceImmediateAnimatedLight = false;
            mPendingLuxActive = false;
            mH.removeCallbacks(mLightDebounceRun);
            mLastLightEventTs = SystemClock.elapsedRealtime();
            try {
                mLightRegistered = mSm.registerListener(
                        mLightListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
            } catch (Throwable t) {
                mLightRegistered = false;
                log("register light err: " + t);
            }
        }
    }

    private void onLightSensorChanged(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux)) return;
        float cleanLux = Math.max(0f, lux);
        long now = SystemClock.elapsedRealtime();
        mRawLightLux = cleanLux;
        mLastLightEventTs = now;

        if (!cfgEnabled || !cfgAutoBright || !isWallpaperAodVisible()) return;
        if (isLightBlockedByProximity(cleanLux)) {
            return;
        }

        if (!mHasAppliedLight) {
            mPendingLuxActive = false;
            mForceImmediateAnimatedLight = false;
            mH.removeCallbacks(mLightDebounceRun);
            commitAutoBrightness(cleanLux, false);
            return;
        }

        if (mForceImmediateAnimatedLight) {
            mForceImmediateAnimatedLight = false;
            mPendingLuxActive = false;
            mH.removeCallbacks(mLightDebounceRun);
            commitAutoBrightness(cleanLux, true);
            return;
        }

        float newNorm = AmbientLightCurve.luxToNorm(cleanLux);
        float diff = newNorm - mAppliedLightNorm;
        if (Math.abs(diff) < LIGHT_DEADBAND_NORM) {
            mPendingLuxActive = false;
            mH.removeCallbacks(mLightDebounceRun);
            if (mAutoBrightAnim == null) {
                releaseAutoLightWakeLock();
            }
            return;
        }

        int dir = diff > 0f ? 1 : -1;
        long delay = (dir > 0) ? LIGHT_DEBOUNCE_BRIGHTEN_MS : LIGHT_DEBOUNCE_DARKEN_MS;
        if (!mPendingLuxActive || mPendingLuxDir != dir) {
            mPendingLuxActive = true;
            mPendingLuxDir = dir;
            mPendingLux = cleanLux;
            mPendingLuxDueTs = now + delay;
            acquireAutoLightWakeLock(delay + 400L);
            mH.removeCallbacks(mLightDebounceRun);
            mH.postDelayed(mLightDebounceRun, delay);
        } else {
            mPendingLux = cleanLux;
            if (now >= mPendingLuxDueTs) {
                mPendingLuxActive = false;
                mH.removeCallbacks(mLightDebounceRun);
                commitAutoBrightness(mPendingLux, true);
            } else if (mAutoLightWakeLock == null || !mAutoLightWakeLock.isHeld()) {
                acquireAutoLightWakeLock((mPendingLuxDueTs - now) + 400L);
            }
        }
    }

    private final Runnable mLightDebounceRun = new Runnable() {
        @Override
        public void run() {
            if (!mPendingLuxActive) return;
            mPendingLuxActive = false;
            if (!isWallpaperAodVisible() || !cfgAutoBright || isLightBlockedByProximity(mPendingLux)) {
                if (mAutoBrightAnim == null) {
                    releaseAutoLightWakeLock();
                }
                return;
            }
            commitAutoBrightness(mPendingLux, true);
        }
    };

    private void onLightTickCheck() {
        if (!isWallpaperAodVisible() || !cfgAutoBright) return;
        long now = SystemClock.elapsedRealtime();
        if (mPendingLuxActive && now >= mPendingLuxDueTs) {
            mPendingLuxActive = false;
            mH.removeCallbacks(mLightDebounceRun);
            if (!isLightBlockedByProximity(mPendingLux)) {
                commitAutoBrightness(mPendingLux, true);
            } else if (mAutoBrightAnim == null) {
                releaseAutoLightWakeLock();
            }
            return;
        }
        if (mProximityNear) return;
        if (mLightRegistered && mLightSensor != null && !mLightSensor.isWakeUpSensor()
                && (now - mLastLightEventTs >= LIGHT_STALE_REREGISTER_MS)) {
            try {
                mSm.unregisterListener(mLightListener);
                mLightRegistered = mSm.registerListener(
                        mLightListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
            } catch (Throwable t) {
                mLightRegistered = false;
                log("light refresh err: " + t);
            }
        }
    }

    private void onLightUncovered() {
        if (!isWallpaperAodVisible() || !cfgAutoBright || mProximityNear) return;
        mPendingLuxActive = false;
        mH.removeCallbacks(mLightDebounceRun);
        mForceImmediateAnimatedLight = true;
        acquireAutoLightWakeLock(800L);
        if (mSm != null && mLightSensor != null) {
            try {
                if (mLightRegistered) {
                    mSm.unregisterListener(mLightListener);
                    mLightRegistered = false;
                }
                mLightRegistered = mSm.registerListener(
                        mLightListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
            } catch (Throwable t) {
                log("light uncover err: " + t);
            }
        }
    }

    private void commitAutoBrightness(float lux, boolean animate) {
        if (!isWallpaperAodVisible() || !cfgAutoBright) return;
        float cleanLux = Math.max(0f, lux);
        float prevNorm = (mAppliedLightNorm >= 0f) ? mAppliedLightNorm : AmbientLightCurve.luxToNorm(cleanLux);
        float newNorm = AmbientLightCurve.luxToNorm(cleanLux);

        mHasAppliedLight = true;
        mAppliedLightLux = cleanLux;
        mAppliedLightNorm = newNorm;

        int targetPct = AmbientLightCurve.computeBrightnessPct(cleanLux, cfgAutoBrightMin, cfgAutoBrightMax);
        final int targetInt = AmbientLightCurve.pctToDozeInt(targetPct);
        mAutoBrightTargetFloat = AmbientLightCurve.pctToDozeFloat(targetPct);
        mAutoBrightTargetInt = targetInt;

        final float targetMask = AmbientLightCurve.computeDynamicMask(cleanLux, cfgMask);
        final float startMask = (mCurrentIdealMask >= 0f) ? mCurrentIdealMask
                : ((mCurrentAutoMask >= 0f) ? mCurrentAutoMask : cfgMask);
        final float startHwFloat = (mCurrentAnimHwFloat >= 1f) ? mCurrentAnimHwFloat
                : ((mLastAppliedBrightnessInt > 0) ? (float) mLastAppliedBrightnessInt : (float) targetInt);

        cancelAutoBrightAnim();

        if (!animate || (Math.abs(targetMask - startMask) < 0.008f && Math.abs(targetInt - startHwFloat) < 0.75f)) {
            log("autoBright: lux=" + AmbientLightCurve.formatLux(cleanLux)
                    + " (" + AmbientLightCurve.describeLightLevel(cleanLux) + ")"
                    + " pct=" + targetPct + "% hw=" + targetInt + "/255"
                    + " mask=" + String.format(Locale.US, "%.2f", targetMask)
                    + " anim=false");
            mCurrentIdealMask = targetMask;
            mCurrentAutoMask = targetMask;
            mCurrentAnimHwFloat = targetInt;
            if (mView != null) {
                mView.setMaskAlpha(targetMask);
            }
            pushDozeBrightnessInt(targetInt);
            if (!mPendingLuxActive) {
                releaseAutoLightWakeLock();
            }
            return;
        }

        final int durationMs = AmbientLightCurve.computeTransitionDurationMs(
                prevNorm, newNorm, targetInt >= startHwFloat);
        log("autoBright: lux=" + AmbientLightCurve.formatLux(cleanLux)
                + " (" + AmbientLightCurve.describeLightLevel(cleanLux) + ")"
                + " pct=" + targetPct + "% hw=" + Math.round(startHwFloat) + "->" + targetInt + "/255"
                + " mask=" + String.format(Locale.US, "%.2f->%.2f", startMask, targetMask)
                + " dur=" + durationMs + "ms");

        acquireAutoLightWakeLock(durationMs + 350L);
        final int[] stats = new int[2];
        final long startWallMs = SystemClock.elapsedRealtime();

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(durationMs);
        anim.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f));
        anim.addUpdateListener(a -> {
            if (!isWallpaperAodVisible() || !cfgAutoBright) {
                a.cancel();
                return;
            }
            float f = (Float) a.getAnimatedValue();
            float idealHw = AmbientLightCurve.lerpLogBrightness(startHwFloat, (float) targetInt, f);
            int hwInt = clampInt(Math.round(idealHw), 1, 255);
            float idealMask = startMask + (targetMask - startMask) * f;
            float compMask = AmbientLightCurve.compensateMaskForHwStep(idealMask, idealHw, hwInt);

            mCurrentAnimHwFloat = idealHw;
            mCurrentIdealMask = idealMask;
            mCurrentAutoMask = compMask;

            if (mView != null) {
                mView.setMaskAlpha(compMask);
            }
            if (hwInt != mLastAppliedBrightnessInt) {
                pushDozeBrightnessInt(hwInt);
                stats[1]++;
            }
            stats[0]++;
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAutoBrightAnim == animation) mAutoBrightAnim = null;
                if (!cancelled && isWallpaperAodVisible() && cfgAutoBright) {
                    mCurrentAnimHwFloat = targetInt;
                    mCurrentIdealMask = targetMask;
                    mCurrentAutoMask = targetMask;
                    if (mView != null) {
                        mView.setMaskAlpha(targetMask);
                    }
                    if (mLastAppliedBrightnessInt != targetInt) {
                        pushDozeBrightnessInt(targetInt);
                        stats[1]++;
                    }
                    log("autoBright anim done: frames=" + stats[0]
                            + " steps=" + stats[1]
                            + " elapsed=" + (SystemClock.elapsedRealtime() - startWallMs) + "ms"
                            + " dur=" + durationMs + "ms");
                }
                if (mAutoBrightAnim == null && !mPendingLuxActive) {
                    releaseAutoLightWakeLock();
                }
            }
        });
        mAutoBrightAnim = anim;
        anim.start();
    }

    private void cancelAutoBrightAnim() {
        if (mAutoBrightAnim != null) {
            mAutoBrightAnim.cancel();
            mAutoBrightAnim = null;
        }
    }

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0) return;
            onLightSensorChanged(event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void armPickupTriggerIfNeeded() {
        if (mSm == null || mPickupSensor == null || mPickupTriggerRegistered) return;
        if (!cfgEnabled || (!cfgLiftWake && !mFaceDownPaused)) return;
        try {
            mPickupTriggerRegistered = mSm.requestTriggerSensor(mPickupTrigger, mPickupSensor);
        } catch (Throwable ignored) {
            mPickupTriggerRegistered = false;
        }
    }

    private void startAccelBurst(long durationMs, boolean withShortWakeLock) {
        if (mSm == null || mAccelSensor == null) return;
        long now = SystemClock.elapsedRealtime();
        mAccelStopAtElapsed = Math.max(mAccelStopAtElapsed, now + durationMs);
        if (withShortWakeLock) {
            acquireFaceDownWakeLock(Math.min(durationMs, BURST_WAKELOCK_MAX_MS));
        }
        if (!mAccelRegistered) {
            mSensorStartElapsed = now;
            mFaceDownSince = 0L;
            mFaceUpSince = 0L;
            mAccelRegistered = mSm.registerListener(mAccelListener, mAccelSensor, SensorManager.SENSOR_DELAY_NORMAL, mH);
        }
        mH.removeCallbacks(mAccelTimeoutRun);
        mH.postDelayed(mAccelTimeoutRun, Math.max(500L, mAccelStopAtElapsed - now));
    }

    private void stopAccelBurst() {
        mH.removeCallbacks(mAccelTimeoutRun);
        if (mAccelRegistered && mSm != null) {
            mSm.unregisterListener(mAccelListener);
            mAccelRegistered = false;
        }
        releaseFaceDownWakeLock();
    }

    private final Runnable mAccelTimeoutRun = () -> {
        long now = SystemClock.elapsedRealtime();
        if (now >= mAccelStopAtElapsed) {
            stopAccelBurst();
        } else {
            mH.postDelayed(this::stopAccelBurst, mAccelStopAtElapsed - now);
        }
    };

    private void acquireFaceDownWakeLock(long timeoutMs) {
        if (mPm == null) return;
        try {
            if (mFaceDownWakeLock == null) {
                mFaceDownWakeLock = mPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":PostureBurst");
                mFaceDownWakeLock.setReferenceCounted(false);
            }
            mFaceDownWakeLock.acquire(Math.max(500L, Math.min(timeoutMs, BURST_WAKELOCK_MAX_MS)));
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

    private final SensorEventListener mProxListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0 || mProxSensor == null) return;
            boolean near = event.values[0] < mProxSensor.getMaximumRange() && event.values[0] < 5.0f;
            boolean prev = mProximityNear;
            mProximityNear = near;
            if (near != prev) {
                if (near && cfgFlipOff && !mFaceDownPaused) {
                    startAccelBurst(3000L, true);
                } else if (!near) {
                    if (mFaceDownPaused) {
                        startAccelBurst(2500L, true);
                    }
                    if (isWallpaperAodVisible() && cfgAutoBright) {
                        onLightUncovered();
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SensorEventListener mTiltListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!cfgEnabled || (!cfgFlipOff && !cfgLiftWake)) return;
            startAccelBurst(2500L, true);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final TriggerEventListener mPickupTrigger = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            mPickupTriggerRegistered = false;
            mH.post(() -> onPickupTriggeredMain());
        }
    };

    private void onPickupTriggeredMain() {
        if (!cfgEnabled || (!cfgLiftWake && !mFaceDownPaused)) {
            return;
        }
        if (mProximityNear) {
            log("pickup ignored (proximity near)");
            mH.postDelayed(this::armPickupTriggerIfNeeded, 1000L);
            return;
        }
        if (mFaceDownPaused) {
            log("pickup while faceDownPaused -> burst verify orientation");
            startAccelBurst(2500L, true);
        } else if (cfgLiftWake && !isScreenOn()) {
            wakeUpScreen("pickup");
        }
        mH.postDelayed(this::armPickupTriggerIfNeeded, 1000L);
    }

    private final SensorEventListener mAccelListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 3) return;
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];

            if (!mGravityInit) {
                mGx = ax;
                mGy = ay;
                mGz = az;
                mGravityInit = true;
            } else {
                mGx = 0.7f * mGx + 0.3f * ax;
                mGy = 0.7f * mGy + 0.3f * ay;
                mGz = 0.7f * mGz + 0.3f * az;
            }
            if (!mBaseGravityInit) {
                mBaseGx = mGx;
                mBaseGy = mGy;
                mBaseGz = mGz;
                mBaseGravityInit = true;
            }

            long now = SystemClock.elapsedRealtime();

            // 1. Check face-down (flipOff)
            if (cfgFlipOff && !mFaceDownPaused && mAod) {
                boolean faceDown = mGz < -7.2f || (mProximityNear && mGz < -5.8f);
                if (faceDown) {
                    if (mFaceDownSince == 0L) mFaceDownSince = now;
                    if (now - mFaceDownSince >= 750L) {
                        enterFaceDownAodOff();
                        stopAccelBurst();
                        return;
                    }
                } else {
                    mFaceDownSince = 0L;
                }
            }

            // 2. Check face-up resume from face-down pause
            if (mFaceDownPaused) {
                boolean faceUp = mGz > 3.5f && !mProximityNear;
                if (faceUp) {
                    if (mFaceUpSince == 0L) mFaceUpSince = now;
                    if (now - mFaceUpSince >= 250L) {
                        exitFaceDownAodResume();
                        if (cfgLiftWake && !isScreenOn() && !mProximityNear && Math.abs(mGy) > 3.5f) {
                            wakeUpScreen("flip_lift");
                        }
                        stopAccelBurst();
                        return;
                    }
                } else {
                    mFaceUpSince = 0L;
                }
            }

            // 3. Fallback tilt lift-to-wake when hardware pickup didn't fire (e.g. continuous hold lift)
            if (cfgLiftWake && !mFaceDownPaused && !isScreenOn() && !mProximityNear
                    && (now - mSensorStartElapsed > 350L)) {
                float dot = mGx * mBaseGx + mGy * mBaseGy + mGz * mBaseGz;
                float n1 = (float) Math.sqrt(mGx * mGx + mGy * mGy + mGz * mGz);
                float n2 = (float) Math.sqrt(mBaseGx * mBaseGx + mBaseGy * mBaseGy + mBaseGz * mBaseGz);
                if (n1 > 5f && n2 > 5f) {
                    float cos = clampFloat(dot / (n1 * n2), -1f, 1f);
                    double deg = Math.toDegrees(Math.acos(cos));
                    if (deg >= 38.0 && Math.abs(mGy) > 4.5f && mGz > 1.5f) {
                        wakeUpScreen("tilt_burst");
                        stopAccelBurst();
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void enterFaceDownAodOff() {
        if (mFaceDownPaused) return;
        mFaceDownPaused = true;
        mFaceDownSince = SystemClock.elapsedRealtime();
        mFaceUpSince = 0L;
        log("enterFaceDownAodOff (keep system clock untouched!)");
        updateLightSensor();
        animateTo(0f, Math.min(cfgAnimOut, 200));
        stopTick();
        mH.post(this::requestAodPaused);
        armPickupTriggerIfNeeded();
    }

    private void exitFaceDownAodResume() {
        if (!mFaceDownPaused) return;
        mFaceDownPaused = false;
        mFaceUpSince = SystemClock.elapsedRealtime();
        log("exitFaceDownAodResume");
        mH.post(this::requestAodResume);
        applyAod(false);
    }

    private void wakeUpScreen(String reason) {
        if (mPm == null || !cfgEnabled || !cfgLiftWake) return;
        if (isScreenOn() || mProximityNear) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastLiftWakeTs < 2000L) return;
        mLastLiftWakeTs = now;
        mLastMotionTs = now;

        log("wakeUpScreen reason=" + reason);
        try {
            if (mWakeUpMethod == null) {
                try {
                    mWakeUpMethod = PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class);
                    mWakeUpArgCount = 3;
                } catch (Throwable e1) {
                    try {
                        mWakeUpMethod = PowerManager.class.getMethod("wakeUp", long.class, String.class);
                        mWakeUpArgCount = 2;
                    } catch (Throwable e2) {
                        mWakeUpMethod = PowerManager.class.getMethod("wakeUp", long.class);
                        mWakeUpArgCount = 1;
                    }
                }
            }
            long uptime = SystemClock.uptimeMillis();
            if (mWakeUpArgCount == 3) {
                mWakeUpMethod.invoke(mPm, uptime, 16, TAG + ":" + reason);
            } else if (mWakeUpArgCount == 2) {
                mWakeUpMethod.invoke(mPm, uptime, TAG + ":" + reason);
            } else {
                mWakeUpMethod.invoke(mPm, uptime);
            }
            return;
        } catch (Throwable ignored) {
        }

        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = mPm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    TAG + ":LiftWake");
            wl.acquire(1000L);
            wl.release();
        } catch (Throwable t) {
            log("wakeUpScreen fallback err: " + t);
        }
    }

    private boolean isScreenOn() {
        if (mPm == null) return true;
        try {
            return mPm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private float readScrimFloat(Object sc, String field) {
        try {
            return XposedHelpers.getFloatField(sc, field);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private float readScrimViewAlpha(Object sc, String field) {
        try {
            Object v = XposedHelpers.getObjectField(sc, field);
            if (v instanceof View) return ((View) v).getAlpha();
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mH.post(r);
        }
    }

    private void loadBaseWallpaperScale() {
        try {
            android.content.res.Resources sys = android.content.res.Resources.getSystem();
            int id = sys.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
            if (id != 0) {
                android.util.TypedValue tv = new android.util.TypedValue();
                sys.getValue(id, tv, true);
                float v = (tv.type == android.util.TypedValue.TYPE_FLOAT)
                        ? tv.getFloat()
                        : sys.getFloat(id);
                if (!Float.isNaN(v) && v >= 1.0f && v <= 2.0f) {
                    if (Math.abs(mBaseWallpaperScale - v) > 0.0005f) {
                        log("baseWallpaperScale=" + v);
                    }
                    mBaseWallpaperScale = v;
                    return;
                }
            }
        } catch (Throwable t) {
            log("loadBaseWallpaperScale err: " + t);
        }
        mBaseWallpaperScale = 1.0f;
    }

    private float getTargetWallpaperScale() {
        return mBaseWallpaperScale * cfgScale;
    }

    private float lerpWallpaperScale(float v) {
        return mBaseWallpaperScale * (1f + (cfgScale - 1f) * v);
    }

    private void applyViewScale(float s) {
        if (mView != null) {
            mView.setScaleX(s);
            mView.setScaleY(s);
        }
    }

    private void ensureLockscreenCenterOffset() {
        if (mCtx == null || mRoot == null) return;
        try {
            if (!mRoot.isAttachedToWindow()) return;
            android.os.IBinder tok = mRoot.getWindowToken();
            if (tok == null || !tok.isBinderAlive()) return;
            boolean isRtl = mRoot.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(mCtx);
            if (wm != null) {
                wm.setWallpaperOffsetSteps(0.0f, 1.0f);
                wm.setWallpaperOffsets(tok, isRtl ? 1.0f : 0.0f, 0.5f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static float clampFloat(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
