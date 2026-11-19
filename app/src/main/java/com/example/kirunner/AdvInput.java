package com.example.kirunner;

/**
 * ADVENTURE controls from the keyboard, mouse and touch buttons, merged (plain Java, unit tested).
 * The UI thread writes key / touch state; the game thread takes a snapshot once per frame.
 *
 * Keyboard: A / D or arrows move, Space / W / Up jump (fly up in boss fights), S / Down fly down (or stop),
 * Enter confirms, P / Esc pause, V switches 2D / 2.5D, J / K fire. Mouse: right button fires (hold = auto-fire).
 *
 * <h3>Tap-only keyboards (the Android Studio emulator panel)</h3>
 * The emulator panel never sends "key released". Every key, held or not, arrives as an instant tap
 * (down + up within 2 ms). Measured on this project's PC: a held key sends one tap, waits the Windows
 * repeat delay (500 ms), then taps every 33 ms. Separate presses by a person are 150+ ms apart.
 * So the taps are decoded ("typematic decoding"):
 * <ul>
 *   <li>a tap 60 ms or less after the previous one = the key is being held (auto-repeat);</li>
 *   <li>a tap about 500 ms after a single press = probably the first auto-repeat. It only counts as a new
 *       press if no fast repeat follows within CONFIRM_MS (so holding Space never double-jumps);</li>
 *   <li>any other tap = a new press (so quick taps for a double jump always work).</li>
 * </ul>
 * A key counts as held while its repeats keep coming (released 90 ms after they stop, so you stop at once),
 * and for HOLD_WINDOW_MS after a single press (to bridge the 500 ms before repeats start).
 *
 * Two keys at once: Windows only repeats the LAST key pressed, so running with D and pressing Space stops the
 * D taps. A run interrupted by another key therefore stays on while the hero is in the air, and for
 * OTHER_KEY_GRACE_MS after the other key's last tap; the other direction (or S / Down on foot) ends it at once.
 * Real keyboards (proper down / up events) skip all of this.
 */
public final class AdvInput {

    public static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3, JUMP = 4, FIRE = 5;
    private static final int KEYS = 6;

    /** A release this soon after the press marks a tap-only keyboard. */
    static final long TAP_RELEASE_MS = 30;
    /** Taps this close together are auto-repeat (a held key). Measured: 33 ms. */
    static final long REPEAT_GAP_MS = 60;
    /** The window where a tap is probably the first auto-repeat. Measured: 499-507 ms. */
    static final long FIRST_REPEAT_MIN_MS = 440, FIRST_REPEAT_MAX_MS = 580;
    /** How long to wait for a fast repeat before deciding a ~500 ms tap was a real new press. */
    static final long CONFIRM_MS = 60;
    /** After a single press, the key counts as held this long (covers the 500 ms repeat delay). */
    static final long HOLD_WINDOW_MS = 540;
    /** While repeats are coming, the key is released this soon after they stop. */
    static final long REPEAT_RELEASE_MS = 90;
    /** A run interrupted by another key keeps going this long after that key's last tap (when not in the air). */
    static final long OTHER_KEY_GRACE_MS = 250;

    /** One frame's worth of input. */
    public static final class Frame {
        public boolean left, right, up, down, jumpHeld, fireHeld;
        public boolean jumpPressed, firePressed, pausePressed, viewPressed, confirmPressed;
    }

    // real keyboards
    private final boolean[] keyboard = new boolean[KEYS];
    private final long[] lastRealDown = new long[KEYS];
    // tap-only keyboards
    private final long[] lastTap = new long[KEYS];
    private final boolean[] repeating = new boolean[KEYS];
    private final long[] pendingPress = new long[KEYS];
    private final boolean[] runLock = new boolean[KEYS];
    private long lastOtherTap;
    private int lastOtherKey = -1;
    private boolean tapMode, airborne, flying, everLocked;
    private int tapModeChanges;
    private long lastAnyKey;
    // touch + mouse
    private final boolean[] touch = new boolean[KEYS];
    private final boolean[] mouse = new boolean[KEYS];
    private int jumpPresses, firePresses, pausePresses, viewPresses, confirmPresses;
    private boolean usingKeyboard;

