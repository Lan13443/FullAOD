package com.operit.fullaod.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

public final class UiWidgets {

    private UiWidgets() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpF(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int lerpColor(int c1, int c2, float t) {
        if (t <= 0f) return c1;
        if (t >= 1f) return c2;
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        return ((int) (a1 + (a2 - a1) * t) << 24)
                | ((int) (r1 + (r2 - r1) * t) << 16)
                | ((int) (g1 + (g2 - g1) * t) << 8)
                | (int) (b1 + (b2 - b1) * t);
    }

    // =========================================================================
    // 0. GPU Display-List Backdrop Stage (Kyant0 LayerBackdrop architecture)
    // =========================================================================
    public static class BackdropStageLayout extends FrameLayout {
        private RenderNode mStageNode;
        private int mBgColor;
        private View mTopOverlay;
        private View mBottomOverlay;

        public BackdropStageLayout(Context ctx, int bgColor) {
            super(ctx);
            mBgColor = bgColor;
            setBackgroundColor(bgColor);
            if (Build.VERSION.SDK_INT >= 29) {
                mStageNode = new RenderNode("BackdropStage");
            }
        }

        public void setOverlays(View top, View bottom) {
            mTopOverlay = top;
            mBottomOverlay = bottom;
        }

        public RenderNode getStageNode() {
            if (Build.VERSION.SDK_INT >= 29 && mStageNode != null && mStageNode.hasDisplayList()) {
                return mStageNode;
            }
            return null;
        }

        public void notifyOverlaysBackdropChanged() {
            if (mTopOverlay != null) {
                mTopOverlay.invalidate();
            }
            if (mBottomOverlay != null && mBottomOverlay.getVisibility() == View.VISIBLE) {
                mBottomOverlay.invalidate();
            }
        }

        @Override
        public void onDescendantInvalidated(View child, View target) {
            super.onDescendantInvalidated(child, target);
            invalidate();
            notifyOverlaysBackdropChanged();
        }

        @Override
        @SuppressWarnings("deprecation")
        public android.view.ViewParent invalidateChildInParent(int[] location, Rect dirty) {
            android.view.ViewParent vp = super.invalidateChildInParent(location, dirty);
            invalidate();
            notifyOverlaysBackdropChanged();
            return vp;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                    && mStageNode != null && w > 0 && h > 0) {
                try {
                    mStageNode.setPosition(0, 0, w, h);
                    RecordingCanvas rc = mStageNode.beginRecording(w, h);
                    rc.drawColor(mBgColor);
                    super.dispatchDraw(rc);
                    mStageNode.endRecording();
                    canvas.drawRenderNode(mStageNode);
                    return;
                } catch (Throwable ignored) {
                }
            }
            super.dispatchDraw(canvas);
        }
    }

    // =========================================================================
    // 1. Modern Material 3 / Miuix Pill Switch (matches Images 3, 4, 5)
    // =========================================================================
    public static class ModernSwitchView extends View {
        public interface OnCheckedChangeListener {
            void onCheckedChanged(ModernSwitchView view, boolean isChecked);
        }

        private ThemeEngine mTheme;
        private boolean mChecked;
        private float mProgress;
        private ValueAnimator mAnim;
        private OnCheckedChangeListener mListener;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        public ModernSwitchView(Context ctx, ThemeEngine theme, boolean initialChecked) {
            super(ctx);
            mTheme = theme;
            mChecked = initialChecked;
            mProgress = initialChecked ? 1f : 0f;
            setClickable(true);
            setFocusable(true);
        }

        public void setTheme(ThemeEngine theme) {
            mTheme = theme;
            invalidate();
        }

        public boolean isChecked() {
            return mChecked;
        }

