package com.example.kirunner;

/**
 * Turns a stream of key taps into clean presses (plain Java, unit tested).
 *
 * The Android Studio emulator sends a held key as instant taps (down + up): one tap, then after the Windows
 * repeat delay (measured: 500 ms) a tap every 33 ms. Without this, holding P would toggle pause 30 times a second and holding Enter on the menu
 * would open the game several times. A tap that comes soon after the previous tap of the same key is part of
 * the same hold, not a new press.
 */
public final class KeyDebounce {

    /** Taps closer together than this are one continuous hold (covers the 500 ms first repeat). */
    public static final long STREAM_GAP_MS = 560;

    private final long[] lastSeen = new long[512];
    private final long[] lastAccepted = new long[512];

    /**
     * @param keyCode       the key
     * @param timeMs        event time
     * @param repeatEveryMs 0 = only the first tap of a hold counts; otherwise also repeat at this rate while held
     * @return true if this tap should act
     */
    public boolean accept(int keyCode, long timeMs, long repeatEveryMs) {
        int k = Math.max(0, Math.min(lastSeen.length - 1, keyCode));
        boolean newPress = lastSeen[k] == 0 || timeMs - lastSeen[k] > STREAM_GAP_MS;
        lastSeen[k] = timeMs;
        if (newPress || (repeatEveryMs > 0 && timeMs - lastAccepted[k] >= repeatEveryMs)) {
            lastAccepted[k] = timeMs;
            return true;
        }
        return false;
    }
}
