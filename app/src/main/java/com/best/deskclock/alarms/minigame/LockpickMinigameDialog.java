// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.minigame;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmation minigame — Tier 2 (mid friction).
 *
 * <p>Mechanic: three lock pins oscillate vertically inside a cylinder cross-section. Tap each pin
 * <em>in order (left → right)</em> while its tip sits within a narrow shear band near the top of
 * its travel. A 3.5-second amber timer bar drains across the cylinder rim. All three pins set
 * before time expires → cylinder rotates open → success. Timer expires → lock holds.
 *
 * <p>Use case: deleting a single / non-recurring locked alarm.
 */
public final class LockpickMinigameDialog extends DialogFragment {

    private static final String TAG = "lockpick_minigame";
    private static final String ARG_KEY = "callback_key";

    private static final Map<String, Runnable> sPending = new HashMap<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static void show(FragmentManager fm, Runnable onSuccess) {
        String key = UUID.randomUUID().toString();
        sPending.put(key, onSuccess);
        Bundle args = new Bundle();
        args.putString(ARG_KEY, key);
        LockpickMinigameDialog d = new LockpickMinigameDialog();
        d.setArguments(args);
        d.show(fm, TAG);
    }

    // -------------------------------------------------------------------------
    // Dialog
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String key = requireArguments().getString(ARG_KEY, "");
        LockpickView view = new LockpickView(requireContext(), () -> {
            Runnable cb = sPending.remove(key);
            if (cb != null) cb.run();
            dismissAllowingStateLoss();
        }, () -> dismissAllowingStateLoss());

