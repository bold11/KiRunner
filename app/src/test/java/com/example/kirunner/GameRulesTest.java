package com.example.kirunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the lab requirements that are rules.
 * Run in Android Studio: right-click this file > Run 'GameRulesTest'  (or: ./gradlew test)
 */
public class GameRulesTest {

    private static final float EPS = 0.0001f;

    @Test
    public void playerStartsInCenterLane() {
        assertEquals(7, GameRules.LANE_COUNT);
        assertEquals(3, GameRules.START_LANE);   // the middle of 7
    }

    @Test
    public void playerCannotMoveBeyondOuterLanes() {
        assertEquals(0, GameRules.clampLane(-1));
        assertEquals(0, GameRules.clampLane(0));
        assertEquals(3, GameRules.clampLane(3));
        assertEquals(6, GameRules.clampLane(6));
        assertEquals(6, GameRules.clampLane(7));
    }

    @Test
    public void obstacleSpacingIsAtLeastPlayerHeightPlusBuffer() {
        float h = 100f;
        // Buffer starts at twice the player's height: 100 + 2 * 100
        assertEquals(300f, GameRules.minSpawnGap(h), EPS);
        assertEquals(300f, GameRules.spawnGap(h, 0f), EPS);
        for (int i = 0; i <= 10; i++) {
            assertTrue(GameRules.spawnGap(h, i / 10f) >= GameRules.minSpawnGap(h));
        }
        assertEquals(300f, GameRules.spawnGap(h, -5f), EPS); // bad random input is clamped
    }

    @Test
    public void nextObstacleWaitsUntilTheGapIsReached() {
        assertFalse(GameRules.canSpawn(299.9f, 300f));
        assertTrue(GameRules.canSpawn(300f, 300f));
    }

    @Test
    public void pointAndReleaseOnlyAfterTopPassesControlBar() {
        assertFalse(GameRules.passedControlBar(999.5f, 1000f));
        assertTrue(GameRules.passedControlBar(1000f, 1000f));
        assertTrue(GameRules.passedControlBar(1200f, 1000f)); // behind the bar still counts
    }

    @Test
    public void insetShrinksEverySide() {
        Hitbox b = Hitbox.ofSize(0, 0, 100, 200).inset(0.15f);
        assertEquals(15f, b.left, EPS);
        assertEquals(30f, b.top, EPS);
        assertEquals(85f, b.right, EPS);
        assertEquals(170f, b.bottom, EPS);
    }

    @Test
    public void touchingEdgesDoNotCollide() {
        assertFalse(Hitbox.ofSize(0, 0, 10, 10).intersects(Hitbox.ofSize(10, 0, 10, 10)));
        assertTrue(Hitbox.ofSize(0, 0, 10, 10).intersects(Hitbox.ofSize(9, 0, 10, 10)));
    }

    @Test
    public void forgivenessZoneIgnoresNearMisses() {
        Hitbox player = Hitbox.ofSize(0, 0, 100, 50);

        Hitbox graze = Hitbox.ofSize(95, 0, 100, 100);  // images overlap by 5 px
        assertTrue(player.intersects(graze));
        assertFalse(GameRules.isCollision(player, graze));
        assertTrue(GameRules.isNearMiss(player, graze));

        Hitbox direct = Hitbox.ofSize(25, 0, 50, 50);
        assertTrue(GameRules.isCollision(player, direct));
        assertFalse(GameRules.isNearMiss(player, direct));

        Hitbox far = Hitbox.ofSize(300, 0, 50, 50);
        assertFalse(GameRules.isCollision(player, far));
        assertFalse(GameRules.isNearMiss(player, far));
    }

    @Test
    public void speedRampsUpAndIsCapped() {
        assertEquals(GameRules.BASE_SPEED, GameRules.speedFraction(0), EPS);
        assertTrue(GameRules.speedFraction(10) > GameRules.speedFraction(0));
        assertEquals(GameRules.MAX_SPEED, GameRules.speedFraction(10_000), EPS);
    }

