package com.example.kirunner;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * STAGE 1: The player sprite.
 * Stores its lane and transformation, slides smoothly between lanes, leans while moving,
 * bobs on the cloud, and pulses an aura (with a big burst when it transforms).
 */
public class Player {

    private static final float LANE_SNAP = 18f;   // higher = faster slide between lanes
    private static final float MAX_TILT = 14f;    // degrees of lean while sliding
    private static final float BURST_TIME = 0.6f;

    private final Bitmap[] forms;                  // one sprite per transformation, all the same size
    private final Paint[] auraPaints;
    private final float width;
    private final float height;

    private int lane = GameRules.START_LANE;
    private int form;
    private float x, y, targetX;
    private float tilt;
    private float pulse = 1f;
    private float burst;
    private float jumpT, jumpCooldown;
    private boolean positioned;

    public Player(Bitmap[] forms, int[] auraColors) {
        this.forms = forms;
        this.width = forms[0].getWidth();
        this.height = forms[0].getHeight();
        auraPaints = new Paint[forms.length];
        for (int i = 0; i < forms.length; i++) {
            int c = auraColors[Math.min(i, auraColors.length - 1)];
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(0, 0, width * 0.62f,
                    new int[]{FxLayer.withAlpha(c, 110), FxLayer.withAlpha(c, 40), FxLayer.withAlpha(c, 0)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            auraPaints[i] = p;
        }
    }

    /** Moves one lane left (never past the outer lane). Returns true if the lane changed. */
    public boolean moveLeft() {
        int before = lane;
        lane = GameRules.clampLane(lane - 1);
        return lane != before;
    }

    /** Moves one lane right (never past the outer lane). Returns true if the lane changed. */
    public boolean moveRight() {
        int before = lane;
        lane = GameRules.clampLane(lane + 1);
        return lane != before;
    }

    /** Starts a jump (hop over blocks). Returns false while already jumping or just landed. */
    public boolean jump() {
        if (jumpT > 0 || jumpCooldown > 0) return false;
        jumpT = GameRules.JUMP_TIME;
        return true;
    }

    public boolean isAirborne() {
        return jumpT > 0;
    }

    /** 0 on the ground, 1 at the top of the jump. */
    public float jumpLift() {
        if (jumpT <= 0) return 0f;
        float progress = 1f - jumpT / GameRules.JUMP_TIME;
        return (float) Math.sin(progress * Math.PI);
    }

    public void setForm(int newForm) {
        form = Math.max(0, Math.min(forms.length - 1, newForm));
        burst = BURST_TIME;
    }

    public int getForm() {
        return form;
    }

    /** The lane the player is in (or sliding toward). Blasts fly up this lane. */
    public int getLane() {
        return lane;
    }

    public void reset() {
        lane = GameRules.START_LANE;
        form = 0;
        burst = 0;
        jumpT = 0;
        jumpCooldown = 0;
        positioned = false; // snap straight to the center lane on the next update
    }

    /** Slides toward the current lane and sits just above the control bar. */
    public void update(float dt, float laneWidth, float controlBarTop, float time) {
        targetX = lane * laneWidth + (laneWidth - width) / 2f;
        if (!positioned) {
            x = targetX;
            positioned = true;
        }
        x += (targetX - x) * Math.min(1f, dt * LANE_SNAP);

        float bob = (float) Math.sin(time * 3.2) * height * 0.035f;
        y = controlBarTop - height - height * 0.22f + bob;

        float lean = (targetX - x) / laneWidth;
        tilt = Math.max(-1f, Math.min(1f, lean)) * MAX_TILT;

        if (jumpT > 0) {
            jumpT -= dt;
            if (jumpT <= 0) {
                jumpT = 0;
                jumpCooldown = GameRules.JUMP_COOLDOWN;
            }
        } else if (jumpCooldown > 0) {
            jumpCooldown -= dt;
        }
        if (burst > 0) burst = Math.max(0f, burst - dt);
        pulse = 1f + 0.06f * (float) Math.sin(time * 7.0) + 0.7f * (burst / BURST_TIME);
    }

    public void draw(Canvas canvas, Paint bitmapPaint) {
        float lift = jumpLift();
        canvas.save();
        canvas.translate(getCenterX(), getCenterY() - lift * height * 0.75f);
        canvas.scale(1f + 0.18f * lift, 1f + 0.18f * lift);   // closer to the camera at the top of the jump

        canvas.save();
        canvas.scale(pulse, pulse * 0.75f);
        canvas.drawCircle(0, 0, width * 0.62f, auraPaints[form]);
        canvas.restore();

        canvas.rotate(tilt);
        canvas.drawBitmap(forms[form], -width / 2f, -height / 2f, bitmapPaint);
        canvas.restore();
    }

    /** The full image rectangle. GameRules shrinks it into the forgiving hitbox. */
    public Hitbox getImageBox() {
        return Hitbox.ofSize(x, y, width, height);
    }

    public float getCenterX() {
        return x + width / 2f;
    }

    public float getCenterY() {
        return y + height / 2f;
    }

    public float getHeight() {
        return height;
    }

    public float getWidth() {
        return width;
    }
}
