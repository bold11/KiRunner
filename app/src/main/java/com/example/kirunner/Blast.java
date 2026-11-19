package com.example.kirunner;

/**
 * An energy blast fired by the player. It flies straight up its lane and destroys the first
 * enemy block it touches. Plain data; GameView moves it and draws it through the camera.
 */
public class Blast {

    private final float centerX;
    private final float width;
    private final float height;
    private final int color;
    private float y;          // top edge
    private float age;

    public Blast(int lane, float laneWidth, float startTop, float width, float height, int color) {
        this.centerX = lane * laneWidth + laneWidth / 2f;
        this.width = width;
        this.height = height;
        this.color = color;
        this.y = startTop;
    }

    /** Moves up by speed (px per second) times dt. */
    public void update(float speed, float dt) {
        y -= speed * dt;
        age += dt;
    }

    public boolean isOffScreen() {
        return y + height < 0;
    }

    public Hitbox getBox() {
        return Hitbox.ofSize(centerX - width / 2f, y, width, height);
    }

    public float getCenterX() {
        return centerX;
    }

    public float getCenterY() {
        return y + height / 2f;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public int getColor() {
        return color;
    }

    public float getAge() {
        return age;
    }
}
