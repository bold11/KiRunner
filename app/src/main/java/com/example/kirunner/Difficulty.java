package com.example.kirunner;

/**
 * EASY / HARD / EXTREME (plain Java, unit tested). One table of numbers that both modes read:
 * ADVENTURE uses hearts, monster speed, boss bullet speed / density, boss HP and the rest between attacks;
 * CLASSIC uses the falling speed. Score is multiplied so harder runs are worth more.
 */
public enum Difficulty {
    //        hearts monsters bullets density bossHp rest  classic scoreX
    EASY(7, 0.85f, 0.80f, 0.70f, 0.70f, 1.30f, 0.85f, 1),
    HARD(5, 1.00f, 1.00f, 1.00f, 1.00f, 0.85f, 1.15f, 2),
    EXTREME(3, 1.20f, 1.25f, 1.35f, 1.30f, 0.50f, 1.40f, 3);

    public final int playerHp;
    public final float monsterSpeed;
    public final float bulletSpeed;
    public final float bulletDensity;
    public final float bossHp;
    /** Seconds the boss waits between attack patterns. */
    public final float patternRest;
    public final float classicSpeed;
    public final int scoreMultiplier;

    Difficulty(int playerHp, float monsterSpeed, float bulletSpeed, float bulletDensity, float bossHp,
               float patternRest, float classicSpeed, int scoreMultiplier) {
        this.playerHp = playerHp;
        this.monsterSpeed = monsterSpeed;
        this.bulletSpeed = bulletSpeed;
        this.bulletDensity = bulletDensity;
        this.bossHp = bossHp;
        this.patternRest = patternRest;
        this.classicSpeed = classicSpeed;
        this.scoreMultiplier = scoreMultiplier;
    }

    /** Saved setting -> Difficulty (unknown or missing falls back to EASY). */
    public static Difficulty fromName(String name) {
        if (name != null) {
            for (Difficulty d : values()) {
                if (d.name().equals(name)) return d;
            }
        }
        return EASY;
    }
}
