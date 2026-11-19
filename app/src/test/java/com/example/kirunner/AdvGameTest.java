package com.example.kirunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ADVENTURE mode: levels, hero physics, monsters, difficulty and the bosses (all plain Java). */
public class AdvGameTest {

    private static final float DT = 1f / 60f;
    private static final float VIEW_W = 480;

    private static void run(AdvGame g, AdvInput.Frame in, float seconds) {
        for (float t = 0; t < seconds; t += DT) g.update(DT, in);
    }

    private static AdvGame.Monster firstBlob(AdvGame g) {
        for (AdvGame.Monster m : g.monsters) {
            if (m.type == AdvGame.Monster.BLOB) return m;
        }
        throw new AssertionError("no blob in stage 1");
    }

    @Test
    public void everyStageParsesWithStartAndGateOnTheGround() {
        for (String[] text : AdvLevels.STAGES) {
            AdvMap m = new AdvMap(text);
            assertEquals(14, m.rows);
            assertTrue("start must stand on ground", m.isSolid(m.startCol, m.startRow + 1));
            assertTrue("gate must stand on ground", m.isSolid(m.exitCol, m.exitRow + 1));
            assertTrue(m.exitCol > m.startCol + 50);
            assertFalse(m.monsters.isEmpty());
            assertFalse(m.orbs.isEmpty());
        }
    }

    @Test
    public void difficultyScalesHeartsAndBossHp() {
        assertTrue(Difficulty.EASY.playerHp > Difficulty.HARD.playerHp);
        assertTrue(Difficulty.HARD.playerHp > Difficulty.EXTREME.playerHp);
        AdvBoss easy = new AdvBoss(AdvBoss.JINX, Difficulty.EASY, VIEW_W, AdvGame.FLOOR_Y, 1);
        AdvBoss extreme = new AdvBoss(AdvBoss.JINX, Difficulty.EXTREME, VIEW_W, AdvGame.FLOOR_Y, 1);
        assertTrue(extreme.maxHp > easy.maxHp);
        assertEquals(Difficulty.EASY, Difficulty.fromName(null));
        assertEquals(Difficulty.EASY, Difficulty.fromName("nope"));
        assertEquals(Difficulty.EXTREME, Difficulty.fromName("EXTREME"));
    }

    @Test
    public void heroLandsAndJumpsAboutThreeTiles() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        assertEquals(AdvGame.State.PLAY, g.state);
        assertTrue(g.hero.onGround);
        float groundY = g.hero.y;

