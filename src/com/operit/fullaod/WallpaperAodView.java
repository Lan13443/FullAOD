package com.operit.fullaod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

public class WallpaperAodView extends View {

    private Bitmap mBitmap;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mMaskPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    private float mMaskAlpha = 0.65f;
    private float mShiftX = 0f;
    private float mShiftY = 0f;

    public WallpaperAodView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        mMaskPaint.setColor(Color.BLACK);
    }

    public void setBitmap(Bitmap b) {
        mBitmap = b;
        if (b != null) {
            mSrc.set(0, 0, b.getWidth(), b.getHeight());
        }
        invalidate();
    }

    public void setMaskAlpha(float a) {
        if (a < 0f) a = 0f;
        if (a > 0.95f) a = 0.95f;
        mMaskAlpha = a;
        invalidate();
    }

    /** 高斯模糊（API31+）。bouncer 背景用，radius<=0 表示清除。 */
    public void setBlur(float radius) {
        try {
            if (radius > 0.5f) {
                android.graphics.RenderEffect eff = android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, android.graphics.Shader.TileMode.CLAMP);
                setRenderEffect(eff);
            } else {
                setRenderEffect(null);
            }
        } catch (Throwable ignored) {
        }
        invalidate();
    }

    public void setPixelShift(float x, float y) {
        mShiftX = x;
        mShiftY = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mBitmap != null && !mBitmap.isRecycled()) {
            float s = 1f;
            float dw = w * s;
            float dh = h * s;
            float left = (w - dw) / 2f + mShiftX;
            float top = (h - dh) / 2f + mShiftY;
            mDst.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(mBitmap, mSrc, mDst, mPaint);
        }
        int a = (int) (255f * mMaskAlpha);
        if (a > 0) {
            mMaskPaint.setAlpha(a);
            canvas.drawRect(0, 0, w, h, mMaskPaint);
        }
    }
}