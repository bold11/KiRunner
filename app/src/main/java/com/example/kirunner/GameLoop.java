package com.example.kirunner;

import android.graphics.Canvas;

/** Anything GameThread can drive: CLASSIC (GameView) and ADVENTURE (AdventureView). */
public interface GameLoop {
    void update(float dt);

    void render(Canvas canvas);

    void reportFps(float fps);
}
