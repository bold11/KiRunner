package com.example.kirunner;

/** Axis-aligned rectangle in pixels. Plain Java (instead of RectF) so collision rules can be unit tested. */
public final class Hitbox {

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public Hitbox(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public static Hitbox ofSize(float x, float y, float width, float height) {
        return new Hitbox(x, y, x + width, y + height);
    }

    /** Shrinks every side by a fraction of the size (0.15 = 15% off each side). */
    public Hitbox inset(float fraction) {
        float ix = width() * fraction;
        float iy = height() * fraction;
        return new Hitbox(left + ix, top + iy, right - ix, bottom - iy);
    }

    /** True when the rectangles overlap (touching edges do not count). */
    public boolean intersects(Hitbox other) {
        return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }
}
