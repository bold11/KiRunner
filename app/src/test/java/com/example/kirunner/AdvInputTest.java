package com.example.kirunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Controls. The tap-only cases use the timings measured from the real emulator log on this PC:
 * a held key = one tap, then (Windows repeat delay) 500 ms, then a tap every 33 ms; every tap is a
 * down + up within 0-2 ms; a person pressing again is 150+ ms after the previous tap.
 */
public class AdvInputTest {

    private static final long DELAY = 500, RATE = 33;

    /** One instant tap, exactly like the emulator panel sends it. */
    private static void tap(AdvInput in, int key, long t) {
        in.setKey(key, true, t);
        in.setKey(key, false, t + 1);
    }

    /** A key held from start to end, as the emulator sends it. Returns the time of the last tap. */
    private static long hold(AdvInput in, int key, long start, long end) {
        tap(in, key, start);
        long t = start + DELAY;
        long last = start;
        while (t <= end) {
            tap(in, key, t);
            last = t;
            t += RATE;
        }
        return last;
    }

    /** Replays scheduled taps in real time, one game frame at a time (like the game thread sees them). */
    private static final class Sim {
        final AdvInput in = new AdvInput();
        final AdvInput.Frame f = new AdvInput.Frame();
        private final TreeMap<Long, List<Integer>> taps = new TreeMap<>();

        void add(int key, long t) {
            taps.computeIfAbsent(t, x -> new ArrayList<>()).add(key);
        }

        /** Schedules a key held from start to end (emulator style). Returns the last tap time. */
        long hold(int key, long start, long end) {
            add(key, start);
            long last = start;
            for (long t = start + DELAY; t <= end; t += RATE) {
                add(key, t);
                last = t;
            }
            return last;
        }

        void frame(long now) {
            while (!taps.isEmpty() && taps.firstKey() <= now) {
                Map.Entry<Long, List<Integer>> e = taps.pollFirstEntry();
                for (int k : e.getValue()) tap(in, k, e.getKey());
            }
            in.snapshot(f, now);
        }
    }

    // ------------------------------------------------------------------ key mapping

    @Test
    public void lettersAndArrowsMoveByKeyCode() {
        assertEquals(AdvInput.LEFT, AdventureView.mapKey(KeyEvent.KEYCODE_A, 0));
        assertEquals(AdvInput.RIGHT, AdventureView.mapKey(KeyEvent.KEYCODE_D, 0));
        assertEquals(AdvInput.LEFT, AdventureView.mapKey(KeyEvent.KEYCODE_DPAD_LEFT, 0));
        assertEquals(AdvInput.RIGHT, AdventureView.mapKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        assertEquals(AdvInput.UP, AdventureView.mapKey(KeyEvent.KEYCODE_W, 0));
        assertEquals(AdvInput.DOWN, AdventureView.mapKey(KeyEvent.KEYCODE_S, 0));
    }

    @Test
    public void physicalKeysStillWorkOnOtherKeyboardLayouts() {
        assertEquals(AdvInput.LEFT, AdventureView.mapKey(KeyEvent.KEYCODE_UNKNOWN, 30));    // A
        assertEquals(AdvInput.RIGHT, AdventureView.mapKey(KeyEvent.KEYCODE_UNKNOWN, 32));   // D
        assertEquals(AdvInput.LEFT, AdventureView.mapKey(KeyEvent.KEYCODE_UNKNOWN, 105));   // left arrow
        assertEquals(AdvInput.RIGHT, AdventureView.mapKey(KeyEvent.KEYCODE_UNKNOWN, 106));  // right arrow
        assertEquals(AdvInput.JUMP, AdventureView.mapKey(KeyEvent.KEYCODE_UNKNOWN, 57));    // space
    }

    @Test
    public void spaceJumpsAndEnterNoLongerFires() {
        assertEquals(AdvInput.JUMP, AdventureView.mapKey(KeyEvent.KEYCODE_SPACE, 57));
        assertNotEquals(AdvInput.FIRE, AdventureView.mapKey(KeyEvent.KEYCODE_ENTER, 28));
        assertEquals(AdvInput.FIRE, AdventureView.mapKey(KeyEvent.KEYCODE_J, 36));
    }

    // ------------------------------------------------------------------ real keyboards (down ... up)

    @Test
    public void realKeyboardHoldAndReleaseStopsImmediately() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        in.setKey(AdvInput.LEFT, true, 1000);
        in.snapshot(f, 1300);
        assertTrue(f.left);
        in.setKey(AdvInput.LEFT, false, 1500);
        in.snapshot(f, 1501);
        assertFalse(f.left);
        assertFalse(in.isTapMode());
    }

