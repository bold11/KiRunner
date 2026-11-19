package com.example.kirunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Proves every stage can be finished: a planning bot plays each level with the real physics.
 * Whenever it stands on the ground it tries a set of moves (walk, or jump with different hold times,
 * double-jump timings and air control) in a throwaway copy of the level, then performs the move that
 * lands safely the furthest to the right. It fires the whole time to clear monsters.
 */
public class AdvLevelBotTest {

    private static final float DT = 1f / 60f;
    private static final float VIEW_W = 480;

    /** One candidate move: jump hold frames (0 = just walk), double jump frame (-1 = none), release right at frame. */
    private static final class Move {
        final int hold, doubleAt, releaseAt;

        Move(int hold, int doubleAt, int releaseAt) {
            this.hold = hold;
            this.doubleAt = doubleAt;
            this.releaseAt = releaseAt;
        }
    }

    private static void input(AdvInput.Frame in, Move m, int f) {
        in.right = m.releaseAt < 0 || f < m.releaseAt;
        in.left = false;
        in.jumpPressed = (m.hold > 0 && f == 0) || f == m.doubleAt;
        in.jumpHeld = (m.hold > 0 && f < m.hold) || (m.doubleAt >= 0 && f >= m.doubleAt && f < m.doubleAt + 18);
        in.fireHeld = true;
        in.firePressed = false;
    }

    /** Simulates a move from the hero's current spot in a monster-free copy; returns landing x or -1 if bad. */
    private static float tryMove(int stage, AdvGame real, Move m) {
        AdvGame sim = new AdvGame(Difficulty.EASY, VIEW_W);
        sim.debugStartStage(stage);
        sim.monsters.clear();
        for (int r = 0; r < sim.map.rows; r++) {
            for (int c = 0; c < sim.map.cols; c++) sim.map.set(c, r, real.map.get(c, r));   // same broken bricks
        }
        sim.hero.x = real.hero.x;
        sim.hero.y = real.hero.y;
        sim.hero.vx = real.hero.vx;
        sim.hero.vy = 0;
        sim.hero.onGround = true;
        int hp = sim.hero.hp;
        AdvInput.Frame in = new AdvInput.Frame();
        int maxFrames = m.hold == 0 ? 20 : 150;
        for (int f = 0; f < maxFrames; f++) {
            input(in, m, f);
            sim.update(DT, in);
            if (sim.hero.hp < hp) return -1;                  // hit spikes or fell in a pit
            if (sim.state == AdvGame.State.BOSS_INTRO) return 1e6f;
            if (f > 3 && sim.hero.onGround) return sim.hero.x;
        }
        return sim.hero.onGround ? sim.hero.x : -1;
    }

    private static boolean playStage(int stage) {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        g.debugStartStage(stage);
        AdvInput.Frame in = new AdvInput.Frame();
        java.util.List<Move> moves = new java.util.ArrayList<>();
        moves.add(new Move(0, -1, -1));
        for (int hold : new int[]{4, 10, 18, 40}) {
            for (int dbl : new int[]{-1, 12, 20, 28, 36}) {
                for (int rel : new int[]{-1, 14, 26}) moves.add(new Move(hold, dbl, rel));
            }
        }
        int frames = 0;
        while (frames < 60 * 240) {
            if (g.state == AdvGame.State.BOSS_INTRO) return true;
            if (g.state == AdvGame.State.GAME_OVER) return false;
            Move best = null;
            float bestX = g.hero.x + 2;
            for (Move m : moves) {
                float x = tryMove(stage, g, m);
                if (x > bestX + (m.hold == 0 ? 0 : 6)) {   // prefer walking unless a jump gets clearly further
                    bestX = x;
                    best = m;
                }
            }
            if (best == null) best = new Move(18, 28, -1);   // stuck: big jump and hope
            int f = 0;
            do {
                input(in, best, f);
                g.update(DT, in);
                frames++;
                f++;
            } while (f < 150 && !(f > 3 && g.hero.onGround) && g.state == AdvGame.State.PLAY);
        }
        return false;
    }

    @Test
    public void everyStageCanBeFinished() {
        for (int stage = 0; stage < AdvLevels.STAGES.length; stage++) {
            assertTrue("bot could not finish stage " + (stage + 1), playStage(stage));
        }
    }

    @Test
    public void botStartsInPlayState() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        g.debugStartStage(1);
        assertEquals(AdvGame.State.PLAY, g.state);
        assertEquals(1, g.stage);
    }
}
