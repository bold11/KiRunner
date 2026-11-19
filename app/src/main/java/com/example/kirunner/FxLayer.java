package com.example.kirunner;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.Random;

/**
 * Visual effects only (no gameplay rules): speed lines, particles, shockwave ring,
 * screen shake and flash. Used for crashes and transformations.
 */
public class FxLayer {

    private static final int SPEED_LINES = 26;
    private static final int MAX_PARTICLES = 140;
    private static final float SHAKE_DURATION = 0.35f;
    private static final float FLASH_DURATION = 0.30f;
    private static final float RING_DURATION = 0.50f;
    private static final int CRASH_PINK = Color.rgb(255, 63, 174);
    private static final int[] CRASH_COLORS = {
            Color.rgb(255, 63, 174), Color.rgb(255, 170, 225), Color.WHITE,
            Color.rgb(44, 229, 216), Color.rgb(255, 194, 60)
    };

    private final Random rnd = new Random();
    private final int w, h;

    // Speed lines
    private final float[] lineX = new float[SPEED_LINES];
    private final float[] lineY = new float[SPEED_LINES];
    private final float[] lineLen = new float[SPEED_LINES];
    private final float[] lineFactor = new float[SPEED_LINES];
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Particles (plain arrays = no garbage collection hiccups mid-game)
    private final float[] pX = new float[MAX_PARTICLES], pY = new float[MAX_PARTICLES];
    private final float[] pVX = new float[MAX_PARTICLES], pVY = new float[MAX_PARTICLES];
    private final float[] pLife = new float[MAX_PARTICLES], pMax = new float[MAX_PARTICLES];
    private final float[] pSize = new float[MAX_PARTICLES];
    private final int[] pColor = new int[MAX_PARTICLES];
    private int particleCount;
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Shockwave, shake, flash
    private float ringX, ringY, ringTime;
    private int ringColor = CRASH_PINK;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shakeTime, shakeScale = 1f;
    private final float shakeMagnitude;
    private float flashTime;
    private int flashStrength = 200;
    private final Paint flashPaint = new Paint();