    // ------------------------------------------------------------------ keyboard

    /** Keyboard key changed. timeMs is the event time (same clock as snapshot's nowMs). */
    public synchronized void setKey(int key, boolean down, long timeMs) {
        usingKeyboard = true;
        lastAnyKey = timeMs;
        if (!down) {
            if (timeMs - lastRealDown[key] <= TAP_RELEASE_MS) {
                if (!tapMode) {
                    tapMode = true;      // released instantly: this keyboard only sends taps
                    tapModeChanges++;
                }
            } else if (tapMode) {
                // a real, held release arrived (e.g. Hardware Input was just turned on): use real keys from now on
                tapMode = false;
                tapModeChanges++;
                for (int i = 0; i < KEYS; i++) {
                    runLock[i] = false;
                    pendingPress[i] = 0;
                    repeating[i] = false;
                }
            }
            keyboard[key] = false;
            return;
        }
        lastRealDown[key] = timeMs;
        if (!tapMode) {
            if (!keyboard[key]) {
                countPress(key);
                onNewPress(key);
            }
            keyboard[key] = true;
            lastTap[key] = timeMs;       // for "the most recent direction wins"
            return;
        }
        onTap(key, timeMs, true);
    }

    private void onTap(int key, long t, boolean count) {
        long gap = lastTap[key] == 0 ? Long.MAX_VALUE : t - lastTap[key];
        if (gap <= REPEAT_GAP_MS) {
            repeating[key] = true;               // held: auto-repeat
            pendingPress[key] = 0;               // the ~500 ms tap before this was the first repeat
        } else if (!repeating[key] && gap >= FIRST_REPEAT_MIN_MS && gap <= FIRST_REPEAT_MAX_MS) {
            pendingPress[key] = t;               // first repeat, or a new press? decided in CONFIRM_MS
        } else {
            repeating[key] = false;
            pendingPress[key] = 0;
            if (count) countPress(key);          // a person pressed it again
            onNewPress(key);
        }
        lastTap[key] = t;
        boolean isOther = key != LEFT && key != RIGHT && (key != DOWN || flying);
        if (isOther) {
            lastOtherTap = t;
            lastOtherKey = key;
            for (int d = LEFT; d <= RIGHT; d++) {      // another key while running: keep the run going
                if (tapMode && tapHeld(d, t)) {
                    runLock[d] = true;
                    everLocked = true;
                }
            }
        }
    }

    private void onNewPress(int key) {
        if (key == LEFT || key == RIGHT) {
            int other = key == LEFT ? RIGHT : LEFT;
            lastTap[other] = 0;                  // turning around lets go of the other direction
            repeating[other] = false;
            runLock[other] = false;
            runLock[key] = false;                // this direction's own taps are in charge again
        } else if (key == DOWN && !flying) {
            for (int d = LEFT; d <= RIGHT; d++) {    // S / Down on foot = stop
                lastTap[d] = 0;
                repeating[d] = false;
                runLock[d] = false;
            }
        }
    }

    private void countPress(int key) {
        if (key == JUMP || key == UP) jumpPresses++;
        if (key == FIRE) firePresses++;
    }

    private boolean tapHeld(int k, long now) {
        if (lastTap[k] == 0) return false;
        long since = now - lastTap[k];
        return since >= 0 && since <= (repeating[k] ? REPEAT_RELEASE_MS : HOLD_WINDOW_MS);
    }

    private boolean held(int k, long now) {
        if (!tapMode) {
            if ((k == LEFT || k == RIGHT) && keyboard[k]) {
                int other = k == LEFT ? RIGHT : LEFT;     // both held: the most recent one wins
                if (keyboard[other] && lastRealDown[other] > lastRealDown[k]) return false;
            }
            return keyboard[k];
        }
        return tapHeld(k, now) || runLock[k];
    }

    /** The hero's state, each frame: in the air (jumping / falling) and / or flying (boss fights). */
    public synchronized void setHeroState(boolean inAir, boolean isFlying) {
        airborne = inAir;
        flying = isFlying;
    }

