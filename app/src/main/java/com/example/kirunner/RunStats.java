package com.example.kirunner;

/** Summary of one run, shown on the Game Over panel. */
public final class RunStats {

    public final int score;
    public final float seconds;
    public final int formIndex;
    public final int closeCalls;
    public final int enemiesBlasted;

    public RunStats(int score, float seconds, int formIndex, int closeCalls) {
        this(score, seconds, formIndex, closeCalls, 0);
    }

    public RunStats(int score, float seconds, int formIndex, int closeCalls, int enemiesBlasted) {
        this.score = score;
        this.seconds = seconds;
        this.formIndex = formIndex;
        this.closeCalls = closeCalls;
        this.enemiesBlasted = enemiesBlasted;
    }

    /** Survival time as m:ss, e.g. "1:05". */
    public String timeText() {
        int total = Math.round(seconds);
        int s = total % 60;
        return (total / 60) + ":" + (s < 10 ? "0" : "") + s;
    }
}