    @Test
    public void realKeyboardQuickTapIsNotLost() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        in.setKey(AdvInput.JUMP, true, 1000);
        in.setKey(AdvInput.JUMP, false, 1080);   // released before the game thread looked
        in.snapshot(f, 1090);
        assertTrue(f.jumpPressed);
        in.snapshot(f, 1100);
        assertFalse(f.jumpPressed);              // counted once only
    }

    @Test
    public void realKeyboardsNeverLockARun() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        in.setKey(AdvInput.RIGHT, true, 1000);
        in.setKey(AdvInput.JUMP, true, 1200);
        in.setKey(AdvInput.JUMP, false, 1300);
        in.setKey(AdvInput.RIGHT, false, 1500);
        in.snapshot(f, 1501);
        assertFalse(f.right);
        assertFalse(in.hasEverLockedRun());
    }

    // ------------------------------------------------------------------ emulator: holding

    @Test
    public void heldKeyIsOneSmoothHoldWithNoGaps() {
        Sim s = new Sim();
        long last = s.hold(AdvInput.RIGHT, 1000, 3000);
        for (long now = 1000; now <= last; now += 16) {
            s.frame(now);
            assertTrue("right must be held at +" + (now - 1000) + " ms (incl. the 500 ms before repeats)", s.f.right);
        }
    }

    @Test
    public void releasingAHeldKeyStopsAlmostAtOnce() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        long last = hold(in, AdvInput.RIGHT, 1000, 3000);
        in.snapshot(f, last + AdvInput.REPEAT_RELEASE_MS + 5);
        assertFalse("stops within 0.1 s of letting go", f.right);
    }

    @Test
    public void holdingSpaceIsOneJumpNeverAnAutomaticDoubleJump() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        int presses = 0;
        long nextTap = 1000;
        boolean first = true;
        for (long now = 1000; now <= 2500; now += 16) {     // the game thread, one frame at a time
            while (nextTap <= now) {                        // the taps that arrived during this frame
                tap(in, AdvInput.JUMP, nextTap);
                nextTap += first ? DELAY : RATE;
                first = false;
            }
            in.snapshot(f, now);
            if (f.jumpPressed) presses++;
            assertTrue("jump stays held at +" + (now - 1000), f.jumpHeld);
        }
        assertEquals("holding Space = exactly one jump", 1, presses);
    }

    @Test
    public void heldPauseKeyTogglesOnlyOnce() {
        KeyDebounce d = new KeyDebounce();
        int presses = 0;
        if (d.accept(KeyEvent.KEYCODE_P, 1000, 0)) presses++;
        for (long t = 1000 + DELAY; t < 3000; t += RATE) {
            if (d.accept(KeyEvent.KEYCODE_P, t, 0)) presses++;
        }
        assertEquals(1, presses);
        assertTrue("a fresh press later counts again", d.accept(KeyEvent.KEYCODE_P, 4000, 0));
    }

    @Test
    public void heldClassicLaneKeyMovesAtASteadyPace() {
        KeyDebounce d = new KeyDebounce();
        int moves = 0;
        if (d.accept(KeyEvent.KEYCODE_D, 1000, 170)) moves++;
        for (long t = 1000 + DELAY; t < 2000; t += RATE) {
            if (d.accept(KeyEvent.KEYCODE_D, t, 170)) moves++;
        }
        assertTrue("1 lane, then about one lane every 0.17 s: " + moves, moves >= 3 && moves <= 5);
    }

    // ------------------------------------------------------------------ emulator: separate presses

    @Test
    public void quickRepeatedTapsAreSeparatePresses() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        tap(in, AdvInput.JUMP, 1000);
        in.snapshot(f, 1005);
        assertTrue("first jump", f.jumpPressed);
        tap(in, AdvInput.JUMP, 1250);                 // 250 ms later: a person pressing again
        in.snapshot(f, 1255);
        assertTrue("double jump fires straight away", f.jumpPressed);
    }

    @Test
    public void aSecondPressRightAt500msStillCounts() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        tap(in, AdvInput.JUMP, 1000);
        in.snapshot(f, 1005);
        tap(in, AdvInput.JUMP, 1500);                 // looks like a first repeat ...
        in.snapshot(f, 1510);
        assertFalse("not yet: waiting to see if repeats follow", f.jumpPressed);
        in.snapshot(f, 1500 + AdvInput.CONFIRM_MS + 5);
        assertTrue("... no repeats followed, so it was a real press", f.jumpPressed);
    }

    @Test
    public void aSingleTapMovesOnlyBriefly() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        tap(in, AdvInput.RIGHT, 1000);
        in.snapshot(f, 1200);
        assertTrue(f.right);
        in.snapshot(f, 1000 + AdvInput.HOLD_WINDOW_MS + 20);
        assertFalse(f.right);
    }

    // ------------------------------------------------------------------ emulator: two keys at once

    /** The user's case: run with D, jump with Space (the D taps stop), land: the run ends by itself. */
    @Test
    public void runCarriesThroughTheJumpAndEndsAfterLanding() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        long lastD = hold(in, AdvInput.RIGHT, 1000, 2500);
        long space = lastD + 10;
        tap(in, AdvInput.JUMP, space);                // from here on, no more D taps (Windows behaviour)
        in.setHeroState(true, false);                 // jumping
        for (long now = space; now < space + 700; now += 16) {
            in.snapshot(f, now);
            assertTrue("running in the air at +" + (now - space), f.right);
        }
        in.setHeroState(false, false);                // landed
        in.snapshot(f, space + 700 + AdvInput.OTHER_KEY_GRACE_MS);
        assertFalse("no S needed: stops after landing", f.right);
    }

    @Test
    public void bossFlightDiagonalWorksWhileTheOtherKeyIsHeld() {
        Sim s = new Sim();
        s.in.setHeroState(false, true);              // flying in a boss fight
        long lastD = s.hold(AdvInput.RIGHT, 1000, 2000);
        long wStart = lastD + 10;                    // now W is held too: the D taps stop (Windows)
        long lastW = s.hold(AdvInput.UP, wStart, wStart + 1500);
        for (long now = 1000; now <= lastW; now += 16) {
            s.frame(now);
            assertTrue("still moving right at +" + (now - 1000) + " (incl. W's 500 ms repeat delay)", s.f.right);
            if (now >= wStart) assertTrue(s.f.up);
        }
        long now = lastW;
        for (; now < lastW + 600; now += 16) s.frame(now);
        assertFalse("stops shortly after letting go", s.f.right);
        assertFalse(s.f.up);
    }

    @Test
    public void pressingTheOtherDirectionTurnsAround() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        long lastD = hold(in, AdvInput.RIGHT, 1000, 2000);
        tap(in, AdvInput.LEFT, lastD + 20);
        in.snapshot(f, lastD + 30);
        assertTrue(f.left);
        assertFalse(f.right);
    }

    @Test
    public void rightMouseFiresAndKeepsKeyboardMode() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        hold(in, AdvInput.RIGHT, 1000, 2000);
        in.setMouse(AdvInput.FIRE, true);
        in.snapshot(f, 2000);
        assertTrue(f.fireHeld);
        assertTrue(f.firePressed);
        assertTrue("the mouse does not interrupt the run", f.right);
        assertTrue(in.isUsingKeyboard());
        in.setMouse(AdvInput.FIRE, false);
        in.snapshot(f, 2010);
        assertFalse(f.fireHeld);
    }

    // ------------------------------------------------------------------ with the real game

    @Test
    public void runningJumpInTheRealGameMovesForwardAndThenStops() {
        AdvGame g = new AdvGame(Difficulty.EASY, 480);
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        long now = 0;
        for (int i = 0; i < 120; i++, now += 16) {         // stage intro
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        long holdStart = now;
        long nextTap = holdStart;
        boolean first = true;
        for (; now < holdStart + 1200; now += 16) {        // hold D for 1.2 s
            while (nextTap <= now) {
                tap(in, AdvInput.RIGHT, nextTap);
                nextTap += first ? DELAY : RATE;
                first = false;
            }
            in.setHeroState(!g.hero.onGround && !g.hero.flying, g.hero.flying);
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        float xBefore = g.hero.x;
        assertTrue("ran while holding D", xBefore > 120);
        tap(in, AdvInput.JUMP, now);                       // Space: the D taps stop
        for (int i = 0; i < 150; i++, now += 16) {
            in.setHeroState(!g.hero.onGround && !g.hero.flying, g.hero.flying);
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        assertTrue("kept moving forward through the jump", g.hero.x > xBefore + 50);
        assertTrue(g.hero.onGround);
        assertTrue("came to a stop by himself", Math.abs(g.hero.vx) < 1f);
    }

    @Test
    public void stompShowsAScorePopupAndJumpStretchesTheHero() {
        AdvGame g = new AdvGame(Difficulty.HARD, 480);
        AdvInput.Frame in = new AdvInput.Frame();
        for (int i = 0; i < 120; i++) g.update(1f / 60f, in);
        in.jumpPressed = true;
        in.jumpHeld = true;
        g.update(1f / 60f, in);
        assertTrue(g.hero.stretch > 1.1f);
        in.jumpPressed = false;
        for (int i = 0; i < 90; i++) g.update(1f / 60f, in);

        AdvGame.Monster blob = null;
        for (AdvGame.Monster m : g.monsters) {
            if (m.type == AdvGame.Monster.BLOB) {
                blob = m;
                break;
            }
        }
        blob.x = g.hero.x - 1;
        blob.y = g.hero.y + g.hero.h - blob.h;
        g.hero.y = blob.y - g.hero.h - 4;
        g.hero.vy = 120;
        for (int i = 0; i < 10 && blob.alive; i++) g.update(1f / 60f, in);
        assertFalse(blob.alive);
        boolean found = false;
        for (AdvGame.Popup p : g.popups) found |= p.text.equals("+200");   // 100 x HARD score multiplier
        assertTrue(found);
    }

    /** Hardware Input turned on mid-game: the first real (held) release switches to exact real-key control. */
    @Test
    public void switchesToRealKeysWhenHardwareInputIsTurnedOn() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        tap(in, AdvInput.RIGHT, 1000);                    // emulator taps first
        assertTrue(in.isTapMode());
        in.setKey(AdvInput.RIGHT, true, 5000);            // now a real hold ...
        in.setKey(AdvInput.RIGHT, false, 5400);           // ... released after 0.4 s
        assertFalse("real keys detected", in.isTapMode());
        in.setKey(AdvInput.RIGHT, true, 6000);
        in.snapshot(f, 6200);
        assertTrue(f.right);
        in.setKey(AdvInput.RIGHT, false, 6250);
        in.snapshot(f, 6251);
        assertFalse("with real keys you stop the instant you let go", f.right);
    }

    /** Fireboy & Watergirl style: with real keys, run + jump together, and a tap is a tiny step. */
    @Test
    public void realKeysRunAndJumpTogetherAndTapIsATinyStep() {
        AdvGame g = new AdvGame(Difficulty.EASY, 480);
        g.monsters.clear();                                // just the controls, no blob in the way
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        long now = 0;
        for (int i = 0; i < 120; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        // a quick tap of D (80 ms) moves the hero only a little
        float x0 = g.hero.x;
        in.setKey(AdvInput.RIGHT, true, now);
        for (int i = 0; i < 5; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        in.setKey(AdvInput.RIGHT, false, now);
        for (int i = 0; i < 30; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        assertTrue("a tap is a small step, not two steps: moved " + (g.hero.x - x0), g.hero.x - x0 < 14);
        // hold D, press Space while holding: keeps running through the jump and after landing
        in.setKey(AdvInput.RIGHT, true, now);
        for (int i = 0; i < 20; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        in.setKey(AdvInput.JUMP, true, now);
        float xJump = g.hero.x;
        for (int i = 0; i < 10; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        in.setKey(AdvInput.JUMP, false, now);
        for (int i = 0; i < 80; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        assertTrue("landed (y=" + g.hero.y + ")", g.hero.onGround);
        assertTrue("still running after landing (D still held), vx=" + g.hero.vx, g.hero.vx > 100);
        assertTrue("moved forward: " + (g.hero.x - xJump), g.hero.x > xJump + 100);
        in.setKey(AdvInput.RIGHT, false, now);
        for (int i = 0; i < 4; i++, now += 16) {
            in.snapshot(f, now);
            g.update(0.016f, f);
        }
        assertTrue("stops almost at once on release", Math.abs(g.hero.vx) < 1f);
    }

    @Test
    public void realKeyboardDoublePressAt500msCountsExactlyTwice() {
        AdvInput in = new AdvInput();
        AdvInput.Frame f = new AdvInput.Frame();
        int presses = 0;
        in.setKey(AdvInput.JUMP, true, 1000);
        in.setKey(AdvInput.JUMP, false, 1100);
        in.snapshot(f, 1110);
        if (f.jumpPressed) presses++;
        in.setKey(AdvInput.JUMP, true, 1500);
        in.setKey(AdvInput.JUMP, false, 1600);
        for (long now = 1510; now < 1800; now += 16) {
            in.snapshot(f, now);
            if (f.jumpPressed) presses++;
        }
        assertEquals(2, presses);
    }
}
