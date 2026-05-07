// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.minigame;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Confirmation minigame — Tier 3 (highest friction).
 *
 * <p>Mechanic: a 5-second countdown ticks on an LCD-style display. Four coloured wires run
 * across a device panel. A one-second hint reveals which wire to cut. The player drags the
 * wire-cutter (resting bottom-right) across the correct wire to snip it.  Correct wire →
 * device powers down → success.  Wrong wire or timeout → device shudders → fail.
 *
 * <p>Hint strategy: the correct wire index is derived from (seconds digit of the alarm minute
 * field) mod 4, giving a deterministic but slightly non-obvious result.  Each wire also carries a
 * small geometric symbol so the hint works without colour alone.
 *
 * <p>Use case: deleting a recurring locked alarm series.
 */
public final class BombDefusalMinigameDialog extends DialogFragment {

    private static final String TAG = "bomb_defusal_minigame";
    private static final String ARG_KEY    = "callback_key";
    private static final String ARG_HINT   = "hint_index";   // 0-3

    private static final Map<String, Runnable> sPending = new HashMap<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * @param hintIndex 0-3 — which wire is correct.  Callers should derive this from
     *                  {@code (alarm.minutes % 10) % 4} or similar.
     */
    public static void show(FragmentManager fm, Runnable onSuccess, int hintIndex) {
        String key = UUID.randomUUID().toString();
        sPending.put(key, onSuccess);
        Bundle args = new Bundle();
        args.putString(ARG_KEY, key);
        args.putInt(ARG_HINT, hintIndex);
        BombDefusalMinigameDialog d = new BombDefusalMinigameDialog();
        d.setArguments(args);
        d.show(fm, TAG);
    }

    /** Convenience overload — caller doesn't need to compute hint index. */
    public static void show(FragmentManager fm, Runnable onSuccess) {
        show(fm, onSuccess, 0);
    }

    // -------------------------------------------------------------------------
    // Dialog
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String key = requireArguments().getString(ARG_KEY, "");
        int hintIndex = requireArguments().getInt(ARG_HINT, 0);

        BombView view = new BombView(requireContext(), hintIndex,
                () -> {
                    Runnable cb = sPending.remove(key);
                    if (cb != null) cb.run();
                    dismissAllowingStateLoss();
                },
                () -> dismissAllowingStateLoss()
        );

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
    static final class BombView extends View {

        private static final long TIMER_MS = 5000L;

        // Wire colours — the ONLY place saturated colours appear
        private static final int[] WIRE_COLORS = {
                0xFFDC143C,   // red
                0xFF1E90FF,   // blue
                0xFFFFD700,   // yellow
                0xFF32CD32    // green
        };
        private static final String[] WIRE_NAMES = {"RED", "BLUE", "YELLOW", "GREEN"};

        // Background / device colours
        private static final int BG_COLOR       = 0xFF0F0F0F;
        private static final int DEVICE_OUTLINE = 0xFFFFBF00;  // amber
        private static final int LCD_COLOR      = 0xFF111111;
        private static final int LCD_TEXT       = 0xFFFFBF00;
        private static final int CUTTER_COLOR   = 0xFF444444;

        private enum State { PLAYING, HINT_FADING, CUT_OK, CUT_WRONG, TIMEOUT }
        private State mState = State.PLAYING;

        // Geometry
        private RectF mDeviceRect    = new RectF();
        private RectF mLcdRect       = new RectF();
        private float[] mWireY;          // y-centre of each wire
        private float mWireLeft, mWireRight;
        private float mCutterRestX, mCutterRestY;
        private float mCutterX, mCutterY;
        private float mCutterSize;
        private boolean mDraggingCutter = false;

        // Hint
        private final int mCorrectWire;
        private float mHintAlpha = 1f;

        // Timer
        private float mTimeElapsed = 0f;    // 0..1
        private int   mDisplaySecs = 5;

        // Shake state (failure)
        private float mShakeOffset = 0f;

        // Paints
        private final Paint mBgPaint         = new Paint();
        private final Paint mDevicePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDeviceBorderPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLcdPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLcdTextPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mWirePaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCutterPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHintPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHintLabelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mMsgPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);