        in.jumpPressed = true;
        in.jumpHeld = true;
        g.update(DT, in);
        in.jumpPressed = false;
        float top = groundY;
        for (int i = 0; i < 90; i++) {
            g.update(DT, in);
            top = Math.min(top, g.hero.y);
        }
        float height = groundY - top;
        assertTrue("jump height " + height, height > 2.8f * AdvMap.T && height < 4f * AdvMap.T);
        assertTrue(g.hero.onGround);
    }

    @Test
    public void shortTapGivesALowerJump() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        float groundY = g.hero.y;
        in.jumpPressed = true;
        in.jumpHeld = true;
        g.update(DT, in);
        in.jumpPressed = false;
        in.jumpHeld = false;     // released straight away
        float top = groundY;
        for (int i = 0; i < 90; i++) {
            g.update(DT, in);
            top = Math.min(top, g.hero.y);
        }
        assertTrue(groundY - top < 2f * AdvMap.T);
    }

    @Test
    public void runningRightMovesTheCamera() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 1.7f);
        in.right = true;
        run(g, in, 3f);
        assertTrue(g.hero.x > 200);
        assertTrue(g.camX > 0);
        assertFalse(g.hero.facingLeft);
    }

    @Test
    public void kiBlastTravelsAndDefeatsAMonster() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        AdvGame.Monster blob = firstBlob(g);
        // put a blob right in front of the hero
        blob.x = g.hero.x + 40;
        blob.y = g.hero.y + g.hero.h - blob.h;
        in.firePressed = true;
        g.update(DT, in);
        in.firePressed = false;
        assertEquals(1, g.shots.size());
        run(g, in, 0.5f);
        assertFalse(blob.alive);
        assertEquals(1, g.kills);
        assertTrue(g.ki >= 2);
    }

    @Test
    public void stompingABlobKillsItAndBounces() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        AdvGame.Monster blob = firstBlob(g);
        blob.x = g.hero.x - 1;
        blob.y = g.hero.y + g.hero.h - blob.h;
        g.hero.y = blob.y - g.hero.h - 4;   // just above it, falling
        g.hero.vy = 120;
        int hp = g.hero.hp;
        for (int i = 0; i < 10 && blob.alive; i++) g.update(DT, in);
        assertFalse(blob.alive);
        assertEquals(hp, g.hero.hp);
        assertTrue(g.hero.vy < 0);
    }

    @Test
    public void formsUnlockWithKi() {
        assertEquals(5, AdvGame.KI_THRESHOLDS.length);
        for (int i = 1; i < AdvGame.KI_THRESHOLDS.length; i++) {
            assertTrue(AdvGame.KI_THRESHOLDS[i] > AdvGame.KI_THRESHOLDS[i - 1]);
            assertTrue(AdvGame.SHOT_COOLDOWN[i] < AdvGame.SHOT_COOLDOWN[i - 1]);
            assertTrue(AdvGame.SHOT_DAMAGE[i] >= AdvGame.SHOT_DAMAGE[i - 1]);
        }
        assertEquals("ZENITH", AdvGame.FORM_NAMES[4]);
    }

    @Test
    public void everyBossAttacksAndTheFinalBossFiresFromAllFourSides() {
        for (int type = 0; type < 3; type++) {
            AdvBoss b = new AdvBoss(type, Difficulty.HARD, VIEW_W, AdvGame.FLOOR_Y, 7);
            boolean left = false, right = false, top = false, bottom = false;
            int maxBullets = 0;
            for (int i = 0; i < 60 * 40; i++) {
                b.update(DT, 60, 110);
                maxBullets = Math.max(maxBullets, b.bullets.size());
                for (AdvBoss.Bullet bl : b.bullets) {
                    if (bl.x < 0) left = true;
                    if (bl.x > VIEW_W) right = true;
                    if (bl.y < 0) top = true;
                    if (bl.y > AdvGame.FLOOR_Y) bottom = true;
                }
            }
            assertTrue(AdvBoss.NAMES[type] + " never attacked", maxBullets > 10);
            if (type == AdvBoss.NULL_KING) assertTrue("attacks from every side", left && right && top && bottom);
        }
    }

    @Test
    public void bossGetsEnragedAtHalfHpAndCanBeDefeated() {
        AdvBoss b = new AdvBoss(AdvBoss.WARDEN, Difficulty.EASY, VIEW_W, AdvGame.FLOOR_Y, 3);
        boolean enragedOnce = false;
        while (!b.isDefeated()) {
            if (b.damage(1)) {
                assertFalse(enragedOnce);
                enragedOnce = true;
                assertTrue(b.hp <= b.maxHp / 2);
            }
        }
        assertTrue(enragedOnce);
        assertTrue(b.enraged);
    }

    @Test
    public void reachingTheGateStartsTheBossFight() {
        AdvGame g = new AdvGame(Difficulty.EASY, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        g.hero.x = g.gateX - 4;
        g.hero.y = g.gateY - g.hero.h - 1;
        g.hero.vy = 0;
        in.right = true;
        run(g, in, 0.3f);
        assertEquals(AdvGame.State.BOSS_INTRO, g.state);
        assertNotNull(g.boss);
        assertTrue(g.hero.flying);
        in.right = false;
        run(g, in, 3f);
        assertEquals(AdvGame.State.BOSS, g.state);
    }

    @Test
    public void dyingAtTheBossRetriesTheBossNotTheWholeStage() {
        AdvGame g = new AdvGame(Difficulty.EXTREME, VIEW_W);
        AdvInput.Frame in = new AdvInput.Frame();
        run(g, in, 2f);
        g.hero.x = g.gateX;
        g.hero.y = g.gateY - g.hero.h - 1;
        run(g, in, 3f);    // into the fight, standing still in the bullets
        run(g, in, 60f);
        assertEquals(AdvGame.State.GAME_OVER, g.state);
        assertTrue(g.diedInBoss);
        in.confirmPressed = true;
        g.update(DT, in);
        assertEquals(AdvGame.State.BOSS_INTRO, g.state);
        assertEquals(g.hero.maxHp, g.hero.hp);
    }
}
