package com.example.kirunner;

import android.graphics.Canvas;
import android.view.SurfaceHolder;

/**
 * STAGE 2: The master game loop.
 * About 60 times per second: update the game state, draw a frame, then sleep for the rest of the frame.
 * Also measures the real frame rate (shown in the dev overlay).
 */
public class GameThread extends Thread {

    private static final long TARGET_FRAME_NS = 1_000_000_000L / 60; // 60 FPS
    private static final float MAX_DT = 0.05f; // cap so a lag spike doesn't teleport obstacles

    private final SurfaceHolder surfaceHolder;
    private final GameLoop gameView;
    private volatile boolean running;

    public GameThread(SurfaceHolder surfaceHolder, GameLoop gameView) {
        super("GameThread");
        this.surfaceHolder = surfaceHolder;
        this.gameView = gameView;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        long fpsWindowStart = lastTime;
        int frames = 0;

        while (running) {
            long frameStart = System.nanoTime();
            float dt = Math.min((frameStart - lastTime) / 1_000_000_000f, MAX_DT);
            lastTime = frameStart;

            Canvas canvas = null;
            try {
                canvas = surfaceHolder.lockCanvas();
                if (canvas != null) {
                    gameView.update(dt);
                    gameView.render(canvas);
                }
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    } catch (IllegalStateException ignored) {
                        // Surface was destroyed mid-frame; the loop will stop next iteration.
                    }
                }
            }

            frames++;
            long now = System.nanoTime();
            if (now - fpsWindowStart >= 1_000_000_000L) {
                gameView.reportFps(frames * 1_000_000_000f / (now - fpsWindowStart));
                frames = 0;
                fpsWindowStart = now;
            }

            long sleepMs = (TARGET_FRAME_NS - (System.nanoTime() - frameStart)) / 1_000_000L;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
