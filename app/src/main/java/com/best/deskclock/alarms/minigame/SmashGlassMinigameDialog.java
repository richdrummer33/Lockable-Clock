// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms.minigame;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Confirmation minigame — Tier 1 (lowest friction).
 *
 * <p>Mechanic: press-and-hold the hammer to wind up (charge meter fills over 350 ms), then
 * release to strike. A hit above the 60 % threshold shatters the glass panel and fires the
 * success callback; below threshold the hammer bounces and a hairline crack appears (up to three
 * cracks before the glass resets).
 *
 * <p>Use case: disabling / toggling off a locked alarm.
 */
public final class SmashGlassMinigameDialog extends DialogFragment {

    private static final String TAG = "smash_glass_minigame";
    private static final String ARG_KEY = "callback_key";

    /** Static registry so the callback survives fragment re-creation. */
    private static final Map<String, Runnable> sPending = new HashMap<>();

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static void show(FragmentManager fm, Runnable onSuccess) {
        String key = UUID.randomUUID().toString();
        sPending.put(key, onSuccess);
        Bundle args = new Bundle();
        args.putString(ARG_KEY, key);
        SmashGlassMinigameDialog d = new SmashGlassMinigameDialog();
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
        SmashGlassView view = new SmashGlassView(requireContext(), () -> {
            Runnable cb = sPending.remove(key);
            if (cb != null) cb.run();
            dismissAllowingStateLoss();
        });

        Dialog dialog = new Dialog(requireContext(),
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        // Tap outside to cancel (no confirmation = alarm stays alive)
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    // -------------------------------------------------------------------------
    // Custom game view
    // -------------------------------------------------------------------------

    @SuppressLint("ViewConstructor")
    static final class SmashGlassView extends View {

        private static final int BG_COLOR         = 0xFF0F0F0F;
        private static final int GLASS_COLOR_TOP  = 0xBBDDEEFF;
        private static final int GLASS_COLOR_BOT  = 0x88AABBCC;
        private static final int HAMMER_BODY      = 0xFF2A2A2A;
        private static final int HAMMER_HEAD      = 0xFFB8860B;  // brass
        private static final int CHARGE_COLOR     = 0xFFDC143C;  // crimson

        /** Charge threshold above which the glass shatters. */
        private static final float SHATTER_THRESHOLD = 0.60f;
        private static final long  CHARGE_DURATION   = 350L;     // ms to full charge

        // State machine
        private enum State { IDLE, CHARGING, STRIKING, SHATTERED, FAILED }
        private State mState = State.IDLE;

        private float mCharge = 0f;        // 0..1
        private int   mCrackCount = 0;     // 0..3

        // Animated values
        private float mHammerAngle = 0f;   // degrees, 0=resting, -90=raised, +30=recoil
        private float mShardAlpha  = 1f;

        // Geometry (computed in onSizeChanged)
        private final RectF mGlassRect  = new RectF();
        private final RectF mHammerHead = new RectF();
        private float mHammerPivotX, mHammerPivotY;
        private float mHammerLength;
        private float mHammerTouchRadius;

        // Shards
        private final List<Shard> mShards = new ArrayList<>();
        private final Random mRandom = new Random();

        // Animators
        private ValueAnimator mChargeAnimator;
        private ValueAnimator mStrikeAnimator;
        private ValueAnimator mShardAnimator;
        private ValueAnimator mRecoilAnimator;

        // Paints
        private final Paint mBgPaint      = new Paint();
        private final Paint mGlassPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHammerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHeadPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mChargePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCrackPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShardPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Hint text
        private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable mOnSuccess;

        @SuppressLint("ClickableViewAccessibility")
        SmashGlassView(Context ctx, Runnable onSuccess) {
            super(ctx);
            mOnSuccess = onSuccess;
            setLayerType(LAYER_TYPE_HARDWARE, null);

            mBgPaint.setColor(BG_COLOR);
            mGlassPaint.setStyle(Paint.Style.FILL);
            mTextPaint.setColor(0x55FFFFFF);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            mHammerPaint.setColor(HAMMER_BODY);
            mHammerPaint.setStyle(Paint.Style.FILL);
            mHeadPaint.setColor(HAMMER_HEAD);
            mHeadPaint.setStyle(Paint.Style.FILL);
            mChargePaint.setColor(CHARGE_COLOR);
            mChargePaint.setStyle(Paint.Style.STROKE);
            mChargePaint.setStrokeCap(Paint.Cap.ROUND);
            mCrackPaint.setColor(0xCCFFFFFF);
            mCrackPaint.setStyle(Paint.Style.STROKE);
            mCrackPaint.setStrokeCap(Paint.Cap.ROUND);
            mShardPaint.setStyle(Paint.Style.FILL);
            mHintPaint.setColor(0x55FFFFFF);
            mHintPaint.setTextAlign(Paint.Align.CENTER);
            mHintPaint.setTypeface(Typeface.DEFAULT);

            setOnTouchListener((v, event) -> {
                handleTouch(event);
                return true;
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float cx = w / 2f;
            float cy = h / 2f;

            // Glass panel — centered slightly above middle
            float gw = w * 0.55f;
            float gh = h * 0.30f;
            mGlassRect.set(cx - gw / 2, cy - gh - h * 0.05f,
                    cx + gw / 2, cy - h * 0.05f);

            // Gradient shader for glass
            mGlassPaint.setShader(new LinearGradient(
                    mGlassRect.left, mGlassRect.top,
                    mGlassRect.right, mGlassRect.bottom,
                    GLASS_COLOR_TOP, GLASS_COLOR_BOT,
                    Shader.TileMode.CLAMP));

            mTextPaint.setTextSize(h * 0.035f);
            mHintPaint.setTextSize(h * 0.025f);

            // Hammer geometry
            mHammerLength = h * 0.25f;
            float headW = w * 0.12f;
            float headH = h * 0.06f;
            mHammerPivotX = cx;
            mHammerPivotY = cy + h * 0.25f;   // pivot = bottom of handle
            mHammerHead.set(-headW / 2, -mHammerLength - headH,
                    headW / 2, -mHammerLength);
            mHammerTouchRadius = w * 0.15f;

            mChargePaint.setStrokeWidth(h * 0.012f);
            mCrackPaint.setStrokeWidth(h * 0.004f);
        }

        private void handleTouch(MotionEvent event) {
            if (mState == State.SHATTERED) return;
            float dx = event.getX() - mHammerPivotX;
            float dy = event.getY() - mHammerPivotY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mState == State.IDLE && dist < mHammerTouchRadius) {
                        startCharging();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mState == State.CHARGING) {
                        releaseHammer();
                    }
                    break;
            }
        }

        private void startCharging() {
            mState = State.CHARGING;
            mCharge = 0f;
            mChargeAnimator = ValueAnimator.ofFloat(0f, 1f);
            mChargeAnimator.setDuration(CHARGE_DURATION * 2); // allow over-charge
            mChargeAnimator.addUpdateListener(a -> {
                mCharge = (float) a.getAnimatedValue();
                // Raise hammer as charge fills
                mHammerAngle = -90f * Math.min(mCharge, 1f);
                invalidate();
            });
            mChargeAnimator.start();

            // Raise hammer animation (already driven by charge animator above)
        }

        private void releaseHammer() {
            if (mChargeAnimator != null) mChargeAnimator.cancel();
            mState = State.STRIKING;
            float capturedCharge = Math.min(mCharge, 1f);

            // Animate hammer swinging down
            mStrikeAnimator = ValueAnimator.ofFloat(mHammerAngle, 20f);
            mStrikeAnimator.setDuration(120);
            mStrikeAnimator.addUpdateListener(a -> {
                mHammerAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            mStrikeAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    onImpact(capturedCharge);
                }
            });
            mStrikeAnimator.start();
        }

        private void onImpact(float charge) {
            vibrate(20);
            if (charge >= SHATTER_THRESHOLD) {
                mState = State.SHATTERED;
                buildShards();
                animateShards();
            } else {
                // Fail — add a crack and rebound
                mCrackCount = Math.min(mCrackCount + 1, 3);
                mState = mCrackCount >= 3 ? State.IDLE : State.FAILED;
                mCrackCount = mCrackCount >= 3 ? 0 : mCrackCount;
                rebound();
            }
        }

        private void rebound() {
            mRecoilAnimator = ValueAnimator.ofFloat(20f, 0f);
            mRecoilAnimator.setDuration(200);
            mRecoilAnimator.addUpdateListener(a -> {
                mHammerAngle = (float) a.getAnimatedValue();
                invalidate();
            });
            mRecoilAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    mState = State.IDLE;
                    invalidate();
                }
            });
            mRecoilAnimator.start();
        }

        private void buildShards() {
            mShards.clear();
            float cx = mGlassRect.centerX();
            float cy = mGlassRect.centerY();
            for (int i = 0; i < 26; i++) {
                Shard s = new Shard();
                float angle = mRandom.nextFloat() * 360f;
                float speed = 4f + mRandom.nextFloat() * 10f;
                s.vx = (float) (Math.cos(Math.toRadians(angle)) * speed);
                s.vy = (float) (Math.sin(Math.toRadians(angle)) * speed);
                s.x  = cx + (mRandom.nextFloat() - 0.5f) * mGlassRect.width() * 0.6f;
                s.y  = cy + (mRandom.nextFloat() - 0.5f) * mGlassRect.height() * 0.6f;
                s.size = 10f + mRandom.nextFloat() * 24f;
                s.rotation = mRandom.nextFloat() * 360f;
                s.rotSpeed = (mRandom.nextFloat() - 0.5f) * 8f;
                // Random ice-blue/white tint
                int alpha = 0xBB + mRandom.nextInt(0x44);
                s.color = Color.argb(alpha, 180 + mRandom.nextInt(70),
                        200 + mRandom.nextInt(50), 220 + mRandom.nextInt(35));
                mShards.add(s);
            }
        }

        private void animateShards() {
            vibrate(40);
            mShardAlpha = 1f;
            mShardAnimator = ValueAnimator.ofFloat(0f, 1f);
            mShardAnimator.setDuration(800);
            mShardAnimator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                for (Shard s : mShards) {
                    s.x += s.vx;
                    s.y += s.vy + t * 3f; // add gravity
                    s.rotation += s.rotSpeed;
                    s.vy += 0.4f; // gravity
                }
                mShardAlpha = 1f - t;
                invalidate();
            });
            mShardAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    mOnSuccess.run();
                }
            });
            mShardAnimator.start();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, h, mBgPaint);

            if (mState != State.SHATTERED || mShardAlpha > 0.05f) {
                // Glass panel
                if (mState != State.SHATTERED) {
                    canvas.drawRoundRect(mGlassRect, 12f, 12f, mGlassPaint);
                    // "EMERGENCY" etching
                    canvas.drawText("EMERGENCY", mGlassRect.centerX(),
                            mGlassRect.centerY() + mTextPaint.getTextSize() / 3f, mTextPaint);
                    // Cracks
                    drawCracks(canvas);
                }
                // Shards (during shatter animation)
                drawShards(canvas);
            }

            // Hammer (not shown during shard flight)
            if (mState != State.SHATTERED) {
                drawHammer(canvas);
            }

            // Charge meter arc (only while charging)
            if (mState == State.CHARGING) {
                float arcRadius = mHammerTouchRadius * 1.3f;
                RectF arcRect = new RectF(
                        mHammerPivotX - arcRadius, mHammerPivotY - arcRadius,
                        mHammerPivotX + arcRadius, mHammerPivotY + arcRadius);
                float sweep = 360f * Math.min(mCharge, 1f);
                canvas.drawArc(arcRect, -90f, sweep, false, mChargePaint);
            }

            // Hint text (always shown)
            if (mState == State.IDLE) {
                canvas.drawText("Hold to charge · release to strike",
                        w / 2f, h * 0.92f, mHintPaint);
            }
        }

        private void drawHammer(Canvas canvas) {
            canvas.save();
            canvas.translate(mHammerPivotX, mHammerPivotY);
            canvas.rotate(mHammerAngle);

            // Handle
            canvas.drawRect(-6f, -mHammerLength, 6f, 0f, mHammerPaint);

            // Head
            canvas.drawRoundRect(mHammerHead, 6f, 6f, mHeadPaint);

            canvas.restore();
        }

        private void drawCracks(Canvas canvas) {
            if (mCrackCount <= 0) return;
            float cx = mGlassRect.centerX();
            float cy = mGlassRect.centerY();
            float len = mGlassRect.width() * 0.35f;
            // Each crack is a random-ish line from the impact point
            long seed = 42L;
            Random r = new Random(seed);
            for (int i = 0; i < mCrackCount; i++) {
                float angle = -60f + i * 45f;
                float endX = cx + (float) Math.cos(Math.toRadians(angle)) * len;
                float endY = cy + (float) Math.sin(Math.toRadians(angle)) * len;
                canvas.drawLine(cx, cy, endX, endY, mCrackPaint);
                // Sub-crack
                float sub = len * 0.5f;
                float subAngle = angle + 25f;
                canvas.drawLine(
                        cx + (float) Math.cos(Math.toRadians(angle)) * sub,
                        cy + (float) Math.sin(Math.toRadians(angle)) * sub,
                        cx + (float) Math.cos(Math.toRadians(subAngle)) * (sub + sub * 0.4f),
                        cy + (float) Math.sin(Math.toRadians(subAngle)) * (sub + sub * 0.4f),
                        mCrackPaint);
            }
        }

        private void drawShards(Canvas canvas) {
            if (mShards.isEmpty()) return;
            mShardPaint.setAlpha((int) (mShardAlpha * 255));
            for (Shard s : mShards) {
                canvas.save();
                canvas.translate(s.x, s.y);
                canvas.rotate(s.rotation);
                mShardPaint.setColor(s.color);
                mShardPaint.setAlpha((int) (mShardAlpha * Color.alpha(s.color)));
                float h2 = s.size / 2f;
                Path p = new Path();
                p.moveTo(0f, -h2);
                p.lineTo(h2 * 0.6f, h2 * 0.4f);
                p.lineTo(-h2 * 0.5f, h2 * 0.5f);
                p.close();
                canvas.drawPath(p, mShardPaint);
                canvas.restore();
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

        // -----------------------------------------------------------------------
        // Data
        // -----------------------------------------------------------------------

        private static final class Shard {
            float x, y, vx, vy, size, rotation, rotSpeed;
            int color;
        }
    }
}