    // ------------------------------------------------------------------ mouse + touch

    /** Mouse buttons: fire without bringing the touch buttons back. */
    public synchronized void setMouse(int key, boolean down) {
        if (down && !mouse[key] && key == FIRE) firePresses++;
        mouse[key] = down;
    }

    public synchronized void setTouch(int key, boolean down) {
        if (down && !touch[key]) countPress(key);
        touch[key] = down;
        usingKeyboard = false;
    }

    /** A hardware keyboard is attached: start in keyboard mode (touch buttons hidden). */
    public synchronized void setUsingKeyboard(boolean on) {
        usingKeyboard = on;
    }

    public synchronized void releaseAllTouches() {
        for (int i = 0; i < KEYS; i++) {
            touch[i] = false;
            mouse[i] = false;
        }
    }

    /** Focus lost (pause, app switch): forget every key so nothing stays stuck down. */
    public synchronized void releaseAll() {
        releaseAllTouches();
        for (int i = 0; i < KEYS; i++) {
            keyboard[i] = false;
            lastTap[i] = 0;
            repeating[i] = false;
            pendingPress[i] = 0;
            runLock[i] = false;
        }
    }

    public synchronized void pressPause() {
        pausePresses++;
    }

    public synchronized void pressView() {
        viewPresses++;
    }

    /** Enter, or a tap on a menu screen (Game Over, Victory, pause). */
    public synchronized void pressConfirm() {
        confirmPresses++;
    }

    public synchronized boolean isUsingKeyboard() {
        return usingKeyboard;
    }

    public synchronized boolean isTapMode() {
        return tapMode;
    }

    /** Counts switches between tap-only and real keys (so the screen can show a tip / a confirmation). */
    public synchronized int tapModeChanges() {
        return tapModeChanges;
    }

    public synchronized boolean isRunLocked() {
        return runLock[LEFT] || runLock[RIGHT];
    }

    public synchronized boolean hasEverLockedRun() {
        return everLocked;
    }

    public synchronized long lastKeyTime() {
        return lastAnyKey;
    }

    // ------------------------------------------------------------------ per frame

    public synchronized void snapshot(Frame f, long nowMs) {
        // a ~500 ms tap with no fast repeat after it was a real new press
        for (int k = 0; k < KEYS; k++) {
            if (tapMode && pendingPress[k] != 0 && nowMs - pendingPress[k] >= CONFIRM_MS) {
                pendingPress[k] = 0;
                if (!repeating[k]) {
                    countPress(k);
                    onNewPress(k);
                }
            }
        }
        // a run carried by another key ends once the hero is down and that key is no longer held (+ grace)
        boolean otherHeld = lastOtherKey >= 0 && tapHeld(lastOtherKey, nowMs);
        for (int d = LEFT; d <= RIGHT; d++) {
            if (runLock[d] && !airborne && !otherHeld && nowMs - lastOtherTap > OTHER_KEY_GRACE_MS) runLock[d] = false;
        }

        f.left = held(LEFT, nowMs) || touch[LEFT];
        f.right = held(RIGHT, nowMs) || touch[RIGHT];
        if (f.left && f.right) {          // both: the most recent one wins
            if (Math.max(lastTap[LEFT], lastRealDown[LEFT]) >= Math.max(lastTap[RIGHT], lastRealDown[RIGHT])) f.right = false;
            else f.left = false;
        }
        f.up = held(UP, nowMs) || held(JUMP, nowMs) || touch[UP] || touch[JUMP];
        f.down = held(DOWN, nowMs) || touch[DOWN];
        f.jumpHeld = f.up;
        f.fireHeld = held(FIRE, nowMs) || touch[FIRE] || mouse[FIRE];
        f.jumpPressed = jumpPresses > 0;
        f.firePressed = firePresses > 0;
        f.pausePressed = pausePresses > 0;
        f.viewPressed = viewPresses > 0;
        f.confirmPressed = confirmPresses > 0;
        jumpPresses = firePresses = pausePresses = viewPresses = confirmPresses = 0;
    }
}
