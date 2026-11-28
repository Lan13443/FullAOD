package com.operit.fullaod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.operit.fullaod.ui.ThemeEngine;
import com.operit.fullaod.ui.UiWidgets.BackdropStageLayout;
import com.operit.fullaod.ui.UiWidgets.BlurTopBarView;
import com.operit.fullaod.ui.UiWidgets.HeroStatusCardView;
import com.operit.fullaod.ui.UiWidgets.LiquidGlassBottomBarView;
import com.operit.fullaod.ui.UiWidgets.ModernSliderView;
import com.operit.fullaod.ui.UiWidgets.ModernSwitchView;
import com.operit.fullaod.ui.UiWidgets.ThemeMockupView;
import com.operit.fullaod.ui.UiWidgets.VectorIconView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int SUBPAGE_NONE = 0;
    private static final int SUBPAGE_DISPLAY = 1;
    private static final int SUBPAGE_ANIMATION = 2;
    private static final int SUBPAGE_NIGHT = 3;
    private static final int SUBPAGE_TIMEOUT = 4;
    private static final int SUBPAGE_SHADE = 5;
    private static final int SUBPAGE_THEME = 6;

    private SharedPreferences mSp;
    private ThemeEngine mTheme;

    private FrameLayout mWindowRoot;
    private BackdropStageLayout mBackdropStage;
    private FrameLayout mMainPagesHost;
    private ScrollView mHomeScroll;
    private ScrollView mSettingsScroll;
    private FrameLayout mSubPageOverlay;
    private ScrollView mSubPageScroll;

    private BlurTopBarView mTopBar;
    private LiquidGlassBottomBarView mBottomBar;

    private int mCurrentTab = 0; // 0 = 主页, 1 = 设置
    private int mCurrentSubPage = SUBPAGE_NONE;
    private int mStatusBarInset = 0;
    private int mNavBarInset = 0;

    // Live UI references for instant sync
    private HeroStatusCardView mHeroCard;
    private ModernSwitchView mHomeMasterSwitch;
    private TextView mHomeProfileSummaryTv;

    private TextView mSubDisplaySummaryTv;
    private TextView mSubAnimSummaryTv;
    private TextView mSubNightSummaryTv;
    private TextView mSubTimeoutSummaryTv;
    private TextView mSubShadeSummaryTv;

    // Predictive Back & Gesture state
    private Object mBackCallback;
    private boolean mBackCallbackRegistered = false;

    private float mTouchDownX;
    private float mTouchDownY;
    private boolean mIsEdgeSwipingSubPage = false;
    private boolean mIsSwipingMainTabs = false;
    private boolean mGestureLockedVertical = false;
    private int mTouchSlop;

    // Live Ambient Light Sensor Preview (only active on SUBPAGE_DISPLAY)
    private SensorManager mUiSensorManager;
    private Sensor mUiLightSensor;
    private boolean mUiLightRegistered = false;
    private float mUiLastLux = -1f;
    private long mLastUiLuxUpdateTs = 0L;
    private WallpaperAodView mPreviewAodView;
    private TextView mPreviewLuxPillTv;
    private ValueAnimator mPreviewMaskAnim;
    private float mPreviewCurrentMask = 0.65f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSp = Prefs.sp(this);
        importFromSecureSettingsIfFirstRun();
        pushToSettings();

        mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (b != null) {
            mCurrentTab = b.getInt("tab", 0);
            mCurrentSubPage = b.getInt("subpage", SUBPAGE_NONE);
        }

        mTheme = new ThemeEngine(this);
        buildUiHierarchy();
        configureEdgeToEdgeWindow();
        updateBackCallbackRegistration();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", mCurrentTab);
        outState.putInt("subpage", mCurrentSubPage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDynamicSummaries();
        if (mCurrentSubPage == SUBPAGE_DISPLAY) {
            startPreviewLightSensor();
            updateDisplayPreview(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreviewLightSensor();
    }

    @Override
    protected void onDestroy() {
        stopPreviewLightSensor();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuildThemeInPlace();
    }

    /**
     * 修复首启不同步缺陷（详见 docs/UI-STRUCTURE.md 第 3 节）：
     * 若本地 SharedPreferences 尚未初始化，先从 Settings.Secure "fullaod_cfg" 导入当前线上 22 个字段。
     */
    private void importFromSecureSettingsIfFirstRun() {
        if (!mSp.contains(Prefs.K_ENABLED) || mSp.getAll().isEmpty()) {
            syncFromSecureSettings(false);
        }
    }

    private boolean syncFromSecureSettings(boolean showToast) {
        try {
            String cur = Settings.Secure.getString(getContentResolver(), "fullaod_cfg");
            if (cur != null && !cur.trim().isEmpty()) {
                JSONObject o = new JSONObject(cur);
                applyJsonToPrefs(o);
                if (showToast) {
                    Toast.makeText(this, "已从系统配置同步最新状态", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        } catch (Throwable t) {
            Log.w("FullAOD", "syncFromSecureSettings fail", t);
        }
        return false;
    }

    private static int clampRange(int val, int min, int max) {
        return val < min ? min : (val > max ? max : val);
    }

    private void applyJsonToPrefs(JSONObject o) {
        int mask = clampRange((int) Math.round(o.optDouble("mask", Prefs.D_MASK / 100.0) * 100), 0, 95);
        int bright = clampRange((int) Math.round(o.optDouble("bright", Prefs.D_BRIGHT / 100.0) * 100), 100, 300);
        int ns = clampRange(o.optInt("nightStart", Prefs.D_NS), 0, 23);
        int ne = clampRange(o.optInt("nightEnd", Prefs.D_NE), 0, 23);
        int animIn = clampRange(o.optInt("animIn", Prefs.D_IN), 150, 1500);
        int animOut = clampRange(o.optInt("animOut", Prefs.D_OUT), 100, 1000);
        int scale = clampRange((int) Math.round(o.optDouble("scale", Prefs.D_SCALE / 100.0) * 100), 100, 110);
        int shadeMask = clampRange((int) Math.round(o.optDouble("shadeMask", Prefs.D_SHADE_MASK / 100.0) * 100), 0, 95);
        int timeoutMin = clampRange(o.optInt("aodTimeoutMin", Prefs.D_TIMEOUT_MIN), 5, 180);
        int timeoutMode = clampRange(o.optInt("aodTimeoutMode", Prefs.D_TIMEOUT_MODE), 0, 1);
        int nightMode = clampRange(o.optInt("nightMode", Prefs.D_NIGHT_MODE), 0, 1);
        int autoMin = clampRange(o.optInt("autoBrightMin", Prefs.D_AUTO_BRIGHT_MIN), 1, 50);
        int autoMax = clampRange(o.optInt("autoBrightMax", Prefs.D_AUTO_BRIGHT_MAX), 20, 100);
        if (autoMax < autoMin) autoMax = autoMin;

        mSp.edit()
                .putBoolean(Prefs.K_ENABLED, o.optBoolean("enabled", Prefs.D_ENABLED))
                .putInt(Prefs.K_MASK, mask)
                .putInt(Prefs.K_BRIGHT, bright)
                .putBoolean(Prefs.K_SHIFT, o.optBoolean("shift", Prefs.D_SHIFT))
                .putBoolean(Prefs.K_NIGHT, o.optBoolean("night", Prefs.D_NIGHT))
                .putInt(Prefs.K_NS, ns)
                .putInt(Prefs.K_NE, ne)
                .putInt(Prefs.K_IN, animIn)
                .putInt(Prefs.K_OUT, animOut)
                .putInt(Prefs.K_SCALE, scale)
                .putBoolean(Prefs.K_SHADE, o.optBoolean("shade", Prefs.D_SHADE))
                .putInt(Prefs.K_SHADE_MASK, shadeMask)
                .putBoolean(Prefs.K_TIMEOUT_ON, o.optBoolean("aodTimeoutOn", Prefs.D_TIMEOUT_ON))
                .putInt(Prefs.K_TIMEOUT_MIN, timeoutMin)
                .putInt(Prefs.K_TIMEOUT_MODE, timeoutMode)
                .putInt(Prefs.K_NIGHT_MODE, nightMode)
                .putBoolean(Prefs.K_NIGHT_SMART, o.optBoolean("nightSmart", Prefs.D_NIGHT_SMART))
                .putBoolean(Prefs.K_FLIP_OFF, o.optBoolean("flipOff", Prefs.D_FLIP_OFF))
                .putBoolean(Prefs.K_LIFT_WAKE, o.optBoolean("liftWake", Prefs.D_LIFT_WAKE))
                .putBoolean(Prefs.K_AUTO_BRIGHT, o.optBoolean("autoBright", Prefs.D_AUTO_BRIGHT))
                .putInt(Prefs.K_AUTO_BRIGHT_MIN, autoMin)
                .putInt(Prefs.K_AUTO_BRIGHT_MAX, autoMax)
                .apply();
    }

    private void configureEdgeToEdgeWindow() {
        try {
            Window w = getWindow();
            if (w == null) return;
            View decor = w.getDecorView();
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController ic = decor != null ? decor.getWindowInsetsController() : null;
                if (ic != null) {
                    int lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(mTheme.isDark ? 0 : lightFlags, lightFlags);
                }
            } else if (decor != null) {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (!mTheme.isDark) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Log.w("FullAOD", "configureEdgeToEdgeWindow fail", t);
        }
    }

    public void rebuildThemeInPlace() {
        stopPreviewLightSensor();
        mPreviewAodView = null;
        mPreviewLuxPillTv = null;
        int homeScrollY = mHomeScroll != null ? mHomeScroll.getScrollY() : 0;
        int settingsScrollY = mSettingsScroll != null ? mSettingsScroll.getScrollY() : 0;
        final int subScrollY = mSubPageScroll != null ? mSubPageScroll.getScrollY() : 0;

        mTheme = new ThemeEngine(this);
        buildUiHierarchy();
        configureEdgeToEdgeWindow();

        if (mHomeScroll != null) mHomeScroll.setScrollY(homeScrollY);
        if (mSettingsScroll != null) mSettingsScroll.setScrollY(settingsScrollY);
        if (mSubPageScroll != null) {
            mSubPageScroll.post(new Runnable() {
                @Override
                public void run() {
                    if (mSubPageScroll != null) mSubPageScroll.scrollTo(0, subScrollY);
                }
            });
        }
        updateBackCallbackRegistration();
    }

    // =========================================================================
    // Root UI Hierarchy Construction
    // =========================================================================
    private void buildUiHierarchy() {
        mWindowRoot = new FrameLayout(this);
        mWindowRoot.setBackgroundColor(mTheme.bgColor);

        // Stage containing main tabs + sub-page overlay (serves as the backdrop source for Top Blur & Liquid Glass Bottom Bar)
        mBackdropStage = new BackdropStageLayout(this, mTheme.bgColor);
        mWindowRoot.addView(mBackdropStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mMainPagesHost = new FrameLayout(this);
        mBackdropStage.addView(mMainPagesHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mHomeScroll = buildHomePage();
        mSettingsScroll = buildSettingsPage();

        mMainPagesHost.addView(mHomeScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mMainPagesHost.addView(mSettingsScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mSubPageOverlay = new FrameLayout(this);
        mSubPageOverlay.setVisibility(View.GONE);
        mSubPageOverlay.setBackgroundColor(mTheme.bgColor);
        mBackdropStage.addView(mSubPageOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top Real-Time Blur Bar
        mTopBar = new BlurTopBarView(this, mTheme);
        mTopBar.setBackdropStage(mBackdropStage);
        mTopBar.setOnTopActionListener(new BlurTopBarView.OnTopActionListener() {
            @Override
            public void onLeftClicked() {
                if (mCurrentSubPage != SUBPAGE_NONE) {
                    closeSubPage(true);
                }
            }

            @Override
            public void onRightClicked() {
                if (mCurrentSubPage == SUBPAGE_NONE && mCurrentTab == 0) {
                    boolean synced = syncFromSecureSettings(false);
                    pushToSettings();
                    refreshDynamicSummaries();
                    Toast.makeText(MainActivity.this,
                            synced ? "模块状态已刷新，Settings.Secure 通道正常" : "状态已刷新并同步至 Settings.Secure",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        mWindowRoot.addView(mTopBar, topLp);

        // Bottom Floating Liquid Glass Navigation Bar
        mBottomBar = new LiquidGlassBottomBarView(this, mTheme);
        mBottomBar.setBackdropStage(mBackdropStage);
        mBottomBar.setSelectedTab(mCurrentTab, false, false);
        mBottomBar.setOnTabSelectedListener(new LiquidGlassBottomBarView.OnTabSelectedListener() {
            @Override
            public void onTabSelected(int index) {
                switchMainTab(index, true);
            }
        });
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        mWindowRoot.addView(mBottomBar, bottomLp);

        mBackdropStage.setOverlays(mTopBar, mBottomBar);

        // Apply system window insets for status bar and gesture nav bar
        if (mStatusBarInset <= 0) mStatusBarInset = getDefaultStatusBarHeight();
        if (mNavBarInset <= 0) mNavBarInset = dp(16);
        applyInsetsPadding();

        mWindowRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
                    mStatusBarInset = sys.top;
                    mNavBarInset = sys.bottom;
                } else {
                    mStatusBarInset = insets.getSystemWindowInsetTop();
                    mNavBarInset = insets.getSystemWindowInsetBottom();
                }
                applyInsetsPadding();
                return insets;
            }
        });

        setContentView(mWindowRoot);
        mWindowRoot.requestApplyInsets();

        // Restore active tab & sub-page state
        switchMainTab(mCurrentTab, false);
        if (mCurrentSubPage != SUBPAGE_NONE) {
            openSubPage(mCurrentSubPage, false);
        } else {
            syncTopBarWithActivePage();
        }
        refreshDynamicSummaries();
    }

    private void applyInsetsPadding() {
        if (mTopBar != null) {
            mTopBar.setStatusBarInset(mStatusBarInset);
        }
        if (mBottomBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mBottomBar.getLayoutParams();
            boolean floating = ThemeEngine.isFloatingBar(this);
            lp.bottomMargin = floating ? (Math.max(dp(10), mNavBarInset) + dp(4)) : 0;
            mBottomBar.setLayoutParams(lp);
        }
        int topContentPad = mStatusBarInset + dp(52);
        int bottomContentPad = mNavBarInset + dp(104);
        if (mHomeScroll != null && mHomeScroll.getChildCount() > 0) {
            mHomeScroll.getChildAt(0).setPadding(dp(16), topContentPad, dp(16), bottomContentPad);
        }
        if (mSettingsScroll != null && mSettingsScroll.getChildCount() > 0) {
            mSettingsScroll.getChildAt(0).setPadding(dp(16), topContentPad, dp(16), bottomContentPad);
        }
        if (mSubPageScroll != null && mSubPageScroll.getChildCount() > 0) {
            mSubPageScroll.getChildAt(0).setPadding(dp(16), topContentPad, dp(16), mNavBarInset + dp(40));
        }
    }

    private int getDefaultStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return getResources().getDimensionPixelSize(resId);
        }
        return dp(28);
    }

    // =========================================================================
    // Screen 1: 主页 (Home Page — Image 2 Layout + Module Master Switch)
    // =========================================================================
    private ScrollView buildHomePage() {
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Large Page Title "主页"
        final TextView bigTitle = buildLargePageTitle("主页");
        root.addView(bigTitle);

        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (mCurrentSubPage == SUBPAGE_NONE && mCurrentTab == 0) {
                    float fade = Math.min(1f, Math.max(0f, scrollY / (float) dp(44)));
                    bigTitle.setAlpha(1f - fade * 0.85f);
                    mTopBar.setScrollFade(fade);
                }
            }
        });

        // 2. Module Running Status Hero Card (Image 2 style with large checkmark watermark)
        boolean enabled = mSp.getBoolean(Prefs.K_ENABLED, Prefs.D_ENABLED);
        mHeroCard = new HeroStatusCardView(this, mTheme, enabled);
        mHeroCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCurrentEffectiveConfigDialog();
            }
        });
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(134));
        heroLp.bottomMargin = dp(14);
        root.addView(mHeroCard, heroLp);

        // 3. Master Switch Card right below Module Status Card (User Requirement!)
        LinearLayout masterCard = createRoundedCard();
        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setPadding(dp(18), dp(16), dp(18), dp(16));

        // Icon badge on left
        FrameLayout iconBadge = new FrameLayout(this);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(mTheme.primaryContainer);
        iconBadge.setBackground(badgeBg);
        VectorIconView powerIcon = new VectorIconView(this, VectorIconView.ICON_AOD_POWER, mTheme.onPrimaryContainer);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        iconLp.gravity = Gravity.CENTER;
        iconBadge.addView(powerIcon, iconLp);

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        badgeLp.rightMargin = dp(14);
        masterRow.addView(iconBadge, badgeLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textColLp.rightMargin = dp(12);

        TextView masterTitle = new TextView(this);
        masterTitle.setText("模块总开关（启用全屏 AOD）");
        masterTitle.setTextColor(mTheme.textPrimary);
        masterTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        masterTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(masterTitle);

        TextView masterSub = new TextView(this);
        masterSub.setText("开启后熄屏显示主壁纸并保留原生 AOD 时钟，下次熄屏生效");
        masterSub.setTextColor(mTheme.textSecondary);
        masterSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        masterSub.setPadding(0, dp(3), 0, 0);
        textCol.addView(masterSub);

        masterRow.addView(textCol, textColLp);

        mHomeMasterSwitch = new ModernSwitchView(this, mTheme, enabled);
        mHomeMasterSwitch.setOnCheckedChangeListener(new ModernSwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                mSp.edit().putBoolean(Prefs.K_ENABLED, isChecked).apply();
                pushToSettings();
                refreshDynamicSummaries();
            }
        });
        masterRow.addView(mHomeMasterSwitch);

        masterRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mHomeMasterSwitch.performClick();
            }
        });
        masterCard.addView(masterRow);
        root.addView(masterCard, createCardMarginParams(14));

        // 4. Device Info Card (Image 2 stacked layout: 设备名称, 设备型号, Android ROM 版本, Android 版本)
        LinearLayout deviceCard = createRoundedCard();
        deviceCard.setPadding(dp(20), dp(14), dp(20), dp(14));

        addDeviceInfoBlock(deviceCard, "设备名称", ThemeEngine.getDeviceName(this), true);
        addDeviceInfoBlock(deviceCard, "设备型号", ThemeEngine.getDeviceModel(), false);
        addDeviceInfoBlock(deviceCard, "Android ROM 版本", ThemeEngine.getRomVersion(), false);
        addDeviceInfoBlock(deviceCard, "Android 版本", ThemeEngine.getAndroidVersion(), false);

        root.addView(deviceCard, createCardMarginParams(14));

        // 5. Quick Active Profile & Wallpaper Preview Entry Card (Image 2 bottom card style)
        LinearLayout previewEntryCard = createRoundedCard();
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(dp(20), dp(16), dp(18), dp(16));

        LinearLayout pvTextCol = new LinearLayout(this);
        pvTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pvColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView pvTitle = new TextView(this);
        pvTitle.setText("当前熄屏渲染策略");
        pvTitle.setTextColor(mTheme.textPrimary);
        pvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        pvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pvTextCol.addView(pvTitle);

        mHomeProfileSummaryTv = new TextView(this);
        mHomeProfileSummaryTv.setTextColor(mTheme.textSecondary);
        mHomeProfileSummaryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mHomeProfileSummaryTv.setPadding(0, dp(3), 0, 0);
        pvTextCol.addView(mHomeProfileSummaryTv);

        previewRow.addView(pvTextCol, pvColLp);
        previewRow.addView(new VectorIconView(this, VectorIconView.ICON_CHEVRON_RIGHT, mTheme.textSecondary));

        previewRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSubPage(SUBPAGE_DISPLAY, true);
            }
        });
        previewEntryCard.addView(previewRow);
        root.addView(previewEntryCard, createCardMarginParams(14));

        return sv;
    }

    private void addDeviceInfoBlock(LinearLayout card, String label, String value, boolean first) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, first ? dp(6) : dp(14), 0, dp(6));

        TextView tLabel = new TextView(this);
        tLabel.setText(label);
        tLabel.setTextColor(mTheme.textPrimary);
        tLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tLabel.setTypeface(Typeface.DEFAULT_BOLD);
        block.addView(tLabel);

        TextView tValue = new TextView(this);
        tValue.setText(value);
        tValue.setTextColor(mTheme.textSecondary);
        tValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tValue.setPadding(0, dp(2), 0, 0);
        block.addView(tValue);

        card.addView(block);
    }

    // =========================================================================
    // Screen 2: 设置 (Settings Page — Image 3 Layout with Secondary Menus)
    // =========================================================================
    private ScrollView buildSettingsPage() {
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView bigTitle = buildLargePageTitle("设置");
        root.addView(bigTitle);

        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (mCurrentSubPage == SUBPAGE_NONE && mCurrentTab == 1) {
                    float fade = Math.min(1f, Math.max(0f, scrollY / (float) dp(44)));
                    bigTitle.setAlpha(1f - fade * 0.85f);
                    mTopBar.setScrollFade(fade);
                }
            }
        });

        // ---------------------------------------------------------------------
        // Group 1: 显示与防烧屏 (All Core Display, Animation & Burn-in Settings)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("显示与动效"));
        LinearLayout displayCard = createRoundedCard();

        mSubDisplaySummaryTv = addNavRow(displayCard,
                "壁纸显示与亮度",
                "调节熄屏壁纸暗化遮罩、AOD 亮度增益与景深缩放，支持实时壁纸预览",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_DISPLAY, true);
                    }
                });

        mSubAnimSummaryTv = addNavRow(displayCard,
                "过渡动画时长",
                "自定义熄屏淡入与亮屏唤醒的动画曲线耗时，打造流畅锁屏过渡",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_ANIMATION, true);
                    }
                });

        addPrefSwitchRow(displayCard,
                "像素位移防烧屏",
                "每 90 秒在 ±3px 范围内周期微移壁纸，保护 OLED 屏幕避免静态残影",
                Prefs.K_SHIFT, Prefs.D_SHIFT);

        root.addView(displayCard, createCardMarginParams(10));

        // ---------------------------------------------------------------------
        // Group 1.5: 姿态与智能感应 (Flip-down AOD Off & Lift-to-Wake)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("姿态与智能感应"));
        LinearLayout postureCard = createRoundedCard();

        addPrefSwitchRow(postureCard,
                "反扣手机自动关闭所有 AOD",
                "当手机屏幕朝下反扣放置时，自动关闭全部 AOD（包含系统时钟与全屏壁纸）；翻转正放后自动恢复显示",
                Prefs.K_FLIP_OFF, Prefs.D_FLIP_OFF);

        addPrefSwitchRow(postureCard,
                "拿起手机自动亮屏",
                "熄屏或 AOD 状态下通过姿态传感器检测拿起并抬起手机动作，自动唤醒并点亮锁屏界面",
                Prefs.K_LIFT_WAKE, Prefs.D_LIFT_WAKE);

        root.addView(postureCard, createCardMarginParams(10));

        // ---------------------------------------------------------------------
        // Group 2: 省电与自动退化策略 (Night Mode & Long AOD Timeout)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("省电与退化策略"));
        LinearLayout powerCard = createRoundedCard();

        mSubNightSummaryTv = addNavRow(powerCard,
                "夜间自动退化",
                "在睡眠时段自动隐藏全屏壁纸或关闭 AOD，支持次日首次解锁智能唤醒",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_NIGHT, true);
                    }
                });

        mSubTimeoutSummaryTv = addNavRow(powerCard,
                "长期亮屏自动退化",
                "AOD 持续显示达到设定时长后自动降级，防止长时间静置耗电",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_TIMEOUT, true);
                    }
                });

        root.addView(powerCard, createCardMarginParams(10));

        // ---------------------------------------------------------------------
        // Group 3: 锁屏与通知栏 (Notification Shade Wallpaper Passthrough)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("锁屏与通知栏"));
        LinearLayout shadeCard = createRoundedCard();

        mSubShadeSummaryTv = addNavRow(shadeCard,
                "锁屏与通知栏透出壁纸",
                "配置下拉通知栏是否透出自定义壁纸层以及通知遮罩浓度",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_SHADE, true);
                    }
                });

        root.addView(shadeCard, createCardMarginParams(10));

        // ---------------------------------------------------------------------
        // Group 4: 界面 (Theme, Monet, Liquid Glass & Desktop Icon — Image 3)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("界面"));
        LinearLayout uiCard = createRoundedCard();

        addNavRow(uiCard,
                "主题",
                "主题模式、Monet 动态取色、悬浮底栏与液态玻璃效果",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSubPage(SUBPAGE_THEME, true);
                    }
                });

        addCustomSwitchRow(uiCard,
                "隐藏桌面图标",
                "关闭时在桌面显示 FullAOD 图标；隐藏后仍可从 LSPosed 模块列表打开",
                ThemeEngine.isLauncherIconHidden(this),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        ThemeEngine.setLauncherIconHidden(MainActivity.this, isChecked);
                        Toast.makeText(MainActivity.this,
                                isChecked ? "已隐藏桌面图标，可从 LSPosed 管理器打开" : "已恢复在系统桌面显示图标",
                                Toast.LENGTH_SHORT).show();
                    }
                });

        addNavRow(uiCard,
                "固定快捷图标到桌面",
                "启用桌面图标并主动请求系统启动器添加 FullAOD 桌面快捷方式",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestPinDesktopShortcut();
                    }
                });

        root.addView(uiCard, createCardMarginParams(10));

        // ---------------------------------------------------------------------
        // Group 5: 调试与数据管理 (Debug, Export/Import JSON & Reset — Image 3)
        // ---------------------------------------------------------------------
        root.addView(buildSectionHeader("调试与数据管理"));
        LinearLayout dataCard = createRoundedCard();

        addNavRow(dataCard,
                "当前生效配置与救援说明",
                "查看 Settings.Secure 实时 JSON 数据通道、日志路径与按键救援方式",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCurrentEffectiveConfigDialog();
                    }
                });

        addNavRow(dataCard,
                "导出设置",
                "将当前 22 项 AOD 参数导出为 JSON 并复制到剪贴板",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showExportConfigDialog();
                    }
                });

        addNavRow(dataCard,
                "导入设置",
                "从 JSON 文本恢复配置并立即推送至系统 Settings.Secure",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showImportConfigDialog();
                    }
                });

        addNavRow(dataCard,
                "恢复默认值",
                "将所有全屏 AOD 参数重置为出厂默认状态（下次熄屏生效）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showResetConfirmDialog();
                    }
                });

        root.addView(dataCard, createCardMarginParams(14));

        // Subtle footer channel info
        TextView foot = new TextView(this);
        foot.setText("数据通道：Settings.Secure (fullaod_cfg) & content://com.operit.fullaod.config\n改动即时保存，下次熄屏生效（无需重启 SystemUI）");
        foot.setTextColor(mTheme.textTertiary);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(dp(8), dp(6), dp(8), dp(12));
        root.addView(foot);

        return sv;
    }

    // =========================================================================
    // Secondary Detail Pages (二级菜单 + 图4/图5 主题设置页)
    // =========================================================================
    private void openSubPage(int subPageId, boolean animate) {
        stopPreviewLightSensor();
        mPreviewAodView = null;
        mPreviewLuxPillTv = null;
        mCurrentSubPage = subPageId;
        mSubPageOverlay.removeAllViews();

        final String title = getSubPageTitle(subPageId);
        mSubPageScroll = new ScrollView(this);
        mSubPageScroll.setVerticalScrollBarEnabled(false);
        mSubPageScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int topContentPad = mStatusBarInset + dp(52);
        content.setPadding(dp(16), topContentPad, dp(16), mNavBarInset + dp(40));
        mSubPageScroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView bigTitle = buildLargePageTitle(title);
        content.addView(bigTitle);

        mSubPageScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (mCurrentSubPage != SUBPAGE_NONE) {
                    float fade = Math.min(1f, Math.max(0f, scrollY / (float) dp(44)));
                    bigTitle.setAlpha(1f - fade * 0.85f);
                    mTopBar.setScrollFade(fade);
                }
            }
        });

        switch (subPageId) {
            case SUBPAGE_DISPLAY:
                populateDisplaySubPage(content);
                break;
            case SUBPAGE_ANIMATION:
                populateAnimationSubPage(content);
                break;
            case SUBPAGE_NIGHT:
                populateNightSubPage(content);
                break;
            case SUBPAGE_TIMEOUT:
                populateTimeoutSubPage(content);
                break;
            case SUBPAGE_SHADE:
                populateShadeSubPage(content);
                break;
            case SUBPAGE_THEME:
                populateThemeSubPage(content);
                break;
        }

        mSubPageOverlay.addView(mSubPageScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSubPageOverlay.setVisibility(View.VISIBLE);
        mSubPageOverlay.setTranslationX(0f);
        mSubPageOverlay.setTranslationY(0f);
        mSubPageOverlay.setScaleX(1f);
        mSubPageOverlay.setScaleY(1f);
        mSubPageOverlay.setAlpha(1f);

        // Hide bottom floating bar while inside secondary detail page (matches Image 1, 4, 5)
        mBottomBar.setVisibility(View.GONE);
        mTopBar.configure(mTheme, title, true, false, 0f);
        updateBackCallbackRegistration();

        if (animate) {
            int w = getResources().getDisplayMetrics().widthPixels;
            mSubPageOverlay.setTranslationX(w * 0.28f);
            mSubPageOverlay.setAlpha(0f);
            mSubPageOverlay.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void closeSubPage(boolean animate) {
        if (mCurrentSubPage == SUBPAGE_NONE) return;
        stopPreviewLightSensor();
        refreshDynamicSummaries();
        if (!animate) {
            mCurrentSubPage = SUBPAGE_NONE;
            mPreviewAodView = null;
            mPreviewLuxPillTv = null;
            mSubPageOverlay.setVisibility(View.GONE);
            mSubPageOverlay.removeAllViews();
            mSubPageScroll = null;
            mMainPagesHost.setScaleX(1f);
            mMainPagesHost.setScaleY(1f);
            mMainPagesHost.setAlpha(1f);
            mMainPagesHost.setTranslationX(0f);
            mBottomBar.setVisibility(View.VISIBLE);
            syncTopBarWithActivePage();
            updateBackCallbackRegistration();
            return;
        }

        int w = getResources().getDisplayMetrics().widthPixels;
        mMainPagesHost.setVisibility(View.VISIBLE);
        mMainPagesHost.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationX(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        mSubPageOverlay.animate()
                .translationX(w * 0.35f)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mSubPageOverlay.animate().setListener(null);
                        mCurrentSubPage = SUBPAGE_NONE;
                        mPreviewAodView = null;
                        mPreviewLuxPillTv = null;
                        mSubPageOverlay.setVisibility(View.GONE);
                        mSubPageOverlay.removeAllViews();
                        mSubPageScroll = null;
                        mBottomBar.setVisibility(View.VISIBLE);
                        syncTopBarWithActivePage();
                        updateBackCallbackRegistration();
                    }
                })
                .start();
    }

    private String getSubPageTitle(int id) {
        switch (id) {
            case SUBPAGE_DISPLAY:
                return "壁纸显示与亮度";
            case SUBPAGE_ANIMATION:
                return "过渡动画时长";
            case SUBPAGE_NIGHT:
                return "夜间自动退化";
            case SUBPAGE_TIMEOUT:
                return "长期亮屏自动退化";
            case SUBPAGE_SHADE:
                return "锁屏与通知栏透出";
            case SUBPAGE_THEME:
                return "主题";
            default:
                return "";
        }
    }

    // -------------------------------------------------------------------------
    // SubPage 1: 壁纸显示与亮度 (Live WallpaperAodView + Ambient Light Preview)
    // -------------------------------------------------------------------------
    private void populateDisplaySubPage(LinearLayout root) {
        root.addView(buildSectionHeader("实时壁纸与环境光预览"));

        // Live WallpaperAodView Preview Card (docs/UI-STRUCTURE.md)
        LinearLayout previewCard = createRoundedCard();
        previewCard.setPadding(dp(14), dp(14), dp(14), dp(14));

        FrameLayout previewBox = new FrameLayout(this);
        GradientDrawable clipBg = new GradientDrawable();
        clipBg.setCornerRadius(dp(16));
        clipBg.setColor(0xFF0A0A0E);
        previewBox.setBackground(clipBg);
        previewBox.setClipToOutline(true);
        previewBox.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        mPreviewAodView = new WallpaperAodView(this);
        mPreviewAodView.setBitmap(loadPreviewWallpaperBitmap());
        mPreviewCurrentMask = mSp.getInt(Prefs.K_MASK, Prefs.D_MASK) / 100f;
        mPreviewAodView.setMaskAlpha(mPreviewCurrentMask);
        previewBox.addView(mPreviewAodView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(188)));

        // Simulated Pixel AOD Clock overlay inside preview
        LinearLayout clockOverlay = new LinearLayout(this);
        clockOverlay.setOrientation(LinearLayout.VERTICAL);
        clockOverlay.setGravity(Gravity.CENTER);
        clockOverlay.setPadding(0, 0, 0, dp(24));
        TextView clockTv = new TextView(this);
        clockTv.setText("09:41");
        clockTv.setTextColor(0xE6FFFFFF);
        clockTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        clockTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockOverlay.addView(clockTv);

        TextView dateTv = new TextView(this);
        dateTv.setText("11月22日 周日 · 全屏 AOD 预览示意图");
        dateTv.setTextColor(0xB3FFFFFF);
        dateTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clockOverlay.addView(dateTv);

        previewBox.addView(clockOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mPreviewLuxPillTv = new TextView(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(12));
        pillBg.setColor(0x99000000);
        mPreviewLuxPillTv.setBackground(pillBg);
        mPreviewLuxPillTv.setTextColor(0xF2FFFFFF);
        mPreviewLuxPillTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        mPreviewLuxPillTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pillLp.bottomMargin = dp(10);
        previewBox.addView(mPreviewLuxPillTv, pillLp);

        previewCard.addView(previewBox);
        root.addView(previewCard, createCardMarginParams(10));

        // Ambient Light Auto-Brightness Card
        root.addView(buildSectionHeader("环境光自动亮度"));
        LinearLayout autoCard = createRoundedCard();

        final List<View> autoDependentViews = new ArrayList<View>();
        final List<View> manualBrightViews = new ArrayList<View>();
        boolean autoOn = mSp.getBoolean(Prefs.K_AUTO_BRIGHT, Prefs.D_AUTO_BRIGHT);

        addCustomSwitchRow(autoCard,
                "环境光自动调节 AOD 亮度",
                "开启后按环境光自动在最低～最高区间调节硬件亮度，并联动深浅壁纸遮罩（暗光加深、强光减淡），仅全屏壁纸 AOD 期间低功耗采样",
                autoOn,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        mSp.edit().putBoolean(Prefs.K_AUTO_BRIGHT, isChecked).apply();
                        pushToSettings();
                        setDependentViewsEnabled(autoDependentViews, isChecked);
                        setDependentViewsEnabled(manualBrightViews, !isChecked);
                        refreshDynamicSummaries();
                        updateDisplayPreview(true);
                    }
                });

        final ModernSliderView[] rangeSliders = new ModernSliderView[2];

        View minBlock = addSliderBlock(autoCard,
                "最低硬件亮度",
                "极暗环境（如夜间关灯 0–2 lux）下压低到的硬件 Doze 亮度下限，保持微亮不刺眼",
                1, 50, Prefs.K_AUTO_BRIGHT_MIN, Prefs.D_AUTO_BRIGHT_MIN, "%",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (fromUser && rangeSliders[1] != null && value > rangeSliders[1].getValue()) {
                            rangeSliders[1].setValue(value, true);
                            mSp.edit().putInt(Prefs.K_AUTO_BRIGHT_MAX, value).apply();
                            pushToSettings();
                            refreshDynamicSummaries();
                        }
                        if (fromUser) {
                            updateDisplayPreview(false);
                        }
                    }
                });
        if (minBlock.getTag() instanceof ModernSliderView) {
            rangeSliders[0] = (ModernSliderView) minBlock.getTag();
        }
        autoDependentViews.add(minBlock);

        View maxBlock = addSliderBlock(autoCard,
                "最高硬件亮度",
                "户外强光环境下自动提升到的硬件 Doze 亮度上限，确保大太阳下壁纸与时钟清晰可读",
                20, 100, Prefs.K_AUTO_BRIGHT_MAX, Prefs.D_AUTO_BRIGHT_MAX, "%",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (fromUser && rangeSliders[0] != null && value < rangeSliders[0].getValue()) {
                            rangeSliders[0].setValue(value, true);
                            mSp.edit().putInt(Prefs.K_AUTO_BRIGHT_MIN, value).apply();
                            pushToSettings();
                            refreshDynamicSummaries();
                        }
                        if (fromUser) {
                            updateDisplayPreview(false);
                        }
                    }
                });
        if (maxBlock.getTag() instanceof ModernSliderView) {
            rangeSliders[1] = (ModernSliderView) maxBlock.getTag();
        }
        autoDependentViews.add(maxBlock);

        setDependentViewsEnabled(autoDependentViews, autoOn);
        root.addView(autoCard, createCardMarginParams(10));

        // Sliders Card
        root.addView(buildSectionHeader("视觉参数调节"));
        LinearLayout paramCard = createRoundedCard();

        addSliderBlock(paramCard,
                "壁纸暗化程度",
                "壁纸暗化遮罩浓度（0%–95%）。开启自动亮度时作为室内基准，暗光自动加深、强光自动减淡，推荐 60%–75%。",
                0, 95, Prefs.K_MASK, Prefs.D_MASK, "%",
                new ModernSliderView.OnValueChangeListener() {
                    @Override
                    public void onValueChanged(int value, boolean fromUser) {
                        if (fromUser) {
                            updateDisplayPreview(false);
                        }
                    }
                });

        View brightBlock = addSliderBlock(paramCard,
                "AOD 亮度倍数（手动模式）",
                "关闭自动亮度时生效：在原生 Doze 屏幕亮度基础上增益（100%–300%）。开启自动亮度时此项自动禁用。",
                100, 300, Prefs.K_BRIGHT, Prefs.D_BRIGHT, "%", null);
        manualBrightViews.add(brightBlock);
        setDependentViewsEnabled(manualBrightViews, !autoOn);

        addSliderBlock(paramCard,
                "放大倍率",
                "熄屏进入全屏 AOD 时壁纸的目标微距放大比例（100%–110%），配合淡入动画营造层次景深。",
                100, 110, Prefs.K_SCALE, Prefs.D_SCALE, "%", null);

        root.addView(paramCard, createCardMarginParams(14));

        startPreviewLightSensor();
        updateDisplayPreview(false);
    }

    private final SensorEventListener mUiLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0) return;
            if (mCurrentSubPage != SUBPAGE_DISPLAY) return;
            float raw = event.values[0];
            if (Float.isNaN(raw) || Float.isInfinite(raw)) return;
            float lux = Math.max(0f, raw);
            long now = SystemClock.elapsedRealtime();
            float prevNorm = (mUiLastLux >= 0f) ? AmbientLightCurve.luxToNorm(mUiLastLux) : -1f;
            float nextNorm = AmbientLightCurve.luxToNorm(lux);
            if (mUiLastLux >= 0f && (now - mLastUiLuxUpdateTs) < 120L && Math.abs(nextNorm - prevNorm) < 0.03f) {
                return;
            }
            mUiLastLux = lux;
            mLastUiLuxUpdateTs = now;
            updateDisplayPreview(true);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void startPreviewLightSensor() {
        try {
            if (mUiSensorManager == null) {
                mUiSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            }
            if (mUiLightSensor == null && mUiSensorManager != null) {
                mUiLightSensor = mUiSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            }
            if (mUiSensorManager != null && mUiLightSensor != null && !mUiLightRegistered) {
                mUiLightRegistered = mUiSensorManager.registerListener(
                        mUiLightListener, mUiLightSensor, SensorManager.SENSOR_DELAY_UI);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopPreviewLightSensor() {
        if (mPreviewMaskAnim != null) {
            mPreviewMaskAnim.cancel();
            mPreviewMaskAnim = null;
        }
        if (mUiLightRegistered && mUiSensorManager != null) {
            try {
                mUiSensorManager.unregisterListener(mUiLightListener);
            } catch (Throwable ignored) {
            }
            mUiLightRegistered = false;
        }
    }

    private void updateDisplayPreview(boolean animate) {
        if (mPreviewAodView == null) return;
        boolean autoOn = mSp.getBoolean(Prefs.K_AUTO_BRIGHT, Prefs.D_AUTO_BRIGHT);
        int baseMaskPct = mSp.getInt(Prefs.K_MASK, Prefs.D_MASK);
        int brightPct = mSp.getInt(Prefs.K_BRIGHT, Prefs.D_BRIGHT);
        int minPct = mSp.getInt(Prefs.K_AUTO_BRIGHT_MIN, Prefs.D_AUTO_BRIGHT_MIN);
        int maxPct = Math.max(minPct, mSp.getInt(Prefs.K_AUTO_BRIGHT_MAX, Prefs.D_AUTO_BRIGHT_MAX));

        final float targetMask;
        String pillText;
        if (!autoOn) {
            targetMask = baseMaskPct / 100f;
            pillText = "手动模式 · 倍数 " + brightPct + "% (" + String.format("%.1f×", brightPct / 100f)
                    + ") · 固定遮罩 " + baseMaskPct + "%";
        } else if (mUiLightSensor == null) {
            targetMask = baseMaskPct / 100f;
            pillText = "未检测到光线传感器 · 按基准遮罩 " + baseMaskPct + "%";
        } else if (mUiLastLux < 0f) {
            targetMask = baseMaskPct / 100f;
            pillText = "正在读取光线传感器… · 基准遮罩 " + baseMaskPct + "%";
        } else {
            int hwPct = AmbientLightCurve.computeBrightnessPct(mUiLastLux, minPct, maxPct);
            int hwInt = AmbientLightCurve.pctToDozeInt(hwPct);
            targetMask = AmbientLightCurve.computeDynamicMask(mUiLastLux, baseMaskPct / 100f);
            pillText = "☀ " + AmbientLightCurve.formatLux(mUiLastLux)
                    + " (" + AmbientLightCurve.describeLightLevel(mUiLastLux) + ")"
                    + " → 硬件 " + hwPct + "% (" + hwInt + "/255)"
                    + " · 遮罩 " + Math.round(targetMask * 100f) + "%";
        }

        if (mPreviewLuxPillTv != null) {
            mPreviewLuxPillTv.setText(pillText);
        }

        if (mPreviewMaskAnim != null) {
            mPreviewMaskAnim.cancel();
            mPreviewMaskAnim = null;
        }
        if (!animate || Math.abs(targetMask - mPreviewCurrentMask) < 0.008f) {
            mPreviewCurrentMask = targetMask;
            mPreviewAodView.setMaskAlpha(targetMask);
            return;
        }
        final float startMask = mPreviewCurrentMask;
        ValueAnimator anim = ValueAnimator.ofFloat(startMask, targetMask);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float v = (Float) animation.getAnimatedValue();
                mPreviewCurrentMask = v;
                if (mPreviewAodView != null) {
                    mPreviewAodView.setMaskAlpha(v);
                }
            }
        });
        mPreviewMaskAnim = anim;
        anim.start();
    }

    // -------------------------------------------------------------------------
    // SubPage 2: 过渡动画时长 (animIn, animOut)
    // -------------------------------------------------------------------------
    private void populateAnimationSubPage(LinearLayout root) {
        root.addView(buildSectionHeader("熄屏与唤醒过渡动画"));
        LinearLayout card = createRoundedCard();

        addSliderBlock(card,
                "熄屏过渡时长",
                "按下电源键或超时熄屏时，主屏幕壁纸淡入与微距缩放的动画持续时间（150–1500 ms，默认 550 ms）。",
                150, 1500, Prefs.K_IN, Prefs.D_IN, " ms", null);

        addSliderBlock(card,
                "亮屏过渡时长",
                "点亮屏幕或指纹解锁瞬间，AOD 暗化遮罩平滑淡出的动画耗时（100–1000 ms，默认 350 ms）。",
                100, 1000, Prefs.K_OUT, Prefs.D_OUT, " ms", null);

        root.addView(card, createCardMarginParams(14));
    }

    // -------------------------------------------------------------------------
    // SubPage 3: 夜间自动退化 (night, nightStart, nightEnd, nightMode, nightSmart)
    // -------------------------------------------------------------------------
    private void populateNightSubPage(LinearLayout root) {
        root.addView(buildSectionHeader("夜间省电策略"));
        LinearLayout masterCard = createRoundedCard();

        final List<View> dependentViews = new ArrayList<View>();
        boolean nightOn = mSp.getBoolean(Prefs.K_NIGHT, Prefs.D_NIGHT);

        addCustomSwitchRow(masterCard,
                "启用夜间退化",
                "在设定的夜间睡眠时段内暂停全屏壁纸显示，退化为仅时钟或完全关闭 AOD",
                nightOn,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        mSp.edit().putBoolean(Prefs.K_NIGHT, isChecked).apply();
                        pushToSettings();
                        setDependentViewsEnabled(dependentViews, isChecked);
                        refreshDynamicSummaries();
                    }
                });
        root.addView(masterCard, createCardMarginParams(10));

        root.addView(buildSectionHeader("生效时段与唤醒方式"));
        LinearLayout detailCard = createRoundedCard();
        dependentViews.add(detailCard);

        final TextView sameTimeWarnTv = new TextView(this);
        sameTimeWarnTv.setText("⚠️ 开始时间与结束时间相同时，系统将视为不启用固定夜间时段");
        sameTimeWarnTv.setTextColor(0xFFFFB4AB);
        sameTimeWarnTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sameTimeWarnTv.setPadding(dp(20), dp(10), dp(20), dp(4));

        final Runnable checkSameTime = new Runnable() {
            @Override
            public void run() {
                int ns = mSp.getInt(Prefs.K_NS, Prefs.D_NS);
                int ne = mSp.getInt(Prefs.K_NE, Prefs.D_NE);
                sameTimeWarnTv.setVisibility(ns == ne ? View.VISIBLE : View.GONE);
            }
        };

        addHourSliderAndPickerBlock(detailCard,
                "夜间开始（时）",
                "每日进入夜间退化模式的整点时刻（点击右侧时间可弹出时间选择器）",
                Prefs.K_NS, Prefs.D_NS, checkSameTime);

        addHourSliderAndPickerBlock(detailCard,
                "夜间结束（时）",
                "次日退出夜间退化模式的整点时刻（支持跨零点，开启智能模式后以首次解锁为准）",
                Prefs.K_NE, Prefs.D_NE, checkSameTime);

        detailCard.addView(sameTimeWarnTv);
        checkSameTime.run();

        addPrefSwitchIntRow(detailCard,
                "夜间退化方式：完全关闭 AOD",
                "开启时在夜间彻底关闭 AOD 屏幕；关闭时仅隐藏壁纸并保留原生系统时钟",
                Prefs.K_NIGHT_MODE, Prefs.D_NIGHT_MODE, 0, 1);

        addPrefSwitchRow(detailCard,
                "智能模式：次日 5:00 后首次解锁即结束",
                "次日 05:00 之后第一次点亮屏幕并成功解锁即结束当日退化（当天不再重复进入）",
                Prefs.K_NIGHT_SMART, Prefs.D_NIGHT_SMART);

        setDependentViewsEnabled(dependentViews, nightOn);
        root.addView(detailCard, createCardMarginParams(14));
    }

    // -------------------------------------------------------------------------
    // SubPage 4: 长期亮屏自动退化 (aodTimeoutOn, aodTimeoutMin, aodTimeoutMode)
    // -------------------------------------------------------------------------
    private void populateTimeoutSubPage(LinearLayout root) {
        root.addView(buildSectionHeader("长时间静置退化"));
        LinearLayout masterCard = createRoundedCard();

        final List<View> dependentViews = new ArrayList<View>();
        boolean timeoutOn = mSp.getBoolean(Prefs.K_TIMEOUT_ON, Prefs.D_TIMEOUT_ON);

        addCustomSwitchRow(masterCard,
                "启用超时退化",
                "当手机熄屏持续显示全屏 AOD 超过设定等待时间后，自动触发降级省电",
                timeoutOn,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        mSp.edit().putBoolean(Prefs.K_TIMEOUT_ON, isChecked).apply();
                        pushToSettings();
                        setDependentViewsEnabled(dependentViews, isChecked);
                        refreshDynamicSummaries();
                    }
                });
        root.addView(masterCard, createCardMarginParams(10));

        root.addView(buildSectionHeader("超时参数"));
        LinearLayout detailCard = createRoundedCard();
        dependentViews.add(detailCard);

        addSliderBlock(detailCard,
                "等待时间",
                "进入全屏 AOD 后持续多少分钟未唤醒即开始退化（5–180 分钟，默认 30 分钟）。",
                5, 180, Prefs.K_TIMEOUT_MIN, Prefs.D_TIMEOUT_MIN, " 分钟", null);

        addPrefSwitchIntRow(detailCard,
                "退化方式：完全关闭 AOD",
                "开启时超时后请求系统进入 DOZE_AOD_PAUSED 完全黑屏；关闭时退化为仅显示时钟",
                Prefs.K_TIMEOUT_MODE, Prefs.D_TIMEOUT_MODE, 0, 1);

        setDependentViewsEnabled(dependentViews, timeoutOn);
        root.addView(detailCard, createCardMarginParams(14));
    }

    // -------------------------------------------------------------------------
    // SubPage 5: 锁屏与通知栏透出 (shade, shadeMask)
    // -------------------------------------------------------------------------
    private void populateShadeSubPage(LinearLayout root) {
        root.addView(buildSectionHeader("锁屏与下拉通知栏"));
        LinearLayout card = createRoundedCard();

        final List<View> dependentViews = new ArrayList<View>();
        boolean shadeOn = mSp.getBoolean(Prefs.K_SHADE, Prefs.D_SHADE);

        addCustomSwitchRow(card,
                "锁屏/通知栏透出壁纸",
                "在锁屏或下拉通知抽屉时透出自定义壁纸背景层（当前线上默认关闭）",
                shadeOn,
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        mSp.edit().putBoolean(Prefs.K_SHADE, isChecked).apply();
                        pushToSettings();
                        setDependentViewsEnabled(dependentViews, isChecked);
                        refreshDynamicSummaries();
                    }
                });

        View maskBlock = addSliderBlock(card,
                "通知栏遮罩",
                "下拉通知栏时的黑色遮罩浓度（0%–95%），仅在开启「锁屏/通知栏透出壁纸」时生效。",
                0, 95, Prefs.K_SHADE_MASK, Prefs.D_SHADE_MASK, "%", null);
        dependentViews.add(maskBlock);

        setDependentViewsEnabled(dependentViews, shadeOn);
        root.addView(card, createCardMarginParams(14));
    }

    // -------------------------------------------------------------------------
    // SubPage 6: 主题设置页 (Exact Match to Image 4 & Image 5!)
    // -------------------------------------------------------------------------
    private void populateThemeSubPage(LinearLayout root) {
        final SharedPreferences uiSp = ThemeEngine.sp(this);

        // 1. Live Phone Mockup Preview (Image 4 center phone frame)
        final ThemeMockupView mockup = new ThemeMockupView(this, mTheme);
        root.addView(mockup);

        // 2. Segmented 3-Button Row: 跟随系统 | 浅色 | 深色 (Image 4)
        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        segRowLp.topMargin = dp(8);
        segRowLp.bottomMargin = dp(16);

        final String[] modeLabels = {"跟随系统", "浅色", "深色"};
        int curMode = uiSp.getInt(ThemeEngine.K_THEME_MODE, 0);
        for (int i = 0; i < 3; i++) {
            final int modeVal = i;
            TextView btn = new TextView(this);
            btn.setText(modeLabels[i]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            boolean active = (curMode == i);
            btn.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            btn.setTextColor(active ? mTheme.textPrimary : mTheme.textSecondary);

            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(12));
            if (active) {
                gd.setColor(mTheme.cardElevatedColor);
            } else {
                gd.setColor(0x00000000);
                gd.setStroke(dp(1), ThemeEngine.withAlpha(mTheme.textSecondary, 90));
            }
            btn.setBackground(gd);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    uiSp.edit().putInt(ThemeEngine.K_THEME_MODE, modeVal).apply();
                    rebuildThemeInPlace();
                }
            });

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i < 2) btnLp.rightMargin = dp(10);
            segRow.addView(btn, btnLp);
        }
        root.addView(segRow, segRowLp);

        // 3. Card 1: Monet & Color Palette (Image 4 & Image 5 top card)
        LinearLayout colorCard = createRoundedCard();

        addIconSwitchRow(colorCard,
                VectorIconView.ICON_MONET,
                "启用 Monet 配色",
                null,
                uiSp.getBoolean(ThemeEngine.K_MONET, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_MONET, isChecked).apply();
                        rebuildThemeInPlace();
                    }
                });

        int colorIdx = uiSp.getInt(ThemeEngine.K_COLOR_INDEX, 0);
        String colorName = (colorIdx >= 0 && colorIdx < ThemeEngine.COLOR_NAMES.length)
                ? ThemeEngine.COLOR_NAMES[colorIdx] : "紫色";
        addIconSelectorRow(colorCard,
                VectorIconView.ICON_PALETTE,
                "主题色",
                colorName,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSingleChoiceDialog("选择主题色", ThemeEngine.COLOR_NAMES,
                                uiSp.getInt(ThemeEngine.K_COLOR_INDEX, 0),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        uiSp.edit().putInt(ThemeEngine.K_COLOR_INDEX, which).apply();
                                        dialog.dismiss();
                                        rebuildThemeInPlace();
                                    }
                                });
                    }
                });

        int styleIdx = uiSp.getInt(ThemeEngine.K_COLOR_STYLE, 0);
        String styleName = (styleIdx >= 0 && styleIdx < ThemeEngine.STYLE_NAMES.length)
                ? ThemeEngine.STYLE_NAMES[styleIdx] : "TonalSpot";
        addIconSelectorRow(colorCard,
                VectorIconView.ICON_STYLE,
                "色彩风格",
                styleName,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSingleChoiceDialog("选择色彩风格", ThemeEngine.STYLE_NAMES,
                                uiSp.getInt(ThemeEngine.K_COLOR_STYLE, 0),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        uiSp.edit().putInt(ThemeEngine.K_COLOR_STYLE, which).apply();
                                        dialog.dismiss();
                                        rebuildThemeInPlace();
                                    }
                                });
                    }
                });

        int specIdx = uiSp.getInt(ThemeEngine.K_COLOR_SPEC, 0);
        String specName = (specIdx >= 0 && specIdx < ThemeEngine.SPEC_NAMES.length)
                ? ThemeEngine.SPEC_NAMES[specIdx] : "SPEC_2025";
        addIconSelectorRow(colorCard,
                VectorIconView.ICON_SPEC,
                "色彩规范",
                specName,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSingleChoiceDialog("选择色彩规范", ThemeEngine.SPEC_NAMES,
                                uiSp.getInt(ThemeEngine.K_COLOR_SPEC, 0),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        uiSp.edit().putInt(ThemeEngine.K_COLOR_SPEC, which).apply();
                                        dialog.dismiss();
                                        rebuildThemeInPlace();
                                    }
                                });
                    }
                });

        root.addView(colorCard, createCardMarginParams(14));

        // 4. Card 2: Blur, Floating Bottom Bar, Liquid Glass & Badge (Image 5 middle card)
        LinearLayout glassCard = createRoundedCard();

        addIconSwitchRow(glassCard,
                VectorIconView.ICON_BLUR,
                "模糊",
                "启用顶部及底部栏的模糊效果",
                uiSp.getBoolean(ThemeEngine.K_BLUR, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_BLUR, isChecked).apply();
                        mTopBar.invalidate();
                        mBottomBar.invalidate();
                        mockup.invalidate();
                    }
                });

        addIconSwitchRow(glassCard,
                VectorIconView.ICON_FLOAT_BAR,
                "悬浮底部栏",
                "使用 Apple 风格悬浮底部栏",
                uiSp.getBoolean(ThemeEngine.K_FLOATING_BAR, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_FLOATING_BAR, isChecked).apply();
                        applyInsetsPadding();
                        mBottomBar.requestLayout();
                        mBottomBar.invalidate();
                        mockup.invalidate();
                    }
                });

        addIconSwitchRow(glassCard,
                VectorIconView.ICON_LIQUID_DROP,
                "液态玻璃",
                "为悬浮底部栏启用液态玻璃效果",
                uiSp.getBoolean(ThemeEngine.K_LIQUID_GLASS, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_LIQUID_GLASS, isChecked).apply();
                        mBottomBar.invalidate();
                        mockup.invalidate();
                    }
                });

        addIconSwitchRow(glassCard,
                VectorIconView.ICON_BADGE_123,
                "底部栏标记",
                "在底部栏显示运行状态点及已启用策略数量",
                uiSp.getBoolean(ThemeEngine.K_BOTTOM_BADGE, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_BOTTOM_BADGE, isChecked).apply();
                        mBottomBar.invalidate();
                    }
                });

        root.addView(glassCard, createCardMarginParams(14));

        // 5. Card 3: Predictive Back & Navigation Gestures (Image 5 bottom card)
        LinearLayout gestureCard = createRoundedCard();

        addIconSwitchRow(gestureCard,
                VectorIconView.ICON_PREDICTIVE_BACK,
                "预测性返回手势",
                "启用 Android 14 预测性返回手势动画支援",
                uiSp.getBoolean(ThemeEngine.K_PREDICTIVE_BACK, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_PREDICTIVE_BACK, isChecked).apply();
                        updateBackCallbackRegistration();
                    }
                });

        addIconSwitchRow(gestureCard,
                VectorIconView.ICON_EDGE_SWIPE,
                "横移返回手势",
                "从屏幕边缘右滑横移返回上一级菜单",
                uiSp.getBoolean(ThemeEngine.K_EDGE_SWIPE_BACK, true),
                new ModernSwitchView.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                        uiSp.edit().putBoolean(ThemeEngine.K_EDGE_SWIPE_BACK, isChecked).apply();
                    }
                });

        int pgIdx = uiSp.getInt(ThemeEngine.K_PAGE_GESTURE, 0);
        String pgName = (pgIdx >= 0 && pgIdx < ThemeEngine.PAGE_GESTURE_NAMES.length)
                ? ThemeEngine.PAGE_GESTURE_NAMES[pgIdx] : "跨轴拦截";
        addIconSelectorRow(gestureCard,
                VectorIconView.ICON_PAGE_SWITCH,
                "页面切换手势",
                pgName,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSingleChoiceDialog("页面切换手势", ThemeEngine.PAGE_GESTURE_NAMES,
                                uiSp.getInt(ThemeEngine.K_PAGE_GESTURE, 0),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        uiSp.edit().putInt(ThemeEngine.K_PAGE_GESTURE, which).apply();
                                        dialog.dismiss();
                                        rebuildThemeInPlace();
                                    }
                                });
                    }
                });

        root.addView(gestureCard, createCardMarginParams(14));
    }

    // =========================================================================
    // Tab Switching & Dynamic Summary Updates
    // =========================================================================
    private void switchMainTab(int index, boolean animate) {
        if (mCurrentSubPage != SUBPAGE_NONE) {
            closeSubPage(false);
        }
        int target = Math.max(0, Math.min(1, index));
        boolean changed = (mCurrentTab != target);
        mCurrentTab = target;
        mBottomBar.setSelectedTab(target, animate, false);

        if (!animate || !changed) {
            mHomeScroll.setVisibility(target == 0 ? View.VISIBLE : View.GONE);
            mSettingsScroll.setVisibility(target == 1 ? View.VISIBLE : View.GONE);
            mHomeScroll.setTranslationX(0f);
            mSettingsScroll.setTranslationX(0f);
            mHomeScroll.setAlpha(1f);
            mSettingsScroll.setAlpha(1f);
            syncTopBarWithActivePage();
            updateBackCallbackRegistration();
            return;
        }

        final View incoming = (target == 0) ? mHomeScroll : mSettingsScroll;
        final View outgoing = (target == 0) ? mSettingsScroll : mHomeScroll;
        int w = getResources().getDisplayMetrics().widthPixels;
        float dir = (target == 1) ? 1f : -1f;

        incoming.setVisibility(View.VISIBLE);
        incoming.setTranslationX(dir * w * 0.22f);
        incoming.setAlpha(0f);
        incoming.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        outgoing.animate()
                .translationX(-dir * w * 0.22f)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        outgoing.animate().setListener(null);
                        outgoing.setVisibility(View.GONE);
                        outgoing.setTranslationX(0f);
                        outgoing.setAlpha(1f);
                    }
                })
                .start();

        syncTopBarWithActivePage();
        updateBackCallbackRegistration();
    }

    private void syncTopBarWithActivePage() {
        if (mTopBar == null) return;
        if (mCurrentSubPage != SUBPAGE_NONE) {
            int sy = mSubPageScroll != null ? mSubPageScroll.getScrollY() : 0;
            float fade = Math.min(1f, Math.max(0f, sy / (float) dp(44)));
            mTopBar.configure(mTheme, getSubPageTitle(mCurrentSubPage), true, false, fade);
        } else if (mCurrentTab == 0) {
            int sy = mHomeScroll != null ? mHomeScroll.getScrollY() : 0;
            float fade = Math.min(1f, Math.max(0f, sy / (float) dp(44)));
            mTopBar.configure(mTheme, "主页", false, true, fade);
        } else {
            int sy = mSettingsScroll != null ? mSettingsScroll.getScrollY() : 0;
            float fade = Math.min(1f, Math.max(0f, sy / (float) dp(44)));
            mTopBar.configure(mTheme, "设置", false, false, fade);
        }
    }

    private void refreshDynamicSummaries() {
        boolean enabled = mSp.getBoolean(Prefs.K_ENABLED, Prefs.D_ENABLED);
        int mask = mSp.getInt(Prefs.K_MASK, Prefs.D_MASK);
        int bright = mSp.getInt(Prefs.K_BRIGHT, Prefs.D_BRIGHT);
        int scale = mSp.getInt(Prefs.K_SCALE, Prefs.D_SCALE);
        int animIn = mSp.getInt(Prefs.K_IN, Prefs.D_IN);
        int animOut = mSp.getInt(Prefs.K_OUT, Prefs.D_OUT);
        boolean shift = mSp.getBoolean(Prefs.K_SHIFT, Prefs.D_SHIFT);
        boolean night = mSp.getBoolean(Prefs.K_NIGHT, Prefs.D_NIGHT);
        int ns = mSp.getInt(Prefs.K_NS, Prefs.D_NS);
        int ne = mSp.getInt(Prefs.K_NE, Prefs.D_NE);
        int nightMode = mSp.getInt(Prefs.K_NIGHT_MODE, Prefs.D_NIGHT_MODE);
        boolean nightSmart = mSp.getBoolean(Prefs.K_NIGHT_SMART, Prefs.D_NIGHT_SMART);
        boolean timeoutOn = mSp.getBoolean(Prefs.K_TIMEOUT_ON, Prefs.D_TIMEOUT_ON);
        int timeoutMin = mSp.getInt(Prefs.K_TIMEOUT_MIN, Prefs.D_TIMEOUT_MIN);
        int timeoutMode = mSp.getInt(Prefs.K_TIMEOUT_MODE, Prefs.D_TIMEOUT_MODE);
        boolean shade = mSp.getBoolean(Prefs.K_SHADE, Prefs.D_SHADE);
        int shadeMask = mSp.getInt(Prefs.K_SHADE_MASK, Prefs.D_SHADE_MASK);
        boolean flipOff = mSp.getBoolean(Prefs.K_FLIP_OFF, Prefs.D_FLIP_OFF);
        boolean liftWake = mSp.getBoolean(Prefs.K_LIFT_WAKE, Prefs.D_LIFT_WAKE);
        boolean autoBright = mSp.getBoolean(Prefs.K_AUTO_BRIGHT, Prefs.D_AUTO_BRIGHT);
        int autoMin = mSp.getInt(Prefs.K_AUTO_BRIGHT_MIN, Prefs.D_AUTO_BRIGHT_MIN);
        int autoMax = Math.max(autoMin, mSp.getInt(Prefs.K_AUTO_BRIGHT_MAX, Prefs.D_AUTO_BRIGHT_MAX));

        if (mHeroCard != null) {
            mHeroCard.setState(mTheme, enabled);
        }
        if (mHomeMasterSwitch != null && mHomeMasterSwitch.isChecked() != enabled) {
            mHomeMasterSwitch.setChecked(enabled, false, false);
        }
        String brightSummary = autoBright
                ? ("自动亮度 " + autoMin + "%–" + autoMax + "%")
                : ("亮度 " + bright + "% (" + String.format("%.1f×", bright / 100f) + ")");
        if (mHomeProfileSummaryTv != null) {
            String summary = (autoBright ? "基准暗化 " : "暗化 ") + mask + "% · " + brightSummary
                    + (flipOff ? " · 反扣熄屏" : "")
                    + (liftWake ? " · 抬手亮屏" : "");
            mHomeProfileSummaryTv.setText(summary);
        }
        if (mSubDisplaySummaryTv != null) {
            mSubDisplaySummaryTv.setText("当前："
                    + (autoBright ? ("自动亮度 " + autoMin + "%–" + autoMax + "% · 基准暗化 ")
                    : ("亮度 " + bright + "% · 暗化 "))
                    + mask + "% · 放大 " + scale + "%（点击调节与预览）");
        }
        if (mSubAnimSummaryTv != null) {
            mSubAnimSummaryTv.setText("当前：熄屏淡入 " + animIn + " ms · 亮屏淡出 " + animOut + " ms");
        }
        if (mSubNightSummaryTv != null) {
            if (!night) {
                mSubNightSummaryTv.setText("已关闭 · 夜间保持显示全屏 AOD 壁纸");
            } else {
                String modeStr = (nightMode == 1) ? "完全关闭 AOD" : "仅保留时钟";
                String smartStr = nightSmart ? " · 5:00 后首次解锁唤醒" : "";
                mSubNightSummaryTv.setText("已开启 · " + formatHour(ns) + " 至 " + formatHour(ne) + " · " + modeStr + smartStr);
            }
        }
        if (mSubTimeoutSummaryTv != null) {
            if (!timeoutOn) {
                mSubTimeoutSummaryTv.setText("已关闭 · 长时间熄屏不自动降级");
            } else {
                String modeStr = (timeoutMode == 1) ? "完全关闭 AOD" : "仅保留时钟";
                mSubTimeoutSummaryTv.setText("已开启 · 持续 " + timeoutMin + " 分钟后" + modeStr);
            }
        }
        if (mSubShadeSummaryTv != null) {
            if (!shade) {
                mSubShadeSummaryTv.setText("已关闭（推荐） · 通知栏使用系统默认背景");
            } else {
                mSubShadeSummaryTv.setText("已开启 · 通知栏遮罩浓度 " + shadeMask + "%");
            }
        }
        if (mBottomBar != null) {
            int activeFeatures = (enabled ? 1 : 0) + (autoBright ? 1 : 0) + (shift ? 1 : 0) + (night ? 1 : 0)
                    + (timeoutOn ? 1 : 0) + (shade ? 1 : 0)
                    + (flipOff ? 1 : 0) + (liftWake ? 1 : 0);
            mBottomBar.setBadgeInfo(enabled, activeFeatures);
        }
    }

    private static String formatHour(int h) {
        return (h < 10 ? "0" : "") + h + ":00";
    }

    private void setDependentViewsEnabled(List<View> views, boolean enabled) {
        for (View v : views) {
            setViewTreeEnabled(v, enabled);
            v.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private void setViewTreeEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setViewTreeEnabled(vg.getChildAt(i), enabled);
            }
        }
    }

    // =========================================================================
    // Predictive Back Gesture (Android 14 API 34) + Edge Swipe & Tab Swipe
    // =========================================================================
    private void updateBackCallbackRegistration() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            OnBackInvokedDispatcher disp = getOnBackInvokedDispatcher();
            if (disp == null) return;

            boolean shouldIntercept = ThemeEngine.isPredictiveBack(this)
                    && (mCurrentSubPage != SUBPAGE_NONE || mCurrentTab != 0);

            if (shouldIntercept && !mBackCallbackRegistered) {
                if (mBackCallback == null) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        mBackCallback = new OnBackAnimationCallback() {
                            @Override
                            public void onBackStarted(BackEvent backEvent) {
                                if (mCurrentSubPage != SUBPAGE_NONE && mSubPageOverlay != null) {
                                    GradientDrawable gd = new GradientDrawable();
                                    gd.setColor(mTheme.bgColor);
                                    gd.setCornerRadius(dp(28));
                                    mSubPageOverlay.setBackground(gd);
                                    mMainPagesHost.setScaleX(0.94f);
                                    mMainPagesHost.setScaleY(0.94f);
                                    mMainPagesHost.setAlpha(0.7f);
                                } else if (mCurrentTab == 1 && mSettingsScroll != null && mHomeScroll != null) {
                                    mHomeScroll.setVisibility(View.VISIBLE);
                                    mHomeScroll.setScaleX(0.94f);
                                    mHomeScroll.setScaleY(0.94f);
                                    mHomeScroll.setAlpha(0.6f);
                                }
                            }

                            @Override
                            public void onBackProgressed(BackEvent backEvent) {
                                float p = backEvent.getProgress();
                                float scale = 1.0f - 0.10f * p;
                                float dir = (backEvent.getSwipeEdge() == BackEvent.EDGE_LEFT) ? 1f : -0.4f;
                                if (mCurrentSubPage != SUBPAGE_NONE && mSubPageOverlay != null) {
                                    mSubPageOverlay.setScaleX(scale);
                                    mSubPageOverlay.setScaleY(scale);
                                    mSubPageOverlay.setTranslationX(dir * dp(54) * p);
                                    mMainPagesHost.setScaleX(0.94f + 0.06f * p);
                                    mMainPagesHost.setScaleY(0.94f + 0.06f * p);
                                    mMainPagesHost.setAlpha(0.7f + 0.3f * p);
                                } else if (mCurrentTab == 1 && mSettingsScroll != null && mHomeScroll != null) {
                                    mSettingsScroll.setScaleX(scale);
                                    mSettingsScroll.setScaleY(scale);
                                    mSettingsScroll.setTranslationX(dir * dp(48) * p);
                                    mSettingsScroll.setAlpha(1.0f - 0.25f * p);
                                    mHomeScroll.setScaleX(0.94f + 0.06f * p);
                                    mHomeScroll.setScaleY(0.94f + 0.06f * p);
                                    mHomeScroll.setAlpha(0.6f + 0.4f * p);
                                }
                            }

                            @Override
                            public void onBackCancelled() {
                                if (mCurrentSubPage != SUBPAGE_NONE && mSubPageOverlay != null) {
                                    mSubPageOverlay.animate()
                                            .scaleX(1f).scaleY(1f).translationX(0f)
                                            .setDuration(180)
                                            .withEndAction(new Runnable() {
                                                @Override
                                                public void run() {
                                                    if (mSubPageOverlay != null) {
                                                        mSubPageOverlay.setBackgroundColor(mTheme.bgColor);
                                                    }
                                                }
                                            })
                                            .start();
                                    mMainPagesHost.animate()
                                            .scaleX(1f).scaleY(1f).alpha(1f)
                                            .setDuration(180).start();
                                } else if (mCurrentTab == 1 && mSettingsScroll != null && mHomeScroll != null) {
                                    mSettingsScroll.animate()
                                            .scaleX(1f).scaleY(1f).translationX(0f).alpha(1f)
                                            .setDuration(180).start();
                                    mHomeScroll.animate()
                                            .scaleX(1f).scaleY(1f).alpha(0f)
                                            .setDuration(180)
                                            .withEndAction(new Runnable() {
                                                @Override
                                                public void run() {
                                                    if (mCurrentTab == 1 && mHomeScroll != null) {
                                                        mHomeScroll.setVisibility(View.GONE);
                                                        mHomeScroll.setAlpha(1f);
                                                    }
                                                }
                                            })
                                            .start();
                                }
                            }

                            @Override
                            public void onBackInvoked() {
                                if (mSettingsScroll != null) {
                                    mSettingsScroll.setScaleX(1f);
                                    mSettingsScroll.setScaleY(1f);
                                }
                                if (mHomeScroll != null) {
                                    mHomeScroll.setScaleX(1f);
                                    mHomeScroll.setScaleY(1f);
                                }
                                handleInternalBack();
                            }
                        };
                    } else {
                        mBackCallback = new OnBackInvokedCallback() {
                            @Override
                            public void onBackInvoked() {
                                handleInternalBack();
                            }
                        };
                    }
                }
                disp.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        (OnBackInvokedCallback) mBackCallback);
                mBackCallbackRegistered = true;
            } else if (!shouldIntercept && mBackCallbackRegistered && mBackCallback != null) {
                disp.unregisterOnBackInvokedCallback((OnBackInvokedCallback) mBackCallback);
                mBackCallbackRegistered = false;
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleInternalBack() {
        if (mCurrentSubPage != SUBPAGE_NONE) {
            closeSubPage(true);
        } else if (mCurrentTab != 0) {
            switchMainTab(0, true);
        } else {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mCurrentSubPage != SUBPAGE_NONE || mCurrentTab != 0) {
            handleInternalBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getRawX();
        float y = ev.getRawY();
        int screenW = getResources().getDisplayMetrics().widthPixels;

        if (action == MotionEvent.ACTION_DOWN) {
            mTouchDownX = x;
            mTouchDownY = y;
            mIsEdgeSwipingSubPage = false;
            mIsSwipingMainTabs = false;
            mGestureLockedVertical = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - mTouchDownX;
            float dy = y - mTouchDownY;

            if (!mIsEdgeSwipingSubPage && !mIsSwipingMainTabs && !mGestureLockedVertical) {
                if (Math.abs(dy) > mTouchSlop * 1.15f && Math.abs(dy) > Math.abs(dx)) {
                    mGestureLockedVertical = true;
                } else if (mCurrentSubPage != SUBPAGE_NONE
                        && ThemeEngine.isEdgeSwipeBack(this)
                        && mTouchDownX <= dp(36)
                        && dx > mTouchSlop * 1.2f
                        && dx > Math.abs(dy) * 1.3f) {
                    mIsEdgeSwipingSubPage = true;
                    cancelChildTouches(ev);
                } else if (mCurrentSubPage == SUBPAGE_NONE) {
                    int pgMode = ThemeEngine.getPageGestureMode(this);
                    if (pgMode != 2 && mTouchDownY < (getResources().getDisplayMetrics().heightPixels - dp(96))) {
                        float ratioReq = (pgMode == 0) ? 1.55f : 1.15f; // 0 = 跨轴拦截, 1 = 自由滑动
                        if (Math.abs(dx) > mTouchSlop * 1.8f && Math.abs(dx) > Math.abs(dy) * ratioReq) {
                            if ((mCurrentTab == 0 && dx < 0) || (mCurrentTab == 1 && dx > 0)) {
                                mIsSwipingMainTabs = true;
                                cancelChildTouches(ev);
                            }
                        }
                    }
                }
            }

            if (mIsEdgeSwipingSubPage && mSubPageOverlay != null) {
                float dragX = Math.max(0f, dx);
                float p = Math.min(1f, dragX / Math.max(1f, screenW));
                mSubPageOverlay.setTranslationX(dragX);
                mSubPageOverlay.setAlpha(1f - 0.25f * p);
                mMainPagesHost.setScaleX(0.95f + 0.05f * p);
                mMainPagesHost.setScaleY(0.95f + 0.05f * p);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mIsEdgeSwipingSubPage) {
                float dx = x - mTouchDownX;
                mIsEdgeSwipingSubPage = false;
                if (dx > screenW * 0.26f) {
                    closeSubPage(true);
                } else if (mSubPageOverlay != null) {
                    mSubPageOverlay.animate().translationX(0f).alpha(1f).setDuration(180).start();
                    mMainPagesHost.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                }
                return true;
            }
            if (mIsSwipingMainTabs) {
                float dx = x - mTouchDownX;
                mIsSwipingMainTabs = false;
                if (mCurrentTab == 0 && dx < -dp(56)) {
                    switchMainTab(1, true);
                } else if (mCurrentTab == 1 && dx > dp(56)) {
                    switchMainTab(0, true);
                }
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void cancelChildTouches(MotionEvent ev) {
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    // =========================================================================
    // UI Component Builders
    // =========================================================================
    private TextView buildLargePageTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(mTheme.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), dp(8), dp(4), dp(18));
        return tv;
    }

    private TextView buildSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(mTheme.sectionTitleColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(10), dp(14), dp(10), dp(8));
        return tv;
    }

    private LinearLayout createRoundedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(mTheme.cardColor);
        bg.setStroke(1, mTheme.cardStrokeColor);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams createCardMarginParams(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private TextView addNavRow(LinearLayout card, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(18), dp(16));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(12);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tTitle);

        TextView tSub = new TextView(this);
        if (subtitle != null && !subtitle.isEmpty()) {
            tSub.setText(subtitle);
            tSub.setTextColor(mTheme.textSecondary);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tSub.setPadding(0, dp(3), 0, 0);
            textCol.addView(tSub);
        }

        row.addView(textCol, colLp);
        row.addView(new VectorIconView(this, VectorIconView.ICON_CHEVRON_RIGHT, mTheme.textSecondary));
        row.setOnClickListener(listener);
        card.addView(row);
        return tSub;
    }

    private void addPrefSwitchRow(LinearLayout card, String title, String subtitle,
                                  final String key, boolean def) {
        boolean cur = mSp.getBoolean(key, def);
        addCustomSwitchRow(card, title, subtitle, cur, new ModernSwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                mSp.edit().putBoolean(key, isChecked).apply();
                pushToSettings();
                refreshDynamicSummaries();
            }
        });
    }

    private void addPrefSwitchIntRow(LinearLayout card, String title, String subtitle,
                                     final String key, int def, final int falseVal, final int trueVal) {
        boolean cur = (mSp.getInt(key, def) == trueVal);
        addCustomSwitchRow(card, title, subtitle, cur, new ModernSwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(ModernSwitchView view, boolean isChecked) {
                mSp.edit().putInt(key, isChecked ? trueVal : falseVal).apply();
                pushToSettings();
                refreshDynamicSummaries();
            }
        });
    }

    private ModernSwitchView addCustomSwitchRow(LinearLayout card, String title, String subtitle,
                                                boolean initialChecked,
                                                ModernSwitchView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(15), dp(18), dp(15));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(14);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tSub = new TextView(this);
            tSub.setText(subtitle);
            tSub.setTextColor(mTheme.textSecondary);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tSub.setPadding(0, dp(3), 0, 0);
            textCol.addView(tSub);
        }

        row.addView(textCol, colLp);

        final ModernSwitchView sw = new ModernSwitchView(this, mTheme, initialChecked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) {
                    sw.performClick();
                }
            }
        });
        card.addView(row);
        return sw;
    }

    private void addIconSwitchRow(LinearLayout card, int iconType, String title, String subtitle,
                                  boolean initialChecked, ModernSwitchView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(15), dp(18), dp(15));

        VectorIconView icon = new VectorIconView(this, iconType, mTheme.textPrimary);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.rightMargin = dp(16);
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(12);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tSub = new TextView(this);
            tSub.setText(subtitle);
            tSub.setTextColor(mTheme.textSecondary);
            tSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tSub.setPadding(0, dp(2), 0, 0);
            textCol.addView(tSub);
        }

        row.addView(textCol, colLp);

        final ModernSwitchView sw = new ModernSwitchView(this, mTheme, initialChecked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.performClick();
            }
        });
        card.addView(row);
    }

    private void addIconSelectorRow(LinearLayout card, int iconType, String title, String currentVal,
                                    View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));

        VectorIconView icon = new VectorIconView(this, iconType, mTheme.textPrimary);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.rightMargin = dp(16);
        row.addView(icon, iconLp);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tTitle, titleLp);

        TextView tVal = new TextView(this);
        tVal.setText(currentVal);
        tVal.setTextColor(mTheme.textSecondary);
        tVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tVal.setPadding(0, 0, dp(4), 0);
        row.addView(tVal);

        row.addView(new VectorIconView(this, VectorIconView.ICON_UP_DOWN_ARROWS, mTheme.textSecondary));
        row.setOnClickListener(listener);
        card.addView(row);
    }

    private View addSliderBlock(LinearLayout card, final String title, String description,
                                final int min, final int max, final String key, int def,
                                final String unit,
                                final ModernSliderView.OnValueChangeListener extraListener) {
        int cur = mSp.getInt(key, def);
        if (cur < min) cur = min;
        if (cur > max) cur = max;

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(tTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tBadge = new TextView(this);
        tBadge.setText(formatSliderValue(key, cur, unit));
        tBadge.setTextColor(mTheme.onPrimaryContainer);
        tBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor(mTheme.primaryContainer);
        tBadge.setBackground(badgeBg);
        headerRow.addView(tBadge);

        block.addView(headerRow);

        if (description != null && !description.isEmpty()) {
            TextView tDesc = new TextView(this);
            tDesc.setText(description);
            tDesc.setTextColor(mTheme.textSecondary);
            tDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tDesc.setPadding(0, dp(4), 0, dp(6));
            block.addView(tDesc);
        }

        ModernSliderView slider = new ModernSliderView(this, mTheme, min, max, cur);
        slider.setOnValueChangeListener(new ModernSliderView.OnValueChangeListener() {
            @Override
            public void onValueChanged(int value, boolean fromUser) {
                tBadge.setText(formatSliderValue(key, value, unit));
                if (fromUser) {
                    mSp.edit().putInt(key, value).apply();
                    pushToSettings();
                    refreshDynamicSummaries();
                }
                if (extraListener != null) {
                    extraListener.onValueChanged(value, fromUser);
                }
            }
        });
        block.addView(slider);
        block.setTag(slider);

        card.addView(block);
        return block;
    }

    private void addHourSliderAndPickerBlock(LinearLayout card, final String title, String description,
                                             final String key, int def, final Runnable onUpdated) {
        int cur = mSp.getInt(key, def);
        if (cur < 0) cur = 0;
        if (cur > 23) cur = 23;

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tTitle = new TextView(this);
        tTitle.setText(title);
        tTitle.setTextColor(mTheme.textPrimary);
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(tTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tBadge = new TextView(this);
        tBadge.setText(formatHour(cur) + " ✎");
        tBadge.setTextColor(mTheme.onPrimaryContainer);
        tBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tBadge.setPadding(dp(11), dp(4), dp(11), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor(mTheme.primaryContainer);
        tBadge.setBackground(badgeBg);
        headerRow.addView(tBadge);

        block.addView(headerRow);

        TextView tDesc = new TextView(this);
        tDesc.setText(description);
        tDesc.setTextColor(mTheme.textSecondary);
        tDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tDesc.setPadding(0, dp(3), 0, dp(4));
        block.addView(tDesc);

        final ModernSliderView slider = new ModernSliderView(this, mTheme, 0, 23, cur);
        slider.setOnValueChangeListener(new ModernSliderView.OnValueChangeListener() {
            @Override
            public void onValueChanged(int value, boolean fromUser) {
                tBadge.setText(formatHour(value) + " ✎");
                if (fromUser) {
                    mSp.edit().putInt(key, value).apply();
                    pushToSettings();
                    refreshDynamicSummaries();
                    if (onUpdated != null) onUpdated.run();
                }
            }
        });
        block.addView(slider);

        tBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentHour = mSp.getInt(key, 0);
                TimePickerDialog dlg = new TimePickerDialog(MainActivity.this,
                        new TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                                mSp.edit().putInt(key, hourOfDay).apply();
                                pushToSettings();
                                slider.setValue(hourOfDay, false);
                                tBadge.setText(formatHour(hourOfDay) + " ✎");
                                refreshDynamicSummaries();
                                if (onUpdated != null) onUpdated.run();
                            }
                        }, currentHour, 0, true);
                dlg.setTitle(title);
                dlg.show();
            }
        });

        card.addView(block);
    }

    private String formatSliderValue(String key, int val, String unit) {
        if (Prefs.K_BRIGHT.equals(key)) {
            return val + "% (" + String.format("%.1f×", val / 100f) + ")";
        }
        if (Prefs.K_SCALE.equals(key)) {
            return val + "% (" + String.format("%.2f×", val / 100f) + ")";
        }
        if (Prefs.K_AUTO_BRIGHT_MIN.equals(key) || Prefs.K_AUTO_BRIGHT_MAX.equals(key)) {
            return val + "% (" + AmbientLightCurve.pctToDozeInt(val) + "/255)";
        }
        return val + unit;
    }

    // =========================================================================
    // Dialogs & Desktop Shortcut / Wallpaper Preview Helpers
    // =========================================================================
    private Bitmap loadPreviewWallpaperBitmap() {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            if (wm != null) {
                Drawable d = wm.getDrawable();
                if (d instanceof BitmapDrawable) {
                    Bitmap b = ((BitmapDrawable) d).getBitmap();
                    if (b != null && !b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0) {
                        // Center-crop to ~16:9 aspect ratio so the 170dp preview card never squishes tall portrait wallpapers
                        int bw = b.getWidth();
                        int bh = b.getHeight();
                        int targetH = Math.min(bh, Math.max(1, (int) (bw * 0.54f)));
                        int startY = (bh - targetH) / 2;
                        return Bitmap.createBitmap(b, 0, startY, bw, targetH);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Fallback procedural lockscreen artwork if Android 14 restricts READ_EXTERNAL_STORAGE for WallpaperManager
        Bitmap bmp = Bitmap.createBitmap(400, 216, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 400, 216,
                new int[]{0xFF2B1B4D, 0xFF45276B, 0xFF1E3C58, 0xFF121324},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 400, 216, p);
        p.setShader(null);
        p.setColor(ThemeEngine.withAlpha(mTheme.primary, 95));
        c.drawCircle(110, 75, 68, p);
        p.setColor(0x557DDBFF);
        c.drawCircle(310, 155, 85, p);
        return bmp;
    }

    private void requestPinDesktopShortcut() {
        ThemeEngine.setLauncherIconHidden(this, false);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ShortcutManager sm = getSystemService(ShortcutManager.class);
                if (sm != null && sm.isRequestPinShortcutSupported()) {
                    Intent launchIntent = new Intent(this, MainActivity.class);
                    launchIntent.setAction(Intent.ACTION_MAIN);
                    ShortcutInfo info = new ShortcutInfo.Builder(this, "fullaod_desktop_shortcut")
                            .setShortLabel("FullAOD")
                            .setLongLabel("FullAOD 全屏熄屏设置")
                            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                            .setIntent(launchIntent)
                            .build();
                    sm.requestPinShortcut(info, null);
                    Toast.makeText(this, "已启用桌面图标并发送桌面快捷方式请求", Toast.LENGTH_SHORT).show();
                    rebuildThemeInPlace();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, "已在系统桌面启用 FullAOD 应用图标", Toast.LENGTH_SHORT).show();
        rebuildThemeInPlace();
    }

    private void showSingleChoiceDialog(String title, String[] items, int checkedItem,
                                        DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, checkedItem, listener)
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCurrentEffectiveConfigDialog() {
        String raw = "";
        try {
            raw = Settings.Secure.getString(getContentResolver(), "fullaod_cfg");
        } catch (Throwable ignored) {
        }
        String formatted = raw;
        if (raw != null && !raw.isEmpty()) {
            try {
                formatted = new JSONObject(raw).toString(2);
            } catch (Throwable ignored) {
            }
        } else {
            formatted = "（Settings.Secure 中暂无 fullaod_cfg 记录，拖动任意控件即可写入）";
        }

        final String copyText = (raw != null && !raw.isEmpty()) ? raw : buildCurrentConfigJson().toString();
        String msg = "【Settings.Secure 实时生效 JSON】\n" + formatted
                + "\n\n【生效机制】\nSystemUI 内的 AodController 每 3 秒读取一次，改动在下次熄屏时生效，无需重启 SystemUI。"
                + "\n\n【救援与日志】\n• 开机动画下三击音量− 可禁用 Xposed 模块\n• 日志路径：/data/user/0/com.android.systemui/files/fullaod.log";

        new AlertDialog.Builder(this)
                .setTitle("当前生效配置与救援")
                .setMessage(msg)
                .setPositiveButton("复制 JSON", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(copyText);
                        Toast.makeText(MainActivity.this, "已复制 JSON 到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("立即重新推送", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pushToSettings();
                        Toast.makeText(MainActivity.this, "已重新推送 22 项配置到 Settings.Secure", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showExportConfigDialog() {
        final String json = buildCurrentConfigJson().toString();
        copyToClipboard(json);
        new AlertDialog.Builder(this)
                .setTitle("导出设置（已复制到剪贴板）")
                .setMessage(json)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showImportConfigDialog() {
        final EditText input = new EditText(this);
        input.setHint("在此粘贴导出的 JSON 配置...");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int p = dp(16);
        input.setPadding(p, p, p, p);

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && clip.toString().trim().startsWith("{")) {
                    input.setText(clip.toString().trim());
                }
            }
        } catch (Throwable ignored) {
        }

        new AlertDialog.Builder(this)
                .setTitle("导入 JSON 设置")
                .setView(input)
                .setPositiveButton("导入并生效", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            String txt = input.getText().toString().trim();
                            JSONObject o = new JSONObject(txt);
                            applyJsonToPrefs(o);
                            pushToSettings();
                            rebuildThemeInPlace();
                            Toast.makeText(MainActivity.this, "导入成功，下次熄屏生效", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "JSON 格式无效：" + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showResetConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("确定要将所有全屏 AOD 参数恢复为出厂默认值吗？\n\n此操作会立即覆盖当前线上配置，下次熄屏时生效。")
                .setPositiveButton("确认重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.reset(MainActivity.this);
                        pushToSettings();
                        rebuildThemeInPlace();
                        Toast.makeText(MainActivity.this, "已恢复默认值并同步至系统", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("fullaod_cfg", text));
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Core Backend Contract (22-FIELD JSON — KEEP IN SYNC WITH ConfigProvider)
    // =========================================================================
    private JSONObject buildCurrentConfigJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", mSp.getBoolean(Prefs.K_ENABLED, Prefs.D_ENABLED));
            o.put("mask", mSp.getInt(Prefs.K_MASK, Prefs.D_MASK) / 100.0);
            o.put("bright", mSp.getInt(Prefs.K_BRIGHT, Prefs.D_BRIGHT) / 100.0);
            o.put("shift", mSp.getBoolean(Prefs.K_SHIFT, Prefs.D_SHIFT));
            o.put("night", mSp.getBoolean(Prefs.K_NIGHT, Prefs.D_NIGHT));
            o.put("nightStart", mSp.getInt(Prefs.K_NS, Prefs.D_NS));
            o.put("nightEnd", mSp.getInt(Prefs.K_NE, Prefs.D_NE));
            o.put("animIn", mSp.getInt(Prefs.K_IN, Prefs.D_IN));
            o.put("animOut", mSp.getInt(Prefs.K_OUT, Prefs.D_OUT));
            o.put("scale", mSp.getInt(Prefs.K_SCALE, Prefs.D_SCALE) / 100.0);
            o.put("shade", mSp.getBoolean(Prefs.K_SHADE, Prefs.D_SHADE));
            o.put("shadeMask", mSp.getInt(Prefs.K_SHADE_MASK, Prefs.D_SHADE_MASK) / 100.0);
            o.put("aodTimeoutOn", mSp.getBoolean(Prefs.K_TIMEOUT_ON, Prefs.D_TIMEOUT_ON));
            o.put("aodTimeoutMin", mSp.getInt(Prefs.K_TIMEOUT_MIN, Prefs.D_TIMEOUT_MIN));
            o.put("aodTimeoutMode", mSp.getInt(Prefs.K_TIMEOUT_MODE, Prefs.D_TIMEOUT_MODE));
            o.put("nightMode", mSp.getInt(Prefs.K_NIGHT_MODE, Prefs.D_NIGHT_MODE));
            o.put("nightSmart", mSp.getBoolean(Prefs.K_NIGHT_SMART, Prefs.D_NIGHT_SMART));
            o.put("flipOff", mSp.getBoolean(Prefs.K_FLIP_OFF, Prefs.D_FLIP_OFF));
            o.put("liftWake", mSp.getBoolean(Prefs.K_LIFT_WAKE, Prefs.D_LIFT_WAKE));
            o.put("autoBright", mSp.getBoolean(Prefs.K_AUTO_BRIGHT, Prefs.D_AUTO_BRIGHT));
            int autoMin = clampRange(mSp.getInt(Prefs.K_AUTO_BRIGHT_MIN, Prefs.D_AUTO_BRIGHT_MIN), 1, 50);
            int autoMax = Math.max(autoMin, clampRange(mSp.getInt(Prefs.K_AUTO_BRIGHT_MAX, Prefs.D_AUTO_BRIGHT_MAX), 20, 100));
            o.put("autoBrightMin", autoMin);
            o.put("autoBrightMax", autoMax);
        } catch (Throwable t) {
            Log.w("FullAOD", "buildCurrentConfigJson fail", t);
        }
        return o;
    }

    private void pushToSettings() {
        final String jsonStr = buildCurrentConfigJson().toString();
        try {
            Settings.Secure.putString(getContentResolver(), "fullaod_cfg", jsonStr);
        } catch (SecurityException se) {
            Log.w("FullAOD", "WRITE_SECURE_SETTINGS missing, attempting root self-grant", se);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{
                                "su", "-c",
                                "pm grant com.operit.fullaod android.permission.WRITE_SECURE_SETTINGS"
                        });
                        p.waitFor();
                        Settings.Secure.putString(getContentResolver(), "fullaod_cfg", jsonStr);
                    } catch (Throwable t2) {
                        Log.w("FullAOD", "root self-grant fail", t2);
                    }
                }
            }).start();
        } catch (Throwable t) {
            Log.w("FullAOD", "pushToSettings fail", t);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