    @Test
    public void formsUnlockAtThresholds() {
        assertEquals(0, GameRules.formForScore(0));
        assertEquals(0, GameRules.formForScore(7));
        assertEquals(1, GameRules.formForScore(8));
        assertEquals(2, GameRules.formForScore(16));
        assertEquals(3, GameRules.formForScore(26));
        assertEquals(4, GameRules.formForScore(38));
        assertEquals(4, GameRules.formForScore(999));
    }

    @Test
    public void formProgressGoesFromZeroToOne() {
        assertEquals(0f, GameRules.formProgress(0), EPS);
        assertEquals(0.5f, GameRules.formProgress(4), EPS);
        assertEquals(0f, GameRules.formProgress(8), EPS);
        assertEquals(1f, GameRules.formProgress(38), EPS);
    }

    @Test
    public void runTimeIsFormatted() {
        assertEquals("1:05", new RunStats(0, 65.2f, 0, 0).timeText());
        assertEquals("0:09", new RunStats(0, 9.4f, 0, 0).timeText());
        assertEquals("0:00", new RunStats(0, 0f, 0, 0).timeText());
    }

    @Test
    public void strongerFormsFireFaster() {
        for (int f = 1; f < GameRules.BLAST_COOLDOWNS.length; f++) {
            assertTrue(GameRules.blastCooldown(f) < GameRules.blastCooldown(f - 1));
        }
        // out-of-range forms are clamped instead of crashing
        assertEquals(GameRules.BLAST_COOLDOWNS[0], GameRules.blastCooldown(-3), EPS);
        assertEquals(GameRules.BLAST_COOLDOWNS[4], GameRules.blastCooldown(99), EPS);
    }

    @Test
    public void canOnlyFireWhenCooldownIsOver() {
        assertTrue(GameRules.canFire(0f));
        assertTrue(GameRules.canFire(-0.01f));
        assertFalse(GameRules.canFire(0.2f));
    }

    @Test
    public void blastHitsEnemyItTouches() {
        Hitbox enemy = Hitbox.ofSize(100, 100, 50, 50);
        assertTrue(GameRules.blastHits(Hitbox.ofSize(110, 140, 20, 30), enemy));   // overlapping
        assertFalse(GameRules.blastHits(Hitbox.ofSize(110, 150, 20, 30), enemy));  // just below, touching edge
        assertFalse(GameRules.blastHits(Hitbox.ofSize(300, 120, 20, 30), enemy));  // other lane
    }

    @Test
    public void blastStatDefaultsToZero() {
        assertEquals(0, new RunStats(5, 1f, 0, 0).enemiesBlasted);
        assertEquals(3, new RunStats(5, 1f, 0, 0, 3).enemiesBlasted);
    }

    @Test
    public void rowsGetBusierButAlwaysLeaveFreeLanes() {
        assertEquals(1, GameRules.blocksInRow(0, 0.99f));       // the start is gentle
        for (int score = 0; score < 200; score += 7) {
            for (float roll = 0f; roll <= 1f; roll += 0.1f) {
                int n = GameRules.blocksInRow(score, roll);
                assertTrue(n >= 1);
                assertTrue(GameRules.LANE_COUNT - n >= GameRules.MIN_FREE_LANES);
            }
        }
        assertEquals(3, GameRules.blocksInRow(100, 0.99f));
        assertEquals(1, GameRules.blocksInRow(100, -4f));      // bad input is clamped
    }

    @Test
    public void jumpingHopsOverBlocks() {
        Hitbox player = Hitbox.ofSize(100, 100, 100, 50);
        Hitbox block = Hitbox.ofSize(120, 110, 50, 50);
        assertTrue(GameRules.isCollision(player, block, false));
        assertFalse(GameRules.isCollision(player, block, true));
        assertTrue(GameRules.JUMP_TIME > 0.3f && GameRules.JUMP_TIME < 1f);
    }
}