    public FxLayer(int width, int height) {
        w = width;
        h = height;
        shakeMagnitude = width * 0.025f;

        linePaint.setStrokeWidth(Math.max(2f, width * 0.0025f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        setTint(Color.WHITE);

        ringPaint.setStyle(Paint.Style.STROKE);
        flashPaint.setColor(Color.WHITE);

        for (int i = 0; i < SPEED_LINES; i++) respawnLine(i, true);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Tints the speed lines toward the current form's color. */
    public void setTint(int color) {
        linePaint.setColor(Color.argb(48, (Color.red(color) + 255) / 2,
                (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2));
    }

    private void respawnLine(int i, boolean anywhere) {
        lineX[i] = rnd.nextFloat() * w;
        lineLen[i] = h * (0.04f + rnd.nextFloat() * 0.08f);
        lineY[i] = anywhere ? rnd.nextFloat() * h : -lineLen[i] - rnd.nextFloat() * h * 0.3f;
        lineFactor[i] = 1.2f + rnd.nextFloat() * 1.3f;
    }

    public void reset() {
        particleCount = 0;
        ringTime = 0;
        shakeTime = 0;
        flashTime = 0;
    }

    public void update(float dt, float speed) {
        for (int i = 0; i < SPEED_LINES; i++) {
            lineY[i] += speed * lineFactor[i] * dt;
            if (lineY[i] > h) respawnLine(i, false);
        }

        for (int i = 0; i < particleCount; ) {
            pLife[i] -= dt;
            if (pLife[i] <= 0) {
                removeParticle(i);
                continue;
            }
            pVX[i] *= 0.96f;
            pVY[i] = pVY[i] * 0.96f + h * 0.6f * dt; // drag + light gravity
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            i++;
        }

        if (ringTime > 0) ringTime -= dt;
        if (shakeTime > 0) shakeTime -= dt;
        if (flashTime > 0) flashTime -= dt;
    }

    private void removeParticle(int i) {
        int last = --particleCount;
        pX[i] = pX[last]; pY[i] = pY[last];
        pVX[i] = pVX[last]; pVY[i] = pVY[last];
        pLife[i] = pLife[last]; pMax[i] = pMax[last];
        pSize[i] = pSize[last]; pColor[i] = pColor[last];
    }

    private void burst(float cx, float cy, int count, float speedScale, int[] colors) {
        for (int n = 0; n < count && particleCount < MAX_PARTICLES; n++) {
            int i = particleCount++;
            double angle = rnd.nextFloat() * Math.PI * 2;
            float speed = w * (0.25f + rnd.nextFloat() * 0.9f) * speedScale;
            pX[i] = cx;
            pY[i] = cy;
            pVX[i] = (float) Math.cos(angle) * speed;
            pVY[i] = (float) Math.sin(angle) * speed;
            pMax[i] = pLife[i] = 0.45f + rnd.nextFloat() * 0.6f;
            pSize[i] = w * (0.006f + rnd.nextFloat() * 0.012f);
            pColor[i] = colors[rnd.nextInt(colors.length)];
        }
    }

    /** Collision: particles, pink shockwave, strong shake and flash. */
    public void explode(float cx, float cy) {
        burst(cx, cy, 70, 1f, CRASH_COLORS);
        ring(cx, cy, CRASH_PINK);
        shakeTime = SHAKE_DURATION;
        shakeScale = 1f;
        flashTime = FLASH_DURATION;
        flashStrength = 200;
    }

    /** Transformation: particles and shockwave in the new form's color, light shake and flash. */
    public void powerUp(float cx, float cy, int color) {
        burst(cx, cy, 55, 0.8f, new int[]{color, color, Color.WHITE});
        ring(cx, cy, color);
        shakeTime = SHAKE_DURATION;
        shakeScale = 0.4f;
        flashTime = FLASH_DURATION;
        flashStrength = 60; // lighter than a crash so you can still see the road
    }

    /** An energy blast destroys an enemy: a small pop in the blast's color, a ring and a light shake. */
    public void hit(float cx, float cy, int color) {
        burst(cx, cy, 30, 0.6f, new int[]{color, CRASH_PINK, Color.WHITE});
        ring(cx, cy, color);
        shakeTime = SHAKE_DURATION * 0.6f;
        shakeScale = 0.25f;
    }

    private void ring(float cx, float cy, int color) {
        ringX = cx;
        ringY = cy;
        ringColor = color;
        ringTime = RING_DURATION;
    }

    public void applyShake(Canvas canvas) {
        if (shakeTime <= 0) return;
        float m = shakeMagnitude * shakeScale * (shakeTime / SHAKE_DURATION);
        canvas.translate((rnd.nextFloat() * 2 - 1) * m, (rnd.nextFloat() * 2 - 1) * m);
    }

    /** Speed lines live in world space; in the 3D view they stream along the converging road. */
    public void drawSpeedLines(Canvas canvas, Projection camera) {
        for (int i = 0; i < SPEED_LINES; i++) {
            float y0 = lineY[i];
            float y1 = lineY[i] + lineLen[i];
            canvas.drawLine(camera.x(lineX[i], y0), y0, camera.x(lineX[i], y1), y1, linePaint);
        }
    }

    public void drawParticles(Canvas canvas) {
        for (int i = 0; i < particleCount; i++) {
            float a = pLife[i] / pMax[i];
            particlePaint.setColor(pColor[i]);
            particlePaint.setAlpha((int) (255 * a));
            canvas.drawCircle(pX[i], pY[i], pSize[i] * (0.5f + 0.5f * a), particlePaint);
        }
        if (ringTime > 0) {
            float p = 1f - ringTime / RING_DURATION;
            ringPaint.setColor(ringColor);
            ringPaint.setAlpha((int) (220 * (1f - p)));
            ringPaint.setStrokeWidth(w * 0.02f * (1f - p) + 2f);
            canvas.drawCircle(ringX, ringY, w * 0.08f + p * w * 0.45f, ringPaint);
        }
    }

    public void drawFlash(Canvas canvas) {
        if (flashTime <= 0) return;
        flashPaint.setAlpha((int) (flashStrength * (flashTime / FLASH_DURATION)));
        canvas.drawRect(0, 0, w, h, flashPaint);
    }
}