        public void setChecked(boolean checked, boolean animate, boolean notify) {
            if (mChecked == checked && animate) return;
            mChecked = checked;
            float target = checked ? 1f : 0f;
            if (mAnim != null) mAnim.cancel();
            if (animate) {
                mAnim = ValueAnimator.ofFloat(mProgress, target);
                mAnim.setDuration(220);
                mAnim.setInterpolator(new OvershootInterpolator(1.1f));
                mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mProgress = (Float) animation.getAnimatedValue();
                        invalidate();
                    }
                });
                mAnim.start();
            } else {
                mProgress = target;
                invalidate();
            }
            if (notify && mListener != null) {
                mListener.onCheckedChanged(this, mChecked);
            }
        }

        public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            mListener = l;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (isEnabled()) {
                setChecked(!mChecked, true, true);
            }
            return true;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 30));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float p = Math.max(0f, Math.min(1f, mProgress));
            int trackColor = lerpColor(mTheme.switchTrackOff, mTheme.switchTrackOn, p);
            int thumbColor = lerpColor(mTheme.switchThumbOff, mTheme.switchThumbOn, p);

            mRect.set(0, 0, w, h);
            float r = h * 0.5f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(trackColor);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            if (p < 0.95f) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 1.2f));
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.switchStrokeOff, (int) ((1f - p) * 65)));
                float inset = dpF(getContext(), 0.7f);
                mRect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(mRect, r, r, mPaint);
            }

            float thumbRadiusOff = dpF(getContext(), 9.0f);
            float thumbRadiusOn = dpF(getContext(), 11.0f);
            float thumbR = thumbRadiusOff + (thumbRadiusOn - thumbRadiusOff) * p;

            float startX = h * 0.5f;
            float endX = w - h * 0.5f;
            float cx = startX + (endX - startX) * mProgress;
            float cy = h * 0.5f;

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(thumbColor);
            canvas.drawCircle(cx, cy, thumbR, mPaint);
        }
    }

    // =========================================================================
    // 2. Modern Thick Capsule Slider
    // =========================================================================
    public static class ModernSliderView extends View {
        public interface OnValueChangeListener {
            void onValueChanged(int value, boolean fromUser);
        }

        private ThemeEngine mTheme;
        private final int mMin;
        private final int mMax;
        private int mValue;
        private OnValueChangeListener mListener;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private boolean mDragging = false;

        public ModernSliderView(Context ctx, ThemeEngine theme, int min, int max, int current) {
            super(ctx);
            mTheme = theme;
            mMin = min;
            mMax = Math.max(min + 1, max);
            mValue = Math.max(min, Math.min(max, current));
        }

        public void setTheme(ThemeEngine theme) {
            mTheme = theme;
            invalidate();
        }

        public int getValue() {
            return mValue;
        }

        public void setValue(int val, boolean notify) {
            int clamped = Math.max(mMin, Math.min(mMax, val));
            if (mValue != clamped) {
                mValue = clamped;
                invalidate();
                if (notify && mListener != null) {
                    mListener.onValueChanged(mValue, false);
                }
            }
        }

        public void setOnValueChangeListener(OnValueChangeListener l) {
            mListener = l;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, dp(getContext(), 36));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (action == MotionEvent.ACTION_DOWN && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                mDragging = true;
                updateFromTouch(event.getX());
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mDragging = false;
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateFromTouch(float x) {
            float pad = dpF(getContext(), 12);
            float trackW = Math.max(1f, getWidth() - pad * 2f);
            float ratio = Math.max(0f, Math.min(1f, (x - pad) / trackW));
            int newVal = mMin + Math.round(ratio * (mMax - mMin));
            if (newVal != mValue) {
                mValue = newVal;
                invalidate();
                if (mListener != null) {
                    mListener.onValueChanged(mValue, true);
                }
            } else {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float pad = dpF(getContext(), 12);
            float trackH = dpF(getContext(), 12);
            float top = (h - trackH) * 0.5f;
            float bottom = top + trackH;
            float r = trackH * 0.5f;

            // Inactive background track
            mRect.set(pad, top, w - pad, bottom);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTheme.switchTrackOff);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            // Active filled track
            float ratio = (float) (mValue - mMin) / (float) (mMax - mMin);
            float activeRight = pad + Math.max(trackH, (w - pad * 2f) * ratio);
            mRect.set(pad, top, activeRight, bottom);
            mPaint.setColor(mTheme.primary);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            // Thumb
            float thumbX = pad + (w - pad * 2f) * ratio;
            float thumbY = h * 0.5f;
            float thumbR = mDragging ? dpF(getContext(), 11.5f) : dpF(getContext(), 10f);

            if (mDragging) {
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.primary, 55));
                canvas.drawCircle(thumbX, thumbY, dpF(getContext(), 17f), mPaint);
            }

            mPaint.setColor(mTheme.primary);
            canvas.drawCircle(thumbX, thumbY, thumbR, mPaint);
            mPaint.setColor(mTheme.onPrimary);
            canvas.drawCircle(thumbX, thumbY, thumbR * 0.44f, mPaint);
        }
    }

    // =========================================================================
    // 3. Home Hero Status Card (Image 2 Style with Large Checkmark Watermark)
    // =========================================================================
    public static class HeroStatusCardView extends View {
        private ThemeEngine mTheme;
        private boolean mActive;
        private final String mVersionText = "v1.0-patch31";
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mClipPath = new Path();
        private final Path mCheckPath = new Path();

        public HeroStatusCardView(Context ctx, ThemeEngine theme, boolean active) {
            super(ctx);
            mTheme = theme;
            mActive = active;
            setClickable(true);
        }

        public void setState(ThemeEngine theme, boolean active) {
            mTheme = theme;
            mActive = active;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, dp(getContext(), 134));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float radius = dpF(getContext(), 24);
            mRect.set(0, 0, w, h);

            mClipPath.reset();
            mClipPath.addRoundRect(mRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClipPath);

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mActive ? mTheme.heroCardBg : mTheme.cardElevatedColor);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);

            float cx = w - dpF(getContext(), 28);
            float cy = h - dpF(getContext(), 22);
            float circleR = dpF(getContext(), 48);

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dpF(getContext(), 7.5f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(mActive ? mTheme.heroWatermarkColor : ThemeEngine.withAlpha(mTheme.textTertiary, 90));
            canvas.drawCircle(cx, cy, circleR, mPaint);

            if (mActive) {
                mCheckPath.reset();
                mCheckPath.moveTo(cx - dpF(getContext(), 20), cy + dpF(getContext(), 2));
                mCheckPath.lineTo(cx - dpF(getContext(), 5), cy + dpF(getContext(), 16));
                mCheckPath.lineTo(cx + dpF(getContext(), 24), cy - dpF(getContext(), 14));
                canvas.drawPath(mCheckPath, mPaint);
            } else {
                float barOffset = dpF(getContext(), 9);
                float barH = dpF(getContext(), 14);
                canvas.drawLine(cx - barOffset, cy - barH, cx - barOffset, cy + barH, mPaint);
                canvas.drawLine(cx + barOffset, cy - barH, cx + barOffset, cy + barH, mPaint);
            }

            float padX = dpF(getContext(), 22);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mActive ? mTheme.heroTextPrimary : mTheme.textPrimary);
            mPaint.setTextSize(dpF(getContext(), 21));
            mPaint.setFakeBoldText(true);
            canvas.drawText(mActive ? "模块已激活" : "模块已暂停", padX, dpF(getContext(), 42), mPaint);

            mPaint.setFakeBoldText(false);
            mPaint.setColor(mActive ? mTheme.heroTextSecondary : mTheme.textSecondary);
            mPaint.setTextSize(dpF(getContext(), 14));
            canvas.drawText(mActive ? (mVersionText + " · 已连通 SystemUI") : (mVersionText + " · 总开关已关闭"),
                    padX, dpF(getContext(), 66), mPaint);

            mPaint.setFakeBoldText(true);
            mPaint.setColor(mActive ? mTheme.heroTextPrimary : mTheme.textSecondary);
            mPaint.setTextSize(dpF(getContext(), 15.5f));
            canvas.drawText("FullAOD", padX, h - dpF(getContext(), 20), mPaint);

            canvas.restore();
        }
    }

    // =========================================================================
    // 4. Theme Phone Mockup View (Image 4 Style)
    // =========================================================================
    public static class ThemeMockupView extends View {
        private ThemeEngine mTheme;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        public ThemeMockupView(Context ctx, ThemeEngine theme) {
            super(ctx);
            mTheme = theme;
        }

        public void setTheme(ThemeEngine theme) {
            mTheme = theme;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, dp(getContext(), 276));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float phoneW = dpF(getContext(), 146);
            float phoneH = dpF(getContext(), 252);
            float left = (w - phoneW) * 0.5f;
            float top = (h - phoneH) * 0.5f;
            float right = left + phoneW;
            float bottom = top + phoneH;
            float corner = dpF(getContext(), 24);

            mRect.set(left, top, right, bottom);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTheme.bgColor);
            canvas.drawRoundRect(mRect, corner, corner, mPaint);

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dpF(getContext(), 1.6f));
            mPaint.setColor(ThemeEngine.withAlpha(mTheme.textSecondary, 110));
            canvas.drawRoundRect(mRect, corner, corner, mPaint);

            float innerPad = dpF(getContext(), 10);
            float contentLeft = left + innerPad;
            float contentRight = right - innerPad;

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTheme.textPrimary);
            mPaint.setTextSize(dpF(getContext(), 11.5f));
            mPaint.setFakeBoldText(true);
            canvas.drawText("FullAOD", contentLeft + dpF(getContext(), 2), top + dpF(getContext(), 28), mPaint);

            float heroTop = top + dpF(getContext(), 38);
            float heroBottom = heroTop + dpF(getContext(), 38);
            mRect.set(contentLeft, heroTop, contentRight, heroBottom);
            mPaint.setColor(mTheme.heroCardBg);
            canvas.drawRoundRect(mRect, dpF(getContext(), 8), dpF(getContext(), 8), mPaint);

            float bodyTop = heroBottom + dpF(getContext(), 8);
            float bodyBottom = bottom - dpF(getContext(), 42);
            mRect.set(contentLeft, bodyTop, contentRight, bodyBottom);
            mPaint.setColor(mTheme.cardColor);
            canvas.drawRoundRect(mRect, dpF(getContext(), 8), dpF(getContext(), 8), mPaint);

            mRect.set(contentLeft, bodyBottom + dpF(getContext(), 6), contentRight, bodyBottom + dpF(getContext(), 14));
            mPaint.setColor(mTheme.cardColor);
            canvas.drawRoundRect(mRect, dpF(getContext(), 4), dpF(getContext(), 4), mPaint);

            mRect.set(contentLeft, bodyBottom + dpF(getContext(), 20), contentRight, bodyBottom + dpF(getContext(), 28));
            canvas.drawRoundRect(mRect, dpF(getContext(), 4), dpF(getContext(), 4), mPaint);

            boolean floating = ThemeEngine.isFloatingBar(getContext());
            float barLeft = floating ? (left + dpF(getContext(), 22)) : (left + dpF(getContext(), 6));
            float barRight = floating ? (right - dpF(getContext(), 22)) : (right - dpF(getContext(), 6));
            float barTop = bottom - dpF(getContext(), 34);
            float barBottom = bottom - dpF(getContext(), 10);
            float barR = floating ? (barBottom - barTop) * 0.5f : dpF(getContext(), 10);

            mRect.set(barLeft, barTop, barRight, barBottom);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(ThemeEngine.withAlpha(mTheme.cardElevatedColor, 220));
            canvas.drawRoundRect(mRect, barR, barR, mPaint);

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(dpF(getContext(), 1f));
            mPaint.setColor(mTheme.glassBorderColor);
            canvas.drawRoundRect(mRect, barR, barR, mPaint);

            mPaint.setStyle(Paint.Style.FILL);
            int dots = 4;
            float dotSize = dpF(getContext(), 11);
            float totalW = barRight - barLeft;
            float step = totalW / dots;
            float dotCy = (barTop + barBottom) * 0.5f;
            for (int i = 0; i < dots; i++) {
                float dotCx = barLeft + step * (i + 0.5f);
                mRect.set(dotCx - dotSize * 0.5f, dotCy - dotSize * 0.5f,
                        dotCx + dotSize * 0.5f, dotCy + dotSize * 0.5f);
                mPaint.setColor(i == 0 ? mTheme.primary : ThemeEngine.withAlpha(mTheme.textPrimary, 210));
                canvas.drawRoundRect(mRect, dpF(getContext(), 3), dpF(getContext(), 3), mPaint);
            }
        }
    }

    // =========================================================================
    // 5. Real-Time Top Blur Header Bar (RenderNode + RenderEffect Blur)
    // =========================================================================
    public static class BlurTopBarView extends View {
        public interface OnTopActionListener {
            void onLeftClicked();
            void onRightClicked();
        }

        private ThemeEngine mTheme;
        private BackdropStageLayout mBackdropStage;
        private RenderNode mBlurNode;
        private float mScrollFade = 0f;
        private String mTitle = "";
        private boolean mShowBack = false;
        private boolean mShowRefresh = false;
        private OnTopActionListener mListener;
        private int mStatusBarInset = 0;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mArcRect = new RectF();

        public BlurTopBarView(Context ctx, ThemeEngine theme) {
            super(ctx);
            mTheme = theme;
            if (Build.VERSION.SDK_INT >= 29) {
                mBlurNode = new RenderNode("TopBarBlur");
            }
        }

        public void setBackdropStage(BackdropStageLayout stage) {
            mBackdropStage = stage;
        }

        public void setStatusBarInset(int inset) {
            mStatusBarInset = inset;
            requestLayout();
        }

        public void configure(ThemeEngine theme, String title, boolean showBack, boolean showRefresh, float scrollFade) {
            mTheme = theme;
            mTitle = title != null ? title : "";
            mShowBack = showBack;
            mShowRefresh = showRefresh;
            mScrollFade = Math.max(0f, Math.min(1f, scrollFade));
            invalidate();
        }

        public void setScrollFade(float fade) {
            float clamped = Math.max(0f, Math.min(1f, fade));
            if (Math.abs(mScrollFade - clamped) > 0.005f) {
                mScrollFade = clamped;
                invalidate();
            }
        }

        public void setOnTopActionListener(OnTopActionListener l) {
            mListener = l;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = mStatusBarInset + dp(getContext(), 54);
            setMeasuredDimension(w, h);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            if (y < mStatusBarInset) return false;
            float hitW = dpF(getContext(), 64);
            boolean hitLeft = mShowBack && x <= hitW;
            boolean hitRight = mShowRefresh && x >= (getWidth() - hitW);

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return hitLeft || hitRight || mScrollFade > 0.25f;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (hitLeft && mListener != null) {
                    mListener.onLeftClicked();
                    return true;
                }
                if (hitRight && mListener != null) {
                    mListener.onRightClicked();
                    return true;
                }
            }
            return hitLeft || hitRight;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            boolean blurOn = ThemeEngine.isBlurEnabled(getContext());
            float blurStrength = mShowBack ? Math.max(0.35f, mScrollFade) : mScrollFade;

            if (blurStrength > 0.02f) {
                RenderNode stageNode = (mBackdropStage != null) ? mBackdropStage.getStageNode() : null;
                if (blurOn && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()
                        && mBlurNode != null && stageNode != null) {
                    try {
                        mBlurNode.setPosition(0, 0, w, h);
                        RecordingCanvas rc = mBlurNode.beginRecording(w, h);
                        rc.drawColor(mTheme.bgColor);
                        rc.save();
                        rc.translate(-getLeft(), -getTop());
                        rc.drawRenderNode(stageNode);
                        rc.restore();
                        mBlurNode.endRecording();

                        float radius = dpF(getContext(), 22f) * Math.min(1f, blurStrength * 1.3f);
                        if (radius > 1f) {
                            mBlurNode.setRenderEffect(RenderEffect.createBlurEffect(
                                    radius, radius, Shader.TileMode.CLAMP));
                        } else {
                            mBlurNode.setRenderEffect(null);
                        }
                        canvas.save();
                        canvas.clipRect(0, 0, w, h);
                        canvas.drawRenderNode(mBlurNode);
                        canvas.restore();
                    } catch (Throwable ignored) {
                    }
                }

                int overlayAlpha = (int) ((blurOn ? 165 : 242) * blurStrength);
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.bgColor, overlayAlpha));
                canvas.drawRect(0, 0, w, h, mPaint);

                mPaint.setColor(ThemeEngine.withAlpha(mTheme.dividerColor, (int) (35 * blurStrength)));
                canvas.drawRect(0, h - 1.5f, w, h, mPaint);
            }

            float barCenterY = mStatusBarInset + (h - mStatusBarInset) * 0.5f;

            if (mShowBack) {
                float cx = dpF(getContext(), 28);
                float sz = dpF(getContext(), 9);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 2.1f));
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setColor(mTheme.textPrimary);

                canvas.drawLine(cx - sz, barCenterY, cx + sz, barCenterY, mPaint);
                mPath.reset();
                mPath.moveTo(cx - sz * 0.2f, barCenterY - sz * 0.85f);
                mPath.lineTo(cx - sz, barCenterY);
                mPath.lineTo(cx - sz * 0.2f, barCenterY + sz * 0.85f);
                canvas.drawPath(mPath, mPaint);
            }

            if (mShowRefresh) {
                float cx = w - dpF(getContext(), 30);
                float r = dpF(getContext(), 8.5f);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 2.0f));
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setColor(mTheme.textPrimary);

                mArcRect.set(cx - r, barCenterY - r, cx + r, barCenterY + r);
                canvas.drawArc(mArcRect, 35, 290, false, mPaint);

                float tipX = (float) (cx + r * Math.cos(Math.toRadians(325)));
                float tipY = (float) (barCenterY + r * Math.sin(Math.toRadians(325)));
                canvas.drawLine(tipX, tipY, tipX - dpF(getContext(), 4.5f), tipY - dpF(getContext(), 1f), mPaint);
                canvas.drawLine(tipX, tipY, tipX + dpF(getContext(), 1f), tipY - dpF(getContext(), 4.8f), mPaint);
            }

            if (mScrollFade > 0.08f && !mTitle.isEmpty()) {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setFakeBoldText(true);
                mPaint.setTextSize(dpF(getContext(), 19));
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.textPrimary, (int) (255 * mScrollFade)));
                float tw = mPaint.measureText(mTitle);
                float tx = mShowBack ? (w - tw) * 0.5f : dpF(getContext(), 22);
                Paint.FontMetrics fm = mPaint.getFontMetrics();
                float ty = barCenterY - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(mTitle, tx, ty, mPaint);
            }
        }
    }

    // =========================================================================
    // 6. Floating Liquid Glass Bottom Bar (Kyant0 AGSL Refraction + Blur)
    // =========================================================================
    public static class LiquidGlassBottomBarView extends View {
        public interface OnTabSelectedListener {
            void onTabSelected(int index);
        }

        private ThemeEngine mTheme;
        private BackdropStageLayout mBackdropStage;
        private RenderNode mGlassNode;
        private RuntimeShader mRefractionShader;
        private RuntimeShader mHighlightShader;

        private int mSelectedTab = 0; // 0 = 主页, 1 = 设置
        private float mIndicatorPos = 0f;
        private ValueAnimator mTabAnim;
        private OnTabSelectedListener mListener;
        private int mActiveFeatureCount = 4;
        private boolean mModuleEnabled = true;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBarRect = new RectF();
        private final RectF mPillRect = new RectF();
        private final Path mClipPath = new Path();
        private final Path mIconPath = new Path();

        private boolean mIsDraggingPill = false;
        private boolean mHasDraggedBeyondSlop = false;
        private float mTouchDownX = 0f;

        public LiquidGlassBottomBarView(Context ctx, ThemeEngine theme) {
            super(ctx);
            mTheme = theme;
            if (Build.VERSION.SDK_INT >= 29) {
                mGlassNode = new RenderNode("LiquidGlassBar");
            }
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    mRefractionShader = new RuntimeShader(LiquidGlassShaders.REFRACTION_WITH_DISPERSION_AGSL);
                    mHighlightShader = new RuntimeShader(LiquidGlassShaders.HIGHLIGHT_RIM_AGSL);
                } catch (Throwable ignored) {
                }
            }
        }

        public void setBackdropStage(BackdropStageLayout stage) {
            mBackdropStage = stage;
        }

        public void setTheme(ThemeEngine theme) {
            mTheme = theme;
            requestLayout();
            invalidate();
        }

        public void setBadgeInfo(boolean moduleEnabled, int activeCount) {
            mModuleEnabled = moduleEnabled;
            mActiveFeatureCount = activeCount;
            invalidate();
        }

        public void setOnTabSelectedListener(OnTabSelectedListener l) {
            mListener = l;
        }

        public int getSelectedTab() {
            return mSelectedTab;
        }

        public void setSelectedTab(int index, boolean animate, boolean notify) {
            int clamped = Math.max(0, Math.min(1, index));
            mSelectedTab = clamped;
            if (mTabAnim != null) mTabAnim.cancel();
            if (animate) {
                mTabAnim = ValueAnimator.ofFloat(mIndicatorPos, (float) clamped);
                mTabAnim.setDuration(280);
                mTabAnim.setInterpolator(new OvershootInterpolator(0.95f));
                mTabAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mIndicatorPos = (Float) animation.getAnimatedValue();
                        invalidate();
                    }
                });
                mTabAnim.start();
            } else {
                mIndicatorPos = clamped;
                invalidate();
            }
            if (notify && mListener != null) {
                mListener.onTabSelected(clamped);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int screenW = MeasureSpec.getSize(widthMeasureSpec);
            boolean floating = ThemeEngine.isFloatingBar(getContext());
            int h = floating ? dp(getContext(), 66) : dp(getContext(), 68);
            setMeasuredDimension(screenW, h);
        }

        private void computeCapsuleBounds(RectF out) {
            float w = getWidth();
            float h = getHeight();
            boolean floating = ThemeEngine.isFloatingBar(getContext());
            if (floating) {
                float capW = Math.min(w - dpF(getContext(), 56), dpF(getContext(), 284));
                float left = (w - capW) * 0.5f;
                out.set(left, dpF(getContext(), 2), left + capW, h - dpF(getContext(), 2));
            } else {
                out.set(0, 0, w, h);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            computeCapsuleBounds(mBarRect);
            float x = event.getX();
            float y = event.getY();
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                if (!mBarRect.contains(x, y)) return false;
                mIsDraggingPill = true;
                mHasDraggedBeyondSlop = false;
                mTouchDownX = x;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            } else if (action == MotionEvent.ACTION_MOVE && mIsDraggingPill) {
                if (!mHasDraggedBeyondSlop && Math.abs(x - mTouchDownX) > dpF(getContext(), 6f)) {
                    mHasDraggedBeyondSlop = true;
                    if (mTabAnim != null) mTabAnim.cancel();
                }
                if (mHasDraggedBeyondSlop) {
                    float rel = (x - mBarRect.left) / Math.max(1f, mBarRect.width());
                    float pos = Math.max(0f, Math.min(1f, (rel - 0.25f) * 2f));
                    mIndicatorPos = pos;
                    invalidate();
                }
                return true;
            } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && mIsDraggingPill) {
                mIsDraggingPill = false;
                mHasDraggedBeyondSlop = false;
                int target = (x < mBarRect.centerX()) ? 0 : 1;
                setSelectedTab(target, true, true);
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            computeCapsuleBounds(mBarRect);
            boolean floating = ThemeEngine.isFloatingBar(getContext());
            boolean blurOn = ThemeEngine.isBlurEnabled(getContext());
            boolean liquidOn = floating && ThemeEngine.isLiquidGlass(getContext());

            float barW = mBarRect.width();
            float barH = mBarRect.height();
            float radius = floating ? barH * 0.5f : dpF(getContext(), 0);

            mClipPath.reset();
            if (floating) {
                mClipPath.addRoundRect(mBarRect, radius, radius, Path.Direction.CW);
            } else {
                mClipPath.addRect(mBarRect, Path.Direction.CW);
            }

            // 1. Hardware-accelerated Real-Time Liquid Glass / Blur Backdrop
            canvas.save();
            canvas.clipPath(mClipPath);

            boolean drewBackdropNode = false;
            RenderNode stageNode = (mBackdropStage != null) ? mBackdropStage.getStageNode() : null;
            if ((blurOn || liquidOn) && Build.VERSION.SDK_INT >= 31
                    && canvas.isHardwareAccelerated() && mGlassNode != null && stageNode != null) {
                try {
                    int bw = Math.max(1, Math.round(barW));
                    int bh = Math.max(1, Math.round(barH));
                    mGlassNode.setPosition(0, 0, bw, bh);
                    RecordingCanvas rc = mGlassNode.beginRecording(bw, bh);
                    rc.drawColor(mTheme.bgColor);
                    rc.save();
                    rc.translate(-(getLeft() + mBarRect.left), -(getTop() + mBarRect.top));
                    rc.drawRenderNode(stageNode);
                    rc.restore();
                    mGlassNode.endRecording();

                    float blurRad = blurOn ? (liquidOn ? dpF(getContext(), 11f) : dpF(getContext(), 22f)) : 0f;
                    RenderEffect effect = LiquidGlassShaders.createLiquidGlassEffect(
                            mRefractionShader,
                            bw, bh, radius,
                            dpF(getContext(), 22f),
                            dpF(getContext(), 17f),
                            blurOn, blurRad,
                            liquidOn
                    );
                    mGlassNode.setRenderEffect(effect);

                    canvas.save();
                    canvas.translate(mBarRect.left, mBarRect.top);
                    canvas.drawRenderNode(mGlassNode);
                    canvas.restore();
                    drewBackdropNode = true;
                } catch (Throwable ignored) {
                }
            }

            // 2. Glass Surface Tint Layer
            mPaint.setStyle(Paint.Style.FILL);
            if (drewBackdropNode) {
                mPaint.setColor(mTheme.glassSurfaceColor);
            } else {
                mPaint.setColor(ThemeEngine.withAlpha(mTheme.cardElevatedColor, 244));
            }
            canvas.drawRoundRect(mBarRect, radius, radius, mPaint);

            if (liquidOn) {
                mPaint.setShader(new LinearGradient(
                        mBarRect.left, mBarRect.top, mBarRect.left, mBarRect.bottom,
                        new int[]{
                                ThemeEngine.withAlpha(0xFFFFFFFF, mTheme.isDark ? 34 : 95),
                                ThemeEngine.withAlpha(0xFFFFFFFF, 4),
                                ThemeEngine.withAlpha(mTheme.primary, mTheme.isDark ? 20 : 30)
                        },
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP
                ));
                canvas.drawRoundRect(mBarRect, radius, radius, mPaint);
                mPaint.setShader(null);
            }

            // 3. Sliding Active Tab Inner Pill (exact Image 2 & Image 3 capsule indicator)
            float pad = dpF(getContext(), 5f);
            float innerW = (barW - pad * 2f) * 0.5f;
            float pillLeft = mBarRect.left + pad + innerW * mIndicatorPos;
            mPillRect.set(pillLeft, mBarRect.top + pad, pillLeft + innerW, mBarRect.bottom - pad);
            float pillR = mPillRect.height() * 0.5f;

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTheme.glassPillActiveColor);
            canvas.drawRoundRect(mPillRect, pillR, pillR, mPaint);

            if (liquidOn) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 1.0f));
                mPaint.setColor(ThemeEngine.withAlpha(0xFFFFFFFF, mTheme.isDark ? 48 : 110));
                canvas.drawRoundRect(mPillRect, pillR, pillR, mPaint);
            }

            // 4. Outer Specular Rim Highlight (Kyant0 AGSL Highlight Shader)
            if (floating) {
                mRimPaint.setStyle(Paint.Style.STROKE);
                mRimPaint.setStrokeWidth(dpF(getContext(), 1.5f));
                if (liquidOn && Build.VERSION.SDK_INT >= 33 && mHighlightShader != null) {
                    canvas.save();
                    canvas.translate(mBarRect.left, mBarRect.top);
                    LiquidGlassShaders.updateHighlightShader(
                            mHighlightShader, barW, barH, radius, mTheme.isDark);
                    mRimPaint.setShader(mHighlightShader);
                    RectF localRect = new RectF(0.75f, 0.75f, barW - 0.75f, barH - 0.75f);
                    canvas.drawRoundRect(localRect, radius, radius, mRimPaint);
                    mRimPaint.setShader(null);
                    canvas.restore();
                } else {
                    mRimPaint.setColor(mTheme.glassBorderColor);
                    canvas.drawRoundRect(mBarRect, radius, radius, mRimPaint);
                }
            } else {
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(mTheme.dividerColor);
                canvas.drawRect(mBarRect.left, mBarRect.top, mBarRect.right, mBarRect.top + 1.5f, mPaint);
            }

            // 5. Draw Tab Icons & Labels ("主页" and "设置")
            float tabW = barW * 0.5f;
            drawTabItem(canvas, 0, "主页", mBarRect.left + tabW * 0.5f, mBarRect.centerY(),
                    1f - Math.min(1f, Math.abs(mIndicatorPos - 0f)));
            drawTabItem(canvas, 1, "设置", mBarRect.left + tabW * 1.5f, mBarRect.centerY(),
                    1f - Math.min(1f, Math.abs(mIndicatorPos - 1f)));

            canvas.restore();
        }

        private void drawTabItem(Canvas canvas, int tabIndex, String label, float cx, float cy, float selFraction) {
            int color = lerpColor(mTheme.textSecondary, mTheme.primary, selFraction);
            float iconCy = cy - dpF(getContext(), 8.5f);
            float r = dpF(getContext(), 9.5f);

            mPaint.setColor(color);
            if (tabIndex == 0) {
                mIconPath.reset();
                mIconPath.moveTo(cx, iconCy - r * 0.95f);
                mIconPath.lineTo(cx - r * 1.05f, iconCy - r * 0.05f);
                mIconPath.lineTo(cx - r * 0.75f, iconCy - r * 0.05f);
                mIconPath.lineTo(cx - r * 0.75f, iconCy + r * 0.85f);
                mIconPath.lineTo(cx - r * 0.22f, iconCy + r * 0.85f);
                mIconPath.lineTo(cx - r * 0.22f, iconCy + r * 0.25f);
                mIconPath.lineTo(cx + r * 0.22f, iconCy + r * 0.25f);
                mIconPath.lineTo(cx + r * 0.22f, iconCy + r * 0.85f);
                mIconPath.lineTo(cx + r * 0.75f, iconCy + r * 0.85f);
                mIconPath.lineTo(cx + r * 0.75f, iconCy - r * 0.05f);
                mIconPath.lineTo(cx + r * 1.05f, iconCy - r * 0.05f);
                mIconPath.close();

                mPaint.setStyle(selFraction > 0.5f ? Paint.Style.FILL : Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 1.8f));
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(mIconPath, mPaint);
            } else {
                mIconPath.reset();
                for (int i = 0; i < 6; i++) {
                    double angle = Math.toRadians(60 * i - 90);
                    float px = (float) (cx + r * Math.cos(angle));
                    float py = (float) (iconCy + r * Math.sin(angle));
                    if (i == 0) mIconPath.moveTo(px, py);
                    else mIconPath.lineTo(px, py);
                }
                mIconPath.close();
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dpF(getContext(), 2.0f));
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(mIconPath, mPaint);
                canvas.drawCircle(cx, iconCy, r * 0.36f, mPaint);
            }

            if (ThemeEngine.isBottomBadge(getContext())) {
                if (tabIndex == 0) {
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setColor(mModuleEnabled ? mTheme.primary : mTheme.textTertiary);
                    canvas.drawCircle(cx + r * 1.15f, iconCy - r * 0.65f, dpF(getContext(), 3.2f), mPaint);
                } else if (mActiveFeatureCount > 0) {
                    String badgeTxt = String.valueOf(mActiveFeatureCount);
                    float bx = cx + r * 1.25f;
                    float by = iconCy - r * 0.55f;
                    float br = dpF(getContext(), 7f);
                    mPaint.setStyle(Paint.Style.FILL);
                    mPaint.setColor(mTheme.primaryContainer);
                    canvas.drawCircle(bx, by, br, mPaint);
                    mPaint.setColor(mTheme.onPrimaryContainer);
                    mPaint.setTextSize(dpF(getContext(), 9f));
                    mPaint.setFakeBoldText(true);
                    float bw = mPaint.measureText(badgeTxt);
                    canvas.drawText(badgeTxt, bx - bw * 0.5f, by + dpF(getContext(), 3.2f), mPaint);
                }
            }

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            mPaint.setTextSize(dpF(getContext(), 11.5f));
            mPaint.setFakeBoldText(selFraction > 0.5f);
            float tw = mPaint.measureText(label);
            canvas.drawText(label, cx - tw * 0.5f, cy + dpF(getContext(), 15.5f), mPaint);
        }
    }

    // =========================================================================
    // 7. Crisp Vector Icon View for Preference Items (Images 3, 4, 5 icons)
    // =========================================================================
    public static class VectorIconView extends View {
        public static final int ICON_MONET = 1;
        public static final int ICON_PALETTE = 2;
        public static final int ICON_STYLE = 3;
        public static final int ICON_SPEC = 4;
        public static final int ICON_BLUR = 5;
        public static final int ICON_FLOAT_BAR = 6;
        public static final int ICON_LIQUID_DROP = 7;
        public static final int ICON_BADGE_123 = 8;
        public static final int ICON_PREDICTIVE_BACK = 9;
        public static final int ICON_EDGE_SWIPE = 10;
        public static final int ICON_PAGE_SWITCH = 11;
        public static final int ICON_AOD_POWER = 12;
        public static final int ICON_CHEVRON_RIGHT = 20;
        public static final int ICON_UP_DOWN_ARROWS = 21;

        private final int mType;
        private int mColor;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Path mPath = new Path();

        public VectorIconView(Context ctx, int type, int color) {
            super(ctx);
            mType = type;
            mColor = color;
        }

        public void setColor(int color) {
            mColor = color;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int sz = (mType >= 20) ? dp(getContext(), 18) : dp(getContext(), 24);
            setMeasuredDimension(sz, sz);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;

            mPaint.setColor(mColor);
            mPaint.setStrokeWidth(dpF(getContext(), 1.8f));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);

            switch (mType) {
                case ICON_MONET: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float s = dpF(getContext(), 8.5f);
                    mRect.set(cx - s, cy - s, cx + s, cy + s);
                    canvas.drawRoundRect(mRect, dpF(getContext(), 3f), dpF(getContext(), 3f), mPaint);
                    mPath.reset();
                    mPath.moveTo(cx - s * 0.8f, cy + s * 0.6f);
                    mPath.lineTo(cx - s * 0.2f, cy);
                    mPath.lineTo(cx + s * 0.3f, cy + s * 0.4f);
                    mPath.lineTo(cx + s * 0.6f, cy + s * 0.1f);
                    mPath.lineTo(cx + s * 0.85f, cy + s * 0.6f);
                    canvas.drawPath(mPath, mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - s * 0.35f, cy - s * 0.38f, dpF(getContext(), 1.6f), mPaint);
                    break;
                }
                case ICON_PALETTE: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float r = dpF(getContext(), 7.5f);
                    canvas.drawLine(cx - r * 0.8f, cy + r * 0.8f, cx + r * 0.3f, cy - r * 0.3f, mPaint);
                    canvas.drawLine(cx - r * 0.3f, cy + r * 0.8f, cx + r * 0.8f, cy - r * 0.3f, mPaint);
                    canvas.drawLine(cx - r * 0.1f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.1f, mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx + r * 0.6f, cy - r * 0.6f, dpF(getContext(), 2.8f), mPaint);
                    break;
                }
                case ICON_STYLE: {
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.save();
                    canvas.rotate(15f, cx, cy);
                    mRect.set(cx - dpF(getContext(), 5), cy - dpF(getContext(), 8),
                            cx + dpF(getContext(), 6), cy + dpF(getContext(), 8));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 2.5f), dpF(getContext(), 2.5f), mPaint);
                    canvas.restore();
                    mPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(new RectF(cx - dpF(getContext(), 8), cy - dpF(getContext(), 6),
                            cx + dpF(getContext(), 4), cy + dpF(getContext(), 8)), 110, 140, false, mPaint);
                    break;
                }
                case ICON_SPEC: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float r = dpF(getContext(), 7.5f);
                    canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mPaint);
                    canvas.drawLine(cx + r, cy - r, cx - r, cy + r, mPaint);
                    break;
                }
                case ICON_BLUR: {
                    mPaint.setStyle(Paint.Style.FILL);
                    for (int ix = -2; ix <= 2; ix++) {
                        for (int iy = -2; iy <= 2; iy++) {
                            float dist = (float) Math.hypot(ix, iy);
                            if (dist <= 2.3f) {
                                float dotR = dpF(getContext(), Math.max(0.7f, 1.85f - dist * 0.45f));
                                canvas.drawCircle(cx + ix * dpF(getContext(), 3.6f),
                                        cy + iy * dpF(getContext(), 3.6f), dotR, mPaint);
                            }
                        }
                    }
                    break;
                }
                case ICON_FLOAT_BAR: {
                    mPaint.setStyle(Paint.Style.FILL);
                    float w2 = dpF(getContext(), 9f);
                    float h2 = dpF(getContext(), 7f);
                    mRect.set(cx - w2, cy - h2, cx + w2, cy + h2);
                    canvas.drawRoundRect(mRect, dpF(getContext(), 2.5f), dpF(getContext(), 2.5f), mPaint);
                    int lum = (Color.red(mColor) * 299 + Color.green(mColor) * 587 + Color.blue(mColor) * 114) / 1000;
                    mPaint.setColor(lum > 128 ? 0xFF141218 : 0xFFFFFFFF);
                    canvas.drawRoundRect(new RectF(cx - w2 * 0.72f, cy + h2 * 0.25f, cx + w2 * 0.72f, cy + h2 * 0.65f),
                            dpF(getContext(), 1.5f), dpF(getContext(), 1.5f), mPaint);
                    break;
                }
                case ICON_LIQUID_DROP: {
                    mPaint.setStyle(Paint.Style.FILL);
                    float r = dpF(getContext(), 6.8f);
                    mPath.reset();
                    mPath.moveTo(cx, cy - r * 1.3f);
                    mPath.quadTo(cx + r * 1.15f, cy - r * 0.1f, cx + r * 0.9f, cy + r * 0.55f);
                    mPath.arcTo(new RectF(cx - r * 0.9f, cy - r * 0.2f, cx + r * 0.9f, cy + r * 1.3f), 15, 150, false);
                    mPath.quadTo(cx - r * 1.15f, cy - r * 0.1f, cx, cy - r * 1.3f);
                    mPath.close();
                    canvas.drawPath(mPath, mPaint);
                    break;
                }
                case ICON_BADGE_123: {
                    mPaint.setStyle(Paint.Style.FILL);
                    mRect.set(cx - dpF(getContext(), 9.5f), cy - dpF(getContext(), 7f),
                            cx + dpF(getContext(), 9.5f), cy + dpF(getContext(), 7f));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 2.5f), dpF(getContext(), 2.5f), mPaint);
                    int lum = (Color.red(mColor) * 299 + Color.green(mColor) * 587 + Color.blue(mColor) * 114) / 1000;
                    mPaint.setColor(lum > 128 ? 0xFF141218 : 0xFFFFFFFF);
                    mPaint.setTextSize(dpF(getContext(), 8.5f));
                    mPaint.setFakeBoldText(true);
                    float tw = mPaint.measureText("123");
                    canvas.drawText("123", cx - tw * 0.5f, cy + dpF(getContext(), 3.0f), mPaint);
                    break;
                }
                case ICON_PREDICTIVE_BACK: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float s = dpF(getContext(), 8f);
                    canvas.drawLine(cx - s, cy - dpF(getContext(), 5f), cx + dpF(getContext(), 1f), cy - dpF(getContext(), 5f), mPaint);
                    canvas.drawLine(cx - s, cy, cx - dpF(getContext(), 1f), cy, mPaint);
                    canvas.drawLine(cx - s, cy + dpF(getContext(), 5f), cx + dpF(getContext(), 1f), cy + dpF(getContext(), 5f), mPaint);
                    mPath.reset();
                    mPath.moveTo(cx + s, cy - dpF(getContext(), 4.5f));
                    mPath.lineTo(cx + dpF(getContext(), 3f), cy);
                    mPath.lineTo(cx + s, cy + dpF(getContext(), 4.5f));
                    canvas.drawPath(mPath, mPaint);
                    break;
                }
                case ICON_EDGE_SWIPE: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(new RectF(cx - dpF(getContext(), 7), cy - dpF(getContext(), 8),
                            cx + dpF(getContext(), 7), cy), 200, 140, false, mPaint);
                    mPaint.setStyle(Paint.Style.FILL);
                    mRect.set(cx - dpF(getContext(), 4.5f), cy, cx + dpF(getContext(), 5.5f), cy + dpF(getContext(), 8.5f));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 3f), dpF(getContext(), 3f), mPaint);
                    canvas.drawRoundRect(new RectF(cx - dpF(getContext(), 2f), cy - dpF(getContext(), 5f),
                            cx + dpF(getContext(), 1.5f), cy + dpF(getContext(), 2f)), dpF(getContext(), 1.5f), dpF(getContext(), 1.5f), mPaint);
                    break;
                }
                case ICON_PAGE_SWITCH: {
                    mPaint.setStyle(Paint.Style.FILL);
                    mRect.set(cx - dpF(getContext(), 4.5f), cy - dpF(getContext(), 7f),
                            cx + dpF(getContext(), 4.5f), cy + dpF(getContext(), 7f));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 2f), dpF(getContext(), 2f), mPaint);

                    mRect.set(cx - dpF(getContext(), 9.5f), cy - dpF(getContext(), 5f),
                            cx - dpF(getContext(), 6.2f), cy + dpF(getContext(), 5f));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 1.5f), dpF(getContext(), 1.5f), mPaint);

                    mRect.set(cx + dpF(getContext(), 6.2f), cy - dpF(getContext(), 5f),
                            cx + dpF(getContext(), 9.5f), cy + dpF(getContext(), 5f));
                    canvas.drawRoundRect(mRect, dpF(getContext(), 1.5f), dpF(getContext(), 1.5f), mPaint);
                    break;
                }
                case ICON_AOD_POWER: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    float r = dpF(getContext(), 8.5f);
                    canvas.drawCircle(cx, cy, r, mPaint);
                    canvas.drawLine(cx, cy, cx, cy - r * 0.55f, mPaint);
                    canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.25f, mPaint);
                    break;
                }
                case ICON_CHEVRON_RIGHT: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(dpF(getContext(), 1.7f));
                    float s = dpF(getContext(), 4.8f);
                    mPath.reset();
                    mPath.moveTo(cx - s * 0.5f, cy - s);
                    mPath.lineTo(cx + s * 0.5f, cy);
                    mPath.lineTo(cx - s * 0.5f, cy + s);
                    canvas.drawPath(mPath, mPaint);
                    break;
                }
                case ICON_UP_DOWN_ARROWS: {
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(dpF(getContext(), 1.5f));
                    float w2 = dpF(getContext(), 3.6f);
                    float gap = dpF(getContext(), 2.2f);
                    mPath.reset();
                    mPath.moveTo(cx - w2, cy - gap);
                    mPath.lineTo(cx, cy - gap - w2);
                    mPath.lineTo(cx + w2, cy - gap);
                    canvas.drawPath(mPath, mPaint);
                    mPath.reset();
                    mPath.moveTo(cx - w2, cy + gap);
                    mPath.lineTo(cx, cy + gap + w2);
                    mPath.lineTo(cx + w2, cy + gap);
                    canvas.drawPath(mPath, mPaint);
                    break;
                }
            }
        }
    }
}
