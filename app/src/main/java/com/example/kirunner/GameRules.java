package com.example.kirunner;

/**
 * The game's rules as plain Java (no Android classes), so they can be unit tested on the JVM.
 * Every lab requirement that is a "rule" lives here and is covered by GameRulesTest.
 */
public final class GameRules {

    private GameRules() {
    }

    // ---- Lanes ----
    /** Landscape phone: 7 columns (the original portrait lab version used 3). */
    public static final int LANE_COUNT = 7;
    public static final int START_LANE = LANE_COUNT / 2; // center

    // ---- Collision forgiveness ----
    /** Fraction shaved off EACH side of the image rectangles to make the hitboxes. */
    public static final float HITBOX_INSET = 0.15f;

    // ---- Obstacle spacing ----
    /** Buffer = 2 x player height (the lab's starting value). */
    public static final float BUFFER_MULTIPLIER = 2f;
    /** Extra random spacing on top of the minimum: 0 .. 1.5 x player height. */
    public static final float RANDOM_EXTRA_GAP = 1.5f;

    // ---- Speed, in screen heights per second ----
    public static final float BASE_SPEED = 0.40f;
    public static final float SPEED_PER_POINT = 0.008f;
    public static final float MAX_SPEED = 0.95f;

    // ---- Transformations: score needed for Base, Storm, Jade, Void, Zenith ----
    public static final int[] FORM_THRESHOLDS = {0, 8, 16, 26, 38};

    // ---- Energy blast (tap to fire) ----
    /** Seconds between shots for each form: stronger forms fire faster. */
    public static final float[] BLAST_COOLDOWNS = {0.70f, 0.60f, 0.52f, 0.45f, 0.38f};
    /** Blast flight speed, in screen heights per second (upward). */
    public static final float BLAST_SPEED = 1.9f;
    /** Points for destroying an enemy block with a blast (same as dodging one). */
    public static final int BLAST_KILL_POINTS = 1;

    // ---- Jump (hop over a block) ----
    /** Seconds in the air: blocks pass underneath without a collision. */
    public static final float JUMP_TIME = 0.62f;
    /** Seconds after landing before the next jump. */
    public static final float JUMP_COOLDOWN = 0.25f;
    /** Rows get busier with the score, but always leave at least this many lanes free. */
    public static final int MIN_FREE_LANES = 3;

    /** Keeps the player inside the outer lanes. */
    public static int clampLane(int lane) {
        return Math.max(0, Math.min(LANE_COUNT - 1, lane));
    }

    /** Minimum distance between obstacles: player height + buffer. */
    public static float minSpawnGap(float playerHeight) {
        return playerHeight + playerHeight * BUFFER_MULTIPLIER;
    }

    /** Gap used for the next obstacle. random01 is clamped to [0, 1], so it is never below the minimum. */
    public static float spawnGap(float playerHeight, float random01) {
        float r = Math.max(0f, Math.min(1f, random01));
        return minSpawnGap(playerHeight) + r * playerHeight * RANDOM_EXTRA_GAP;
    }

    /** A new obstacle (spawned just above the screen) may appear once the previous one's top has fallen this far. */
    public static boolean canSpawn(float previousObstacleTop, float requiredGap) {
        return previousObstacleTop >= requiredGap;
    }

    /** Scoring and release rule: the obstacle's top edge has passed the top of the control bar. */
    public static boolean passedControlBar(float obstacleTop, float controlBarTop) {
        return obstacleTop >= controlBarTop;
    }

    public static float speedFraction(int score) {
        return Math.min(BASE_SPEED + score * SPEED_PER_POINT, MAX_SPEED);
    }

    public static int formForScore(int score) {
        int form = 0;
        for (int i = 1; i < FORM_THRESHOLDS.length; i++) {
            if (score >= FORM_THRESHOLDS[i]) form = i;
        }
        return form;
    }

    /** 0..1 progress toward the next form (1 when already at the final form). */
    public static float formProgress(int score) {
        int form = formForScore(score);
        if (form >= FORM_THRESHOLDS.length - 1) return 1f;
        int from = FORM_THRESHOLDS[form];
        int to = FORM_THRESHOLDS[form + 1];
        return (score - from) / (float) (to - from);
    }

    public static float blastCooldown(int form) {
        int i = Math.max(0, Math.min(BLAST_COOLDOWNS.length - 1, form));
        return BLAST_COOLDOWNS[i];
    }

    /** A shot may be fired once the cooldown has run out. */
    public static boolean canFire(float cooldownLeft) {
        return cooldownLeft <= 0f;
    }

    /** A blast hits an enemy when it touches the enemy's full image (generous, it's the player's attack). */
    public static boolean blastHits(Hitbox blast, Hitbox obstacleImage) {
        return blast.intersects(obstacleImage);
    }

    /** How many blocks the next row has: 1 at first, up to 3 as the score climbs (roll01 picks within the range). */
    public static int blocksInRow(int score, float roll01) {
        int max = score < 10 ? 1 : score < 25 ? 2 : 3;
        max = Math.min(max, LANE_COUNT - MIN_FREE_LANES);
        float r = Math.max(0f, Math.min(0.999f, roll01));
        return 1 + (int) (r * max);
    }

    /** While jumping the hero is above the blocks, so nothing can hit him. */
    public static boolean isCollision(Hitbox playerImage, Hitbox obstacleImage, boolean airborne) {
        return !airborne && isCollision(playerImage, obstacleImage);
    }

    /** Collision uses the forgiving hitboxes, not the full images. */
    public static boolean isCollision(Hitbox playerImage, Hitbox obstacleImage) {
        return playerImage.inset(HITBOX_INSET).intersects(obstacleImage.inset(HITBOX_INSET));
    }

    /** The images touched but the hitboxes did not: the forgiveness zone saved the player. */
    public static boolean isNearMiss(Hitbox playerImage, Hitbox obstacleImage) {
        return playerImage.intersects(obstacleImage) && !isCollision(playerImage, obstacleImage);
    }
}
