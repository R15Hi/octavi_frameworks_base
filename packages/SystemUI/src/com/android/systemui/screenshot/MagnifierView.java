/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenshot;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * MagnifierView shows a full-res cropped circular display of a given ImageTileSet, contents and
 * positioning dereived from events from a CropView to which it listens.
 *
 * Not meant to be a general-purpose magnifier!
 */
public class MagnifierView extends View implements CropView.CropInteractionListener {
    private Drawable mDrawable;

    private final Paint mShadePaint;
    private final Paint mHandlePaint;

    private Path mOuterCircle;
    private Path mInnerCircle;

    private Path mCheckerboard;
    private Paint mCheckerboardPaint;
    private final float mBorderPx;
    private final int mBorderColor;
    private float mCheckerboardBoxSize = 40;

    private float mLastCropPosition;
    private CropView.CropBoundary mCropBoundary;

    public MagnifierView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MagnifierView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray t = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.MagnifierView, 0, 0);
        mShadePaint = new Paint();
        mShadePaint.setColor(t.getColor(R.styleable.MagnifierView_scrimColor, Color.TRANSPARENT));
        mHandlePaint = new Paint();
        mHandlePaint.setColor(t.getColor(R.styleable.MagnifierView_handleColor, Color.BLACK));
        mHandlePaint.setStrokeWidth(
                t.getDimensionPixelSize(R.styleable.MagnifierView_handleThickness, 20));
        mBorderPx = t.getDimensionPixelSize(R.styleable.MagnifierView_borderThickness, 0);
        mBorderColor = t.getColor(R.styleable.MagnifierView_borderColor, Color.WHITE);
        t.recycle();
        mCheckerboardPaint = new Paint();
        mCheckerboardPaint.setColor(Color.GRAY);
    }

    /**
     * Set the drawable to be displayed by the magnifier.
     */
    public void setDrawable(@NonNull Drawable drawable, int width, int height) {
        mDrawable = drawable;
        mDrawable.setBounds(0, 0, width, height);
        invalidate();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int radius = getWidth() / 2;
        mOuterCircle = new Path();
        mOuterCircle.addCircle(radius, radius, radius, Path.Direction.CW);
        mInnerCircle = new Path();
        mInnerCircle.addCircle(radius, radius, radius - mBorderPx, Path.Direction.CW);
        mCheckerboard = generateCheckerboard();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // TODO: just draw a circle at the end instead of clipping like this?
        canvas.clipPath(mOuterCircle);
        canvas.drawColor(mBorderColor);
        canvas.clipPath(mInnerCircle);

        // Draw a checkerboard pattern for out of bounds.
        canvas.drawPath(mCheckerboard, mCheckerboardPaint);

        if (mDrawable != null) {
            canvas.save();
            // Translate such that the center of this view represents the center of the crop
            // boundary.
            canvas.translate(-mDrawable.getBounds().width() / 2 + getWidth() / 2,
                    -mDrawable.getBounds().height() * mLastCropPosition + getHeight() / 2);
            mDrawable.draw(canvas);
            canvas.restore();
        }

        Rect scrimRect = new Rect(0, 0, getWidth(), getHeight() / 2);
        if (mCropBoundary == CropView.CropBoundary.BOTTOM) {
            scrimRect.offset(0, getHeight() / 2);
        }
        canvas.drawRect(scrimRect, mShadePaint);

        canvas.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2, mHandlePaint);
    }

    @Override
    public void onCropMotionEvent(MotionEvent event, CropView.CropBoundary boundary,
            float cropPosition, int cropPositionPx) {
        mCropBoundary = boundary;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLastCropPosition = cropPosition;
                setTranslationY(cropPositionPx - getHeight() / 2);
                setPivotX(getWidth() / 2);
                setPivotY(getHeight() / 2);
                setScaleX(0.2f);
                setScaleY(0.2f);
                setAlpha(0f);
                setTranslationX((getParentWidth() - getWidth()) / 2);
                setVisibility(View.VISIBLE);
                boolean touchOnRight = event.getX() > getParentWidth() / 2;
                float translateXTarget = touchOnRight ? 0 : getParentWidth() - getWidth();
                animate().alpha(1f).translationX(translateXTarget).scaleX(1f).scaleY(1f).start();
                break;
            case MotionEvent.ACTION_MOVE:
                mLastCropPosition = cropPosition;
                setTranslationY(cropPositionPx - getHeight() / 2);
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                animate().alpha(0).translationX((getParentWidth() - getWidth()) / 2).scaleX(0.2f)
                        .scaleY(0.2f).withEndAction(() -> setVisibility(View.INVISIBLE)).start();
                break;
        }
    }

    private Path generateCheckerboard() {
        Path path = new Path();
        int checkerWidth = (int) Math.ceil(getWidth() / mCheckerboardBoxSize);
        int checkerHeight = (int) Math.ceil(getHeight() / mCheckerboardBoxSize);

        for (int row = 0; row < checkerHeight; row++) {
            // Alternate starting on the first and second column;
            int colStart = (row % 2 == 0) ? 0 : 1;
            for (int col = colStart; col < checkerWidth; col += 2) {
                path.addRect(col * mCheckerboardBoxSize,
                        row * mCheckerboardBoxSize,
                        (col + 1) * mCheckerboardBoxSize,
                        (row + 1) * mCheckerboardBoxSize,
                        Path.Direction.CW);
            }
        }
        return path;
    }

    private int getParentWidth() {
        return ((View) getParent()).getWidth();
    }
}