        private ValueAnimator mTimerAnim;
        private ValueAnimator mHintFadeAnim;

        private final Runnable mOnSuccess;
        private final Runnable mOnFail;

        @SuppressLint("ClickableViewAccessibility")
        BombView(Context ctx, int correctWire, Runnable onSuccess, Runnable onFail) {
            super(ctx);
            mCorrectWire = correctWire & 3;
            mOnSuccess   = onSuccess;
            mOnFail      = onFail;
            setLayerType(LAYER_TYPE_HARDWARE, null);

            mBgPaint.setColor(BG_COLOR);
            mDevicePaint.setColor(0xFF1A1A1A);
            mDevicePaint.setStyle(Paint.Style.FILL);
            mDeviceBorderPaint.setColor(DEVICE_OUTLINE);
            mDeviceBorderPaint.setStyle(Paint.Style.STROKE);
            mLcdPaint.setColor(LCD_COLOR);
            mLcdPaint.setStyle(Paint.Style.FILL);
            mLcdTextPaint.setColor(LCD_TEXT);
            mLcdTextPaint.setTextAlign(Paint.Align.CENTER);
            mLcdTextPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            mLcdTextPaint.setAntiAlias(true);
            mWirePaint.setStyle(Paint.Style.STROKE);
            mWirePaint.setStrokeCap(Paint.Cap.ROUND);
            mCutterPaint.setColor(CUTTER_COLOR);
            mCutterPaint.setStyle(Paint.Style.FILL);
            mCutterPaint.setAntiAlias(true);
            mHintPaint.setColor(DEVICE_OUTLINE);
            mHintPaint.setTextAlign(Paint.Align.CENTER);
            mHintPaint.setAntiAlias(true);
            mHintLabelPaint.setColor(0x88FFBF00);
            mHintLabelPaint.setTextAlign(Paint.Align.CENTER);
            mHintLabelPaint.setAntiAlias(true);
            mHintLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
            mMsgPaint.setTextAlign(Paint.Align.CENTER);
            mMsgPaint.setAntiAlias(true);
            mMsgPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));

            setOnTouchListener((v, e) -> { handleTouch(e); return true; });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float cx = w / 2f;
            float cy = h / 2f;

            // Device panel
            float dw = w * 0.78f, dh = h * 0.55f;
            mDeviceRect.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2);

            // LCD panel inside device
            float lcdH = dh * 0.22f;
            mLcdRect.set(mDeviceRect.left + dw * 0.12f, mDeviceRect.top + dh * 0.08f,
                    mDeviceRect.right - dw * 0.12f, mDeviceRect.top + dh * 0.08f + lcdH);

            // Wire positions — evenly spaced in lower part of device
            mWireY = new float[4];
            float wireAreaTop = mLcdRect.bottom + dh * 0.08f;
            float wireSpacing = (mDeviceRect.bottom - wireAreaTop - dh * 0.06f) / 3f;
            for (int i = 0; i < 4; i++) {
                mWireY[i] = wireAreaTop + wireSpacing * i;
            }
            mWireLeft  = mDeviceRect.left  - w * 0.05f;
            mWireRight = mDeviceRect.right + w * 0.05f;

            // Cutter rests at bottom-right
            mCutterSize  = w * 0.085f;
            mCutterRestX = mDeviceRect.right - dw * 0.05f;
            mCutterRestY = mDeviceRect.bottom + h * 0.08f;
            mCutterX     = mCutterRestX;
            mCutterY     = mCutterRestY;

            mDeviceBorderPaint.setStrokeWidth(w * 0.006f);
            mWirePaint.setStrokeWidth(h * 0.018f);
            mLcdTextPaint.setTextSize(lcdH * 0.65f);
            mHintPaint.setTextSize(h * 0.030f);
            mHintLabelPaint.setTextSize(h * 0.020f);
            mMsgPaint.setTextSize(h * 0.030f);

            startTimerAnim();
            startHintFade();
        }

        private void startTimerAnim() {
            mTimerAnim = ValueAnimator.ofFloat(0f, 1f);
            mTimerAnim.setDuration(TIMER_MS);
            mTimerAnim.setInterpolator(new LinearInterpolator());
            mTimerAnim.addUpdateListener(a -> {
                mTimeElapsed = (float) a.getAnimatedValue();
                mDisplaySecs = Math.max(0, 5 - (int)(mTimeElapsed * 5));
                // Last 2s: faster tick haptic
                if (mDisplaySecs <= 2 && mState == State.PLAYING) {
                    // short buzz each second handled by checking display seconds change
                }
                invalidate();
            });
            mTimerAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (mState == State.PLAYING) {
                        triggerFail(false);
                    }
                }
            });
            mTimerAnim.start();
        }

        private void startHintFade() {
            // Hint fully visible for 1s then fades to 25%
            mHintFadeAnim = ValueAnimator.ofFloat(1f, 0.25f);
            mHintFadeAnim.setStartDelay(1000);
            mHintFadeAnim.setDuration(600);
            mHintFadeAnim.addUpdateListener(a -> {
                mHintAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            mHintFadeAnim.start();
        }

        private void handleTouch(MotionEvent event) {
            if (mState != State.PLAYING) return;
            float tx = event.getX(), ty = event.getY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float dx = tx - mCutterX, dy = ty - mCutterY;
                    if (Math.sqrt(dx*dx + dy*dy) < mCutterSize * 1.6f) {
                        mDraggingCutter = true;
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDraggingCutter) {
                        mCutterX = tx;
                        mCutterY = ty;
                        invalidate();
                        // Check intersection with any wire
                        for (int i = 0; i < 4; i++) {
                            if (ty >= mWireY[i] - mWirePaint.getStrokeWidth() * 1.5f
                                    && ty <= mWireY[i] + mWirePaint.getStrokeWidth() * 1.5f
                                    && tx >= mWireLeft && tx <= mWireRight) {
                                cutWire(i);
                                return;
                            }
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mDraggingCutter = false;
                    if (mState == State.PLAYING) {
                        // Snap back to rest
                        ValueAnimator snap = ValueAnimator.ofFloat(1f, 0f);
                        snap.setDuration(250);
                        final float startX = mCutterX, startY = mCutterY;
                        snap.addUpdateListener(a -> {
                            float t = (float) a.getAnimatedValue();
                            mCutterX = mCutterRestX + (startX - mCutterRestX) * t;
                            mCutterY = mCutterRestY + (startY - mCutterRestY) * t;
                            invalidate();
                        });
                        snap.start();
                    }
                    break;
            }
        }

        private void cutWire(int wireIndex) {
            mDraggingCutter = false;
            if (mTimerAnim != null) mTimerAnim.cancel();
            if (mHintFadeAnim != null) mHintFadeAnim.cancel();

            if (wireIndex == mCorrectWire) {
                mState = State.CUT_OK;
                vibrate(60);
                postDelayed(mOnSuccess, 800);
            } else {
                triggerFail(true);
            }
            invalidate();
        }

        private void triggerFail(boolean wrongWire) {
            mState = wrongWire ? State.CUT_WRONG : State.TIMEOUT;
            if (mTimerAnim != null) mTimerAnim.cancel();
            vibrate(200);
            animateShake();
        }

        private void animateShake() {
            ValueAnimator shake = ValueAnimator.ofFloat(0f, 1f);
            shake.setDuration(500);
            shake.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                mShakeOffset = (float)(Math.sin(t * Math.PI * 8) * 12f * (1f - t));
                invalidate();
            });
            shake.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    mShakeOffset = 0f;
                    postDelayed(mOnFail, 600);
                }
            });
            shake.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, h, mBgPaint);

            canvas.save();
            canvas.translate(mShakeOffset, 0f);

            // Device body
            canvas.drawRoundRect(mDeviceRect, 20f, 20f, mDevicePaint);
            canvas.drawRoundRect(mDeviceRect, 20f, 20f, mDeviceBorderPaint);

            // LCD panel
            canvas.drawRoundRect(mLcdRect, 6f, 6f, mLcdPaint);
            String timerText;
            if (mState == State.CUT_OK) {
                timerText = "OK";
            } else if (mState == State.CUT_WRONG || mState == State.TIMEOUT) {
                timerText = "BOOM";
            } else {
                timerText = String.format(Locale.US, "%d", mDisplaySecs);
            }
            mLcdTextPaint.setColor(mState == State.CUT_WRONG || mState == State.TIMEOUT
                    ? 0xFFDC143C : LCD_TEXT);
            canvas.drawText(timerText,
                    mLcdRect.centerX(), mLcdRect.centerY() + mLcdTextPaint.getTextSize() * 0.35f,
                    mLcdTextPaint);

            // Wires (only uncut ones for clean look)
            for (int i = 0; i < 4; i++) {
                if (mState == State.CUT_OK && i == mCorrectWire) continue;
                mWirePaint.setColor(WIRE_COLORS[i]);
                // Slight tremor
                float wobble = mState == State.PLAYING
                        ? (float)(Math.sin(System.currentTimeMillis() * 0.003 + i) * 2f) : 0f;
                canvas.drawLine(mWireLeft, mWireY[i] + wobble,
                        mWireRight, mWireY[i] + wobble, mWirePaint);
                // Small symbol on each wire (circle / square / triangle / diamond)
                drawWireSymbol(canvas, i, (mWireLeft + mWireRight) / 2f, mWireY[i] + wobble);
            }

            // Cutter
            drawCutter(canvas, mCutterX, mCutterY);

            // Hint
            float hinty = mDeviceRect.bottom + h * 0.040f;
            mHintLabelPaint.setAlpha((int)(mHintAlpha * 140));
            canvas.drawText("CUT THE " + WIRE_NAMES[mCorrectWire] + " WIRE",
                    w / 2f, hinty, mHintPaint);
            mHintPaint.setAlpha((int)(mHintAlpha * 255));

            // End state messages
            if (mState == State.CUT_WRONG || mState == State.TIMEOUT) {
                mMsgPaint.setColor(0xCCFFFFFF);
                canvas.drawText("Defused itself.", w / 2f, h * 0.88f, mMsgPaint);
            } else if (mState == State.CUT_OK) {
                mMsgPaint.setColor(DEVICE_OUTLINE);
                canvas.drawText("Wire cut.", w / 2f, h * 0.88f, mMsgPaint);
            } else {
                mHintLabelPaint.setAlpha((int)(mHintAlpha * 120));
                canvas.drawText("Drag cutter to correct wire", w / 2f, h * 0.88f, mHintLabelPaint);
            }

            canvas.restore();
        }

        private void drawWireSymbol(Canvas canvas, int wireIdx, float cx, float cy) {
            float r = mWirePaint.getStrokeWidth() * 0.6f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(BG_COLOR);
            p.setStyle(Paint.Style.FILL);
            switch (wireIdx) {
                case 0: canvas.drawCircle(cx, cy, r, p); break;  // circle
                case 1: canvas.drawRect(cx-r, cy-r, cx+r, cy+r, p); break; // square
                case 2: {  // triangle
                    Path tri = new Path();
                    tri.moveTo(cx, cy - r); tri.lineTo(cx + r, cy + r);
                    tri.lineTo(cx - r, cy + r); tri.close();
                    canvas.drawPath(tri, p); break;
                }
                case 3: { // diamond
                    Path dia = new Path();
                    dia.moveTo(cx, cy - r); dia.lineTo(cx + r, cy);
                    dia.lineTo(cx, cy + r); dia.lineTo(cx - r, cy);
                    dia.close();
                    canvas.drawPath(dia, p); break;
                }
            }
        }

        private void drawCutter(Canvas canvas, float cx, float cy) {
            canvas.save();
            canvas.translate(cx, cy);
            float s = mCutterSize;
            // Simplified cutter silhouette: two jaw triangles + handle bar
            mCutterPaint.setColor(CUTTER_COLOR);
            // Handle
            canvas.drawRect(-s * 0.1f, 0f, s * 0.1f, s * 0.7f, mCutterPaint);
            // Top jaw
            Path jaw1 = new Path();
            jaw1.moveTo(0f, 0f); jaw1.lineTo(-s * 0.45f, -s * 0.3f);
            jaw1.lineTo(s * 0.45f, -s * 0.3f); jaw1.close();
            canvas.drawPath(jaw1, mCutterPaint);
            canvas.restore();
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
