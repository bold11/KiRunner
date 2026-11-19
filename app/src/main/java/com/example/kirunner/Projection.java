package com.example.kirunner;

/**
 * The camera for the 3D view (plain Java, unit tested).
 *
 * The game logic always runs in flat 2D screen coordinates. This class only changes how a point
 * is DRAWN: rows higher up the screen are treated as "farther away", so lanes converge toward
 * the top and objects shrink with distance (perspective scale = depth / (depth + distance)).
 * Rows keep their y position, so obstacles still enter from the top of the screen and the
 * score line stays exactly at the top of the control bar.
 *
 * strength 0 = the normal flat view (exact identity), 1 = full 3D. Values in between animate the camera.
 */
public final class Projection {

    private float width = 1f;
    private float refY = 1f;      // the player's row: scale is exactly 1 here
    private float depth = 1f;     // perspective depth, derived from farScale
    private float strength;
    private float sway;           // camera follow: shifts the far end sideways

    /**
     * @param width    screen width in px
     * @param refY     the player's row (y), where nothing is scaled
     * @param farScale size of objects at the very top of the screen (y = 0) in full 3D, e.g. 0.45
     */
    public void configure(float width, float refY, float farScale) {
        this.width = width;
        this.refY = Math.max(1f, refY);
        float f = Math.max(0.05f, Math.min(0.99f, farScale));
        // Solve depth / (depth + refY) == f, so the top row has exactly scale f.
        this.depth = f * this.refY / (1f - f);
    }

    public void setStrength(float t) {
        strength = Math.max(0f, Math.min(1f, t));
    }

    public float getStrength() {
        return strength;
    }

    public void setSway(float px) {
        sway = px;
    }

    /** Size multiplier for something centered on row y (1 at the player's row). */
    public float scale(float y) {
        float distance = Math.max(refY - y, -0.8f * depth); // rows below the player get slightly bigger
        float perspective = depth / (depth + distance);
        return 1f + (perspective - 1f) * strength;
    }

    /** Screen x of a world x on row y: farther rows are pulled toward the center (plus camera sway). */
    public float x(float worldX, float y) {
        float s = scale(y);
        float cx = width / 2f;
        return cx + (worldX - cx) * s + sway * (1f - s);
    }
}
