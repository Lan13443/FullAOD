package com.operit.fullaod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

public class WallpaperAodView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mMaskPaint = new Paint();
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    private volatile Bitmap mBitmap;
    private float mMaskAlpha = 0.65f;
    private float mShiftX = 0f;
    private float mShiftY = 0f;
    private float mLastBlurRadius = -1f;

    public WallpaperAodView(Context context) {
        super(context);
        mMaskPaint.setColor(Color.BLACK);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    public void setBitmap(Bitmap bmp) {
        if (mBitmap == bmp) return;
        mBitmap = bmp;
        postInvalidateOnAnimation();
    }

    public void setMaskAlpha(float alpha) {
        if (Float.isNaN(alpha)) return;
        float clamped = Math.max(0f, Math.min(1f, alpha));
        if (Math.abs(clamped - mMaskAlpha) < 0.002f) return;
        mMaskAlpha = clamped;
        postInvalidateOnAnimation();
    }

    public void setBlur(float radius) {
        if (Build.VERSION.SDK_INT < 31) return;
        float clamped = Math.max(0f, Math.min(80f, radius));
        if (Math.abs(clamped - mLastBlurRadius) < 0.25f) return;
        mLastBlurRadius = clamped;
        if (clamped > 0.5f) {
            setRenderEffect(RenderEffect.createBlurEffect(clamped, clamped, Shader.TileMode.CLAMP));
        } else {
            setRenderEffect(null);
        }
    }

    public void setPixelShift(float x, float y) {
        if (Float.isNaN(x) || Float.isNaN(y)) return;
        if (Math.abs(x - mShiftX) < 0.05f && Math.abs(y - mShiftY) < 0.05f) return;
        mShiftX = x;
        mShiftY = y;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        canvas.drawColor(Color.BLACK);

        Bitmap bmp = mBitmap;
        if (bmp != null && !bmp.isRecycled()) {
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw > 0 && bh > 0) {
                boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                computeCenterCropSrc(bw, bh, vw, vh, isRtl, mSrc);
                float shiftBleed = Math.max(Math.abs(mShiftX), Math.abs(mShiftY));
                if (shiftBleed > 0f) {
                    shiftBleed += 2f;
                }
                mDst.set(
                        -shiftBleed + mShiftX,
                        -shiftBleed + mShiftY,
                        vw + shiftBleed + mShiftX,
                        vh + shiftBleed + mShiftY
                );
                canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
            }
        }

        int maskAlphaInt = Math.max(0, Math.min(255, Math.round(mMaskAlpha * 255f)));
        if (maskAlphaInt > 0) {
            mMaskPaint.setAlpha(maskAlphaInt);
            canvas.drawRect(0f, 0f, vw, vh, mMaskPaint);
        }
    }

    private static void computeCenterCropSrc(int bw, int bh, int vw, int vh, boolean isRtl, Rect out) {
        if ((long) bw * vh > (long) vw * bh) {
            int cropW = Math.max(1, (int) Math.round((double) bh * vw / vh));
            cropW = Math.min(bw, cropW);
            int left = isRtl ? (bw - cropW) : 0;
            out.set(left, 0, left + cropW, bh);
        } else {
            int cropH = Math.max(1, (int) Math.round((double) bw * vh / vw));
            cropH = Math.min(bh, cropH);
            int top = (bh - cropH) / 2;
            out.set(0, top, bw, top + cropH);
        }
    }
}