        Dialog dialog = new Dialog(requireContext(),
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    // -------------------------------------------------------------------------
    // Game view
    // -------------------------------------------------------------------------

    @SuppressLint("ViewConstructor")
    static final class LockpickView extends View {

        // Design constants
        private static final int PIN_COUNT    = 3;
        private static final long TOTAL_MS    = 3500L;
        private static final float[] FREQS    = {1.2f, 1.5f, 1.8f};
        private static final float[] PHASES   = {0.0f, 0.3f, 0.7f};
        /** Band width as fraction of pin travel for each pin. */
        private static final float[] BANDS    = {0.14f, 0.12f, 0.10f};
        /** y position (0..1) where shear line sits. */
        private static final float SHEAR_Y    = 0.85f;

        // Colors
        private static final int BG_COLOR     = 0xFF0F0F0F;
        private static final int BRASS        = 0xFFB8860B;
        private static final int AMBER        = 0xFFFFBF00;
        private static final int AMBER_SET    = 0xFFFF8C00;
        private static final int FAIL_RED     = 0xFFDC143C;
        private static final int CYLINDER_COL = 0xFF1A1A1A;

        private enum State { PLAYING, SUCCESS, FAIL }
        private State mState = State.PLAYING;

        private final boolean[] mPinSet = new boolean[PIN_COUNT];
        private final float[]   mPinFlash = new float[PIN_COUNT]; // 0..1, flash on mistap

        // Geometry (set in onSizeChanged)
        private float mCylLeft, mCylTop, mCylRight, mCylBottom;
        private float mCylCx, mCylCy;
        private float[] mPinCx;
        private float mPinTravelTop, mPinTravelBottom;
        private float mPinW, mPinH;
        private float mTimerBarY, mTimerBarLeft, mTimerBarRight;
        private float mShearLineY;

        // Animation state
        private float mTimeElapsed = 0f;   // 0..1
        private float mCylinderRotation = 0f;

        // Animators
        private ValueAnimator mTimerAnimator;
        private ValueAnimator mCylinderAnimator;

        // Paints
        private final Paint mBgPaint     = new Paint();
        private final Paint mCylPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCylBorder   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPinPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPinSetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShearPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTimerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFlashPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHintPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mMsgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable mOnSuccess;
        private final Runnable mOnFail;

        @SuppressLint("ClickableViewAccessibility")
        LockpickView(Context ctx, Runnable onSuccess, Runnable onFail) {
            super(ctx);
            mOnSuccess = onSuccess;
            mOnFail    = onFail;
            setLayerType(LAYER_TYPE_HARDWARE, null);
            mPinCx = new float[PIN_COUNT];

            mBgPaint.setColor(BG_COLOR);
            mCylPaint.setColor(CYLINDER_COL);
            mCylPaint.setStyle(Paint.Style.FILL);
            mCylBorder.setColor(BRASS);
            mCylBorder.setStyle(Paint.Style.STROKE);
            mCylBorder.setStrokeWidth(3f);
            mCylBorder.setAntiAlias(true);
            mPinPaint.setColor(BRASS);
            mPinPaint.setStyle(Paint.Style.FILL);
            mPinSetPaint.setColor(AMBER_SET);
            mPinSetPaint.setStyle(Paint.Style.FILL);
            mShearPaint.setColor(AMBER);
            mShearPaint.setStyle(Paint.Style.STROKE);
            mShearPaint.setStrokeWidth(2.5f);
            mTimerPaint.setColor(AMBER);
            mTimerPaint.setStyle(Paint.Style.STROKE);
            mTimerPaint.setStrokeCap(Paint.Cap.ROUND);
            mFlashPaint.setStyle(Paint.Style.FILL);
            mHintPaint.setColor(0x55FFFFFF);
            mHintPaint.setTextAlign(Paint.Align.CENTER);
            mHintPaint.setTypeface(Typeface.DEFAULT);
            mMsgPaint.setTextAlign(Paint.Align.CENTER);
            mMsgPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            mMsgPaint.setAntiAlias(true);

            setOnTouchListener((v, e) -> { handleTouch(e); return true; });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float cx = w / 2f;
            float cy = h / 2f;

            // Cylinder rect — wide rounded rect in centre
            float cw = w * 0.72f;
            float ch = h * 0.50f;
            mCylLeft  = cx - cw / 2;
            mCylTop   = cy - ch / 2 - h * 0.04f;
            mCylRight = cx + cw / 2;
            mCylBottom = cy + ch / 2 - h * 0.04f;
            mCylCx = cx;
            mCylCy = (mCylTop + mCylBottom) / 2f;

            // Timer bar runs along the top rim of the cylinder
            float timerH = h * 0.013f;
            mTimerBarY = mCylTop + timerH / 2f;
            mTimerBarLeft  = mCylLeft + 16f;
            mTimerBarRight = mCylRight - 16f;
            mTimerPaint.setStrokeWidth(timerH);

            // Pin geometry
            mPinW = cw / (PIN_COUNT * 3.5f);
            mPinH = ch * 0.55f;
            float pinSpacing = cw / (PIN_COUNT + 1);
            for (int i = 0; i < PIN_COUNT; i++) {
                mPinCx[i] = mCylLeft + pinSpacing * (i + 1);
            }
            mPinTravelTop    = mCylTop  + ch * 0.08f;
            mPinTravelBottom = mCylTop  + ch * 0.85f;

            // Shear line y
            mShearLineY = mPinTravelTop + (mPinTravelBottom - mPinTravelTop) * (1f - SHEAR_Y);

            mCylBorder.setStrokeWidth(w * 0.005f);
            mHintPaint.setTextSize(h * 0.024f);
            mMsgPaint.setTextSize(h * 0.030f);

            startTimerAnimator();
        }

        private void startTimerAnimator() {
            mTimerAnimator = ValueAnimator.ofFloat(0f, 1f);
            mTimerAnimator.setDuration(TOTAL_MS);
            mTimerAnimator.setInterpolator(new LinearInterpolator());
            mTimerAnimator.addUpdateListener(a -> {
                mTimeElapsed = (float) a.getAnimatedValue();
                invalidate();
            });
            mTimerAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (mState == State.PLAYING) {
                        mState = State.FAIL;
                        postDelayed(mOnFail, 1000);
                        invalidate();
                    }
                }
            });
            mTimerAnimator.start();
        }

        private void handleTouch(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return;
            if (mState != State.PLAYING) return;

            float tx = event.getX();
            float ty = event.getY();

            // Find the next unset pin
            int nextPin = -1;
            for (int i = 0; i < PIN_COUNT; i++) {
                if (!mPinSet[i]) { nextPin = i; break; }
            }
            if (nextPin < 0) return;

            // Check if touch is near this pin's column
            float px = mPinCx[nextPin];
            if (Math.abs(tx - px) > mPinW * 2.5f) return; // wrong column / not near any pin

            // Get current pin y
            float pinY = getPinTopY(nextPin, currentTime());
            boolean inBand = Math.abs(pinY - mShearLineY) < (mPinTravelBottom - mPinTravelTop) * BANDS[nextPin] / 2f;

            if (inBand) {
                mPinSet[nextPin] = true;
                vibrate(15);
                if (nextPin == PIN_COUNT - 1) {
                    // All set!
                    if (mTimerAnimator != null) mTimerAnimator.cancel();
                    mState = State.SUCCESS;
                    animateCylinder();
                }
                invalidate();
            } else {
                // Mistap — flash red
                mPinFlash[nextPin] = 1f;
                ValueAnimator flash = ValueAnimator.ofFloat(1f, 0f);
                flash.setDuration(300);
                final int pinIdx = nextPin;
                flash.addUpdateListener(a -> {
                    mPinFlash[pinIdx] = (float) a.getAnimatedValue();
                    invalidate();
                });
                flash.start();
            }
        }

        private float currentTime() {
            return mTimeElapsed; // 0..1 (matches animation progress)
        }

        private float getPinTopY(int pin, float t) {
            // Oscillate: 0.5 + 0.4 * sin(2π * freq * t * 3.5 + phase)
            // t is 0..1 spanning the full 3.5s window
            double angle = 2 * Math.PI * FREQS[pin] * t * 3.5 + PHASES[pin];
            float norm = 0.5f + 0.4f * (float) Math.sin(angle);
            return mPinTravelTop + (mPinTravelBottom - mPinTravelTop) * norm;
        }

        private void animateCylinder() {
            mCylinderAnimator = ValueAnimator.ofFloat(0f, 90f);
            mCylinderAnimator.setDuration(400);
            mCylinderAnimator.addUpdateListener(a -> {
                mCylinderRotation = (float) a.getAnimatedValue();
                invalidate();
            });
            mCylinderAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    postDelayed(mOnSuccess, 300);
                }
            });
            mCylinderAnimator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, h, mBgPaint);

            // Cylinder body (with rotation if success)
            canvas.save();
            canvas.translate(mCylCx, mCylCy);
            canvas.rotate(mCylinderRotation);
            canvas.translate(-mCylCx, -mCylCy);
            RectF cyl = new RectF(mCylLeft, mCylTop, mCylRight, mCylBottom);
            canvas.drawRoundRect(cyl, 18f, 18f, mCylPaint);
            canvas.drawRoundRect(cyl, 18f, 18f, mCylBorder);
            canvas.restore();

            // Shear line
            canvas.drawLine(mCylLeft + 10f, mShearLineY, mCylRight - 10f, mShearLineY, mShearPaint);

            // Pins
            float t = currentTime();
            for (int i = 0; i < PIN_COUNT; i++) {
                float pinTopY = mPinSet[i] ? mPinTravelTop : getPinTopY(i, t);
                float pinBottomY = mCylBottom - 8f;
                RectF pinRect = new RectF(
                        mPinCx[i] - mPinW / 2, pinTopY,
                        mPinCx[i] + mPinW / 2, pinBottomY);
                if (mPinFlash[i] > 0.01f) {
                    mFlashPaint.setColor(Color.argb(
                            (int)(mPinFlash[i] * 200), 220, 20, 20));
                    canvas.drawRoundRect(pinRect, 6f, 6f, mFlashPaint);
                }
                canvas.drawRoundRect(pinRect, 6f, 6f,
                        mPinSet[i] ? mPinSetPaint : mPinPaint);
            }

            // Timer bar
            float remaining = 1f - mTimeElapsed;
            float barRight = mTimerBarLeft + (mTimerBarRight - mTimerBarLeft) * remaining;
            if (barRight > mTimerBarLeft) {
                mTimerPaint.setColor(remaining < 0.25f ? FAIL_RED : AMBER);
                canvas.drawLine(mTimerBarLeft, mTimerBarY, barRight, mTimerBarY, mTimerPaint);
            }

            // State messages
            if (mState == State.FAIL) {
                mMsgPaint.setColor(0xAAFFFFFF);
                canvas.drawText("Lock held.", w / 2f, h * 0.85f, mMsgPaint);
            } else if (mState == State.SUCCESS) {
                mMsgPaint.setColor(AMBER);
                canvas.drawText("Unlocked.", w / 2f, h * 0.85f, mMsgPaint);
            } else {
                mHintPaint.setColor(0x55FFFFFF);
                int next = -1;
                for (int i = 0; i < PIN_COUNT; i++) {
                    if (!mPinSet[i]) { next = i + 1; break; }
                }
                String hint = next > 0 ? "Tap pin " + next + " in the shear band" : "";
                canvas.drawText(hint, w / 2f, h * 0.90f, mHintPaint);
            }
        }

        private void vibrate(long ms) {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(ms,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    //noinspection deprecation
                    v.vibrate(ms);
                }
            }
        }
    }
}
