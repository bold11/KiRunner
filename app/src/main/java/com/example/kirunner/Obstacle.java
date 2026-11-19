package com.example.kirunner;

import android.graphics.Bitmap;

/**
 * STAGE 1: A square obstacle (ki block) that falls down one lane.
 * Holds its image and position; GameView draws it (through the 2D/3D camera).
 */
public class Obstacle {

    private final Bitmap bitmap;
    private final float size;
    private final float x;
    private float y;
    private boolean grazed;          // touched the player's image but not the hitbox
    private boolean closeCallCounted;

    public Obstacle(Bitmap bitmap, int lane, float laneWidth) {
        this.bitmap = bitmap;
        this.size = bitmap.getWidth();          // square: width == height
        this.x = lane * laneWidth + (laneWidth - size) / 2f;
        this.y = -size;                          // start just above the screen
    }

    /** Moves down by speed (px per second) times dt (seconds since last frame). */
    public void update(float speed, float dt) {
        y += speed * dt;
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    /** Top edge of the obstacle's rectangle. Used for scoring, release and spacing. */
    public float getTop() {
        return y;
    }

    public float getSize() {
        return size;
    }

    public float getCenterX() {
        return x + size / 2f;
    }

    public float getCenterY() {
        return y + size / 2f;
    }

    public Hitbox getImageBox() {
        return Hitbox.ofSize(x, y, size, size);
    }

    public void markGrazed() {
        grazed = true;
    }

    public boolean isGrazed() {
        return grazed;
    }

    /** Returns true only the first time, so each close call is counted once. */
    public boolean claimCloseCall() {
        if (!grazed || closeCallCounted) return false;
        closeCallCounted = true;
        return true;
    }
}
