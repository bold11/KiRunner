package com.example.kirunner;

import java.util.ArrayList;
import java.util.List;

/**
 * ADVENTURE mode simulation (plain Java, no Android, unit tested).
 *
 * Each stage is a side-scrolling level (run left to right, jump, stomp and blast micro monsters,
 * collect ki orbs, hit power blocks). The boss gate at the end opens an arena where the hero flies on
 * the cloud and fights the stage's boss, bullet-hell style. Three stages, three bosses.
 *
 * Coordinates are "virtual pixels": tiles are 16 px and the view is 224 px tall. AdventureView renders
 * this into a small buffer and scales it up, so the art stays pixel-sharp.
 */
public final class AdvGame implements AdvBoss.Events {

    public enum State { STAGE_INTRO, PLAY, BOSS_INTRO, BOSS, BOSS_DEFEAT, STAGE_CLEAR, GAME_OVER, VICTORY }

    /** Sounds and effects; AdventureView plays / draws them. */
    public interface Listener {
        void sound(int id);

        /** Particles at a point (world coordinates while in a level, arena coordinates in a boss fight). */
        void burst(float x, float y, int color, int count);

        void shake(float strength);

        /** Full-screen flash (transformations, boss defeat). */
        void flash(int color);
    }

    /** Floating "+100" style text. */
    public static final class Popup {
        public final String text;
        public final int color;
        public final float x, y;
        public float t;

        Popup(String text, int color, float x, float y) {
            this.text = text;
            this.color = color;
            this.x = x;
            this.y = y;
        }
    }

    public static final int SFX_JUMP = 0, SFX_ORB = 1, SFX_STOMP = 2, SFX_SHOT = 3, SFX_HIT = 4, SFX_HURT = 5,
            SFX_TRANSFORM = 6, SFX_GO = 7, SFX_WARN = 8, SFX_BOOM = 9, SFX_BREAK = 10, SFX_BEAM = 11;

    public static final int T = AdvMap.T;
    public static final int VIEW_H = 224;
    public static final float FLOOR_Y = VIEW_H - 30;         // boss arena floor (same place in 2D and 2.5D)
    public static final String[] STAGE_NAMES = {"SKY MEADOWS", "CARNIVAL CLOUDS", "NULL CITADEL"};
    public static final String[] FORM_NAMES = {"BASE", "STORM", "JADE", "VOID", "ZENITH"};
    public static final int[] FORM_COLORS = {0xFF2CE5D8, 0xFFAA82FF, 0xFF5AEB96, 0xFFCD82FF, 0xFFEAF6FF};

    // ---- feel (tuned by playtesting) ----
    static final float GRAVITY = 980f, MAX_FALL = 360f;
    // Tight, Fireboy & Watergirl style: full speed almost at once, stop almost at once, steer in the air.
    static final float RUN_SPEED = 120f, RUN_ACCEL = 3000f, FRICTION = 4000f, AIR_CONTROL = 0.9f, AIR_STOP = 2400f;
    static final float JUMP_V = 330f, DOUBLE_JUMP_V = 285f, JUMP_CUT = 0.45f;
    static final float COYOTE = 0.10f, JUMP_BUFFER = 0.12f, STOMP_BOUNCE = 230f;
    static final float HURT_INVULN = 1.3f, FLY_SPEED = 150f;
    static final float SHOT_SPEED = 300f, SHOT_LIFE = 0.9f, BOSS_SHOT_SPEED = 330f;
    /** Ki needed for each form: orbs give 1, defeated monsters give 2. */
    public static final int[] KI_THRESHOLDS = {0, 15, 40, 75, 120};
    public static final float[] SHOT_COOLDOWN = {0.30f, 0.26f, 0.22f, 0.19f, 0.15f};
    public static final int[] SHOT_DAMAGE = {1, 1, 2, 2, 3};

    // ------------------------------------------------------------------ entities

    /** Anything that moves through the tiles. (x, y) is the top-left of its hitbox. */
    public static class Body {
        public float x, y, w, h, vx, vy;
        public boolean onGround;

        public float cx() {
            return x + w / 2f;
        }

        public float cy() {
            return y + h / 2f;
        }

        boolean overlaps(Body o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    public static final class Hero extends Body {
        public boolean facingLeft, flying;
        public int hp, maxHp;
        public float invuln, anim, shotCd, hurtT;
        /** Squash and stretch: 1 = normal, above 1 = stretched tall (jump), below 1 = squashed (landing). */
        public float stretch = 1f;
        float dustT;
        float coyote, jumpBuffer, safeX, safeY;
        int jumpsUsed, stompChain;
        boolean jumpCut;

        Hero() {
            w = 10;
            h = 23;
        }

        /** The small glowing core that bullets can hit in boss fights. */
        public float coreX() {
            return x + w / 2f;
        }

        public float coreY() {
            return y + h * 0.45f;
        }
    }

    public static final class Monster extends Body {
        public static final int BLOB = 0, FLIT = 1, SPIKE = 2;
        public final int type;
        public int hp;
        public boolean alive = true;
        public float anim, flash;
        float homeX, homeY, timer;
        int mode;
        boolean dirLeft = true;

        Monster(int type, float x, float y) {
            this.type = type;
            this.x = x;
            this.y = y;
            w = 12;
            h = type == FLIT ? 10 : 12;
            hp = type == SPIKE ? 2 : 1;
            homeX = x;
            homeY = y;
        }

        public boolean stompable() {
            return type != SPIKE;
        }
    }

    public static final class Pickup {
        public static final int ORB = 0, HEART = 1;
        public final int kind;
        public final float x, y;   // centre
        public boolean taken;

        Pickup(int kind, float x, float y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
        }
    }

    public static final class Shot {
        public float x, y, vx, vy, r;
        public int damage, color;
        float life;
    }

    // ------------------------------------------------------------------ state

    public final Difficulty diff;
    public final float viewW;
    private Listener listener;

    public State state = State.STAGE_INTRO;
    public float stateT;
    public int stage;
    public AdvMap map;
    public final Hero hero = new Hero();
    public final List<Monster> monsters = new ArrayList<>();
    public final List<Pickup> pickups = new ArrayList<>();
    public final List<Shot> shots = new ArrayList<>();
    public final List<Popup> popups = new ArrayList<>();
    public AdvBoss boss;
    public float camX;
    public int score, ki, form, kills;
    public float time;
    public String bannerTitle = "", bannerSub = "";
    public int bannerColor = 0xFFFFC23C;
    public float bannerT;
    public boolean diedInBoss;
    public float gateX, gateY;

    // checkpoint = start of the stage, or the boss gate once reached
    private int cpScore, cpKi, cpForm, cpKills;
    private boolean cpBoss;
    private float hitStop, warnCooldown, boomT;
    private long seed = 1234;

    public AdvGame(Difficulty diff, float viewW) {
        this.diff = diff;
        this.viewW = viewW;
        hero.maxHp = diff.playerHp;
        hero.hp = hero.maxHp;
        loadStage(0, false);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void sfx(int id) {
        if (listener != null) listener.sound(id);
    }

    private void burst(float x, float y, int color, int n) {
        if (listener != null) listener.burst(x, y, color, n);
    }

    private void shake(float s) {
        if (listener != null) listener.shake(s);
    }

    private void flash(int color) {
        if (listener != null) listener.flash(color);
    }

    private void popup(String text, int color, float x, float y) {
        popups.add(new Popup(text, color, x, y));
    }

    private void addScore(int points) {
        score += points * diff.scoreMultiplier;
    }

    public void banner(String title, String sub, int color, float seconds) {
        bannerTitle = title;
        bannerSub = sub;
        bannerColor = color;
        bannerT = seconds;
    }

    public boolean inArena() {
        return state == State.BOSS_INTRO || state == State.BOSS || state == State.BOSS_DEFEAT
                || (state == State.STAGE_CLEAR && boss != null) || (state == State.GAME_OVER && diedInBoss);
    }

    // ------------------------------------------------------------------ stage flow

    private void loadStage(int index, boolean atBoss) {
        stage = index;
        map = new AdvMap(AdvLevels.STAGES[index]);
        monsters.clear();
        pickups.clear();
        shots.clear();
        popups.clear();
        boss = null;
        for (AdvMap.Spawn s : map.monsters) {
            int type = s.type == 'f' ? Monster.FLIT : s.type == 'x' ? Monster.SPIKE : Monster.BLOB;
            Monster m = new Monster(type, 0, 0);
            m.x = s.col * T + (T - m.w) / 2f;
            m.y = type == Monster.FLIT ? s.row * T + 3 : (s.row + 1) * T - m.h;
            m.homeX = m.x;
            m.homeY = m.y;
            monsters.add(m);
        }
        for (AdvMap.Spawn s : map.orbs) pickups.add(new Pickup(Pickup.ORB, s.col * T + T / 2f, s.row * T + T / 2f));
        gateX = map.exitCol * T;
        gateY = (map.exitRow + 1) * T;
        hero.flying = false;
        hero.vx = hero.vy = 0;
        hero.invuln = 0;
        hero.hurtT = 0;
        hero.facingLeft = false;
        hero.x = map.startCol * T + (T - hero.w) / 2f;
        hero.y = (map.startRow + 1) * T - hero.h;
        hero.safeX = hero.x;
        hero.safeY = hero.y;
        camX = 0;
        diedInBoss = false;
        if (atBoss) {
            enterBoss();
        } else {
            state = State.STAGE_INTRO;
            stateT = 0;
            banner("STAGE " + (index + 1), STAGE_NAMES[index], 0xFFFFC23C, 2.0f);
            sfx(SFX_GO);
        }
    }

    private void saveCheckpoint(boolean atBoss) {
        cpScore = score;
        cpKi = ki;
        cpForm = form;
        cpKills = kills;
        cpBoss = atBoss;
    }

    private void enterBoss() {
        state = State.BOSS_INTRO;
        stateT = 0;
        shots.clear();
        popups.clear();
        hero.flying = true;
        hero.vx = hero.vy = 0;
        hero.x = 40;
        hero.y = FLOOR_Y * 0.5f;
        hero.facingLeft = false;
        boss = new AdvBoss(stage, diff, viewW, FLOOR_Y, seed++);
        boss.setEvents(this);
        banner(AdvBoss.NAMES[stage], AdvBoss.TITLES[stage], 0xFFFF5FD2, 2.4f);
        sfx(SFX_WARN);
    }

    /** For tests: start a stage from its beginning, skipping the intro card. */
    void debugStartStage(int stageIndex) {
        loadStage(stageIndex, false);
        state = State.PLAY;
    }

    /** Dev shortcut (menu F1 / F2 / F3): jump straight to a stage's boss fight. */
    public void debugStartAtBoss(int stageIndex) {
        loadStage(Math.max(0, Math.min(AdvLevels.STAGES.length - 1, stageIndex)), true);
    }

    /** Game Over -> try again from the checkpoint (stage start, or straight back to the boss). */
    public void retry() {
        score = cpScore;
        ki = cpKi;
        form = cpForm;
        kills = cpKills;
        hero.hp = hero.maxHp;
        loadStage(stage, cpBoss);
    }

    // ------------------------------------------------------------------ main update

    public void update(float dt, AdvInput.Frame in) {
        if (bannerT > 0) bannerT -= dt;
        if (warnCooldown > 0) warnCooldown -= dt;
        if (hitStop > 0) {           // tiny freeze on big hits = punchy
            hitStop -= dt;
            return;
        }
        stateT += dt;
        hero.anim += dt;
        hero.stretch += (1f - hero.stretch) * Math.min(1f, dt * 12f);
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup pu = popups.get(i);
            pu.t += dt;
            if (pu.t > 0.9f) popups.remove(i);
        }

        switch (state) {
            case STAGE_INTRO:
                if (stateT == dt) saveCheckpoint(false);
                updateLevel(dt, in, stateT > 0.6f);
                if (stateT > 1.6f) state = State.PLAY;
                break;
            case PLAY:
                time += dt;
                updateLevel(dt, in, true);
                break;
            case BOSS_INTRO:
                time += dt;
                if (stateT == dt) saveCheckpoint(true);
                updateFlight(dt, in, false);
                boss.updateIntro(dt);
                if (stateT > 2.3f) {
                    state = State.BOSS;
                    stateT = 0;
                    banner("FIGHT!", "", 0xFFFFC23C, 0.9f);
                    sfx(SFX_GO);
                }
                break;
            case BOSS:
                time += dt;
                updateFlight(dt, in, true);
                updateBossFight(dt);
                break;
            case BOSS_DEFEAT:
                updateFlight(dt, in, false);
                boss.anim += dt;
                boomT -= dt;
                if (boomT <= 0 && stateT < 2.0f) {
                    boomT = 0.12f;
                    burst(boss.x + (float) (Math.random() - 0.5) * boss.w, boss.y + (float) (Math.random() - 0.5) * boss.h,
                            0xFFFFFFFF, 14);
                    sfx(SFX_HIT);
                    shake(0.5f);
                }
                if (stateT > 2.4f) {
                    state = State.STAGE_CLEAR;
                    stateT = 0;
                    boolean last = stage == AdvLevels.STAGES.length - 1;
                    banner(last ? "VICTORY!" : "STAGE CLEAR!", AdvBoss.NAMES[stage] + " defeated", 0xFFFFC23C, 2.6f);
                    sfx(SFX_TRANSFORM);
                }
                break;
            case STAGE_CLEAR:
                updateFlight(dt, in, false);
                if (stateT > 2.8f || (stateT > 1.0f && in.confirmPressed)) {
                    if (stage == AdvLevels.STAGES.length - 1) {
                        state = State.VICTORY;
                        stateT = 0;
                    } else {
                        hero.hp = hero.maxHp;   // fresh hearts for the next stage
                        loadStage(stage + 1, false);
                    }
                }
                break;
            case GAME_OVER:
                if (!hero.flying) {      // fall off the screen
                    hero.vy = Math.min(hero.vy + GRAVITY * dt, MAX_FALL);
                    hero.y += hero.vy * dt;
                }
                if (stateT > 1.0f && in.confirmPressed) retry();
                break;
            case VICTORY:
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ level play

    private void updateLevel(float dt, AdvInput.Frame in, boolean controls) {
        Hero p = hero;
        if (p.invuln > 0) p.invuln -= dt;
        if (p.hurtT > 0) p.hurtT -= dt;
        if (p.shotCd > 0) p.shotCd -= dt;

        // --- run
        int dir = controls && p.hurtT <= 0 ? (in.right ? 1 : 0) - (in.left ? 1 : 0) : 0;
        if (dir != 0) p.facingLeft = dir < 0;
        float accel = RUN_ACCEL * (p.onGround ? 1f : AIR_CONTROL);
        if (dir != 0) {
            p.vx = approach(p.vx, dir * RUN_SPEED, accel * dt);
        } else if (p.onGround) {
            p.vx = approach(p.vx, 0, FRICTION * dt);
        } else {
            p.vx = approach(p.vx, 0, AIR_STOP * dt);   // let go in the air = stop moving sideways
        }

        // --- jump (coyote time + jump buffer + double jump)
        if (p.onGround) {
            p.coyote = COYOTE;
            p.jumpsUsed = 0;
            p.stompChain = 0;
        } else {
            p.coyote -= dt;
            if (p.coyote <= 0 && p.jumpsUsed == 0) p.jumpsUsed = 1;   // walked off a ledge: only the double jump left
        }
        if (controls && in.jumpPressed) p.jumpBuffer = JUMP_BUFFER;
        else p.jumpBuffer -= dt;
        if (p.jumpBuffer > 0) {
            if (p.onGround || p.coyote > 0) {
                p.vy = -JUMP_V;
                p.jumpsUsed = 1;
                p.coyote = 0;
                p.jumpBuffer = 0;
                p.jumpCut = false;
                p.onGround = false;
                p.stretch = 1.22f;
                burst(p.cx(), p.y + p.h, 0xFF9A94D0, 5);
                sfx(SFX_JUMP);
            } else if (p.jumpsUsed < 2) {
                p.vy = -DOUBLE_JUMP_V;
                p.jumpsUsed = 2;
                p.jumpBuffer = 0;
                p.jumpCut = false;
                p.stretch = 1.18f;
                burst(p.cx(), p.y + p.h, 0xFFE6DEF9, 8);   // little cloud puff under the feet
                sfx(SFX_JUMP);
            }
        }
        if (!in.jumpHeld && p.vy < 0 && !p.jumpCut) {   // short hop when the button is released early
            p.vy *= JUMP_CUT;
            p.jumpCut = true;
        }
        p.vy = Math.min(p.vy + GRAVITY * dt, MAX_FALL);

        // --- move + collide
        moveX(p, dt);
        boolean wasGround = p.onGround;
        float fallSpeed = p.vy;
        int bumpCol = moveY(p, dt, true);
        if (p.onGround && !wasGround && fallSpeed > 160f) {      // landing: squash + dust
            p.stretch = 0.72f;
            burst(p.cx(), p.y + p.h, 0xFF9A94D0, 7);
        }
        if (p.onGround && Math.abs(p.vx) > 80f) {                // running dust
            p.dustT -= dt;
            if (p.dustT <= 0) {
                p.dustT = 0.16f;
                burst(p.cx() + (p.facingLeft ? 4 : -4), p.y + p.h, 0x889A94D0, 2);
            }
        }
        if (bumpCol != Integer.MIN_VALUE) bump(bumpCol, (int) Math.floor((p.y - 1) / T));
        if (p.onGround && map.isSolid((int) Math.floor((p.x + 1) / T), (int) Math.floor((p.y + p.h + 1) / T))
                && map.isSolid((int) Math.floor((p.x + p.w - 1) / T), (int) Math.floor((p.y + p.h + 1) / T))) {
            p.safeX = p.x;
            p.safeY = p.y;
        }

        // --- spikes and pits
        if (touchesSpikes(p)) hurtHero(p.cx(), true);
        if (p.y > map.heightPx() + 24) {
            hurtHero(p.cx(), false);
            if (hero.hp > 0) {
                p.x = p.safeX;
                p.y = p.safeY;
                p.vx = p.vy = 0;
            }
        }

        // --- fire
        if (controls && (in.fireHeld || in.firePressed) && p.shotCd <= 0 && p.hurtT <= 0) {
            Shot s = newShot(p.cx() + (p.facingLeft ? -6 : 6), p.y + 10, p.facingLeft ? -SHOT_SPEED : SHOT_SPEED, 0);
            shots.add(s);
            p.shotCd = SHOT_COOLDOWN[form];
            sfx(SFX_SHOT);
        }

        updateMonsters(dt);
        updateShotsInLevel(dt);
        collectPickups();

        // --- camera
        float target = p.cx() - viewW * (p.facingLeft ? 0.58f : 0.40f);   // look ahead in the facing direction
        target = Math.max(0, Math.min(map.widthPx() - viewW, target));
        camX += (target - camX) * Math.min(1f, dt * 5f);

        // --- boss gate
        if (state == State.PLAY && p.x + p.w > gateX + 4 && p.y + p.h > gateY - 2 * T) {
            addScore(500);
            enterBoss();
        }
    }

    private static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }

    /** Horizontal move with wall collision. Returns true if a wall stopped the body. */
    private boolean moveX(Body b, float dt) {
        b.x += b.vx * dt;
        int top = (int) Math.floor(b.y / T), bottom = (int) Math.floor((b.y + b.h - 0.01f) / T);
        if (b.vx > 0) {
            int col = (int) Math.floor((b.x + b.w) / T);
            for (int r = top; r <= bottom; r++) {
                if (map.isSolid(col, r)) {
                    b.x = col * T - b.w - 0.01f;
                    b.vx = 0;
                    return true;
                }
            }
        } else if (b.vx < 0) {
            int col = (int) Math.floor(b.x / T);
            for (int r = top; r <= bottom; r++) {
                if (map.isSolid(col, r)) {
                    b.x = (col + 1) * T + 0.01f;
                    b.vx = 0;
                    return true;
                }
            }
        }
        return false;
    }

    /** Vertical move with floors, ceilings and cloud platforms. Returns the column bumped from below, if any. */
    private int moveY(Body b, float dt, boolean useOneWay) {
        float prevBottom = b.y + b.h;
        b.y += b.vy * dt;
        b.onGround = false;
        int left = (int) Math.floor(b.x / T), right = (int) Math.floor((b.x + b.w - 0.01f) / T);
        if (b.vy >= 0) {
            int row = (int) Math.floor((b.y + b.h) / T);
            for (int c = left; c <= right; c++) {
                boolean land = map.isSolid(c, row) || (useOneWay && map.isOneWay(c, row) && prevBottom <= row * T + 0.5f);
                if (land && row >= 0) {
                    b.y = row * T - b.h;
                    b.vy = 0;
                    b.onGround = true;
                    break;
                }
            }
        } else {
            int row = (int) Math.floor(b.y / T);
            int best = Integer.MIN_VALUE;
            float bestDist = 1e9f;
            for (int c = left; c <= right; c++) {
                if (map.isSolid(c, row)) {
                    float d = Math.abs((c + 0.5f) * T - b.cx());
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
            }
            if (best != Integer.MIN_VALUE) {
                b.y = (row + 1) * T;
                b.vy = 0;
                return best;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The hero's head hit a block from below. */
    private void bump(int col, int row) {
        char ch = map.get(col, row);
        float bx = col * T + T / 2f, by = row * T;
        if (ch == AdvMap.POWER) {
            map.set(col, row, AdvMap.USED);
            addKi(3);
            addScore(30);
            popup("+3 KI", 0xFF2CE5D8, bx, by - 6);
            burst(bx, by - 4, 0xFF2CE5D8, 14);
            sfx(SFX_ORB);
        } else if (ch == AdvMap.POWER_HEART) {
            map.set(col, row, AdvMap.USED);
            pickups.add(new Pickup(Pickup.HEART, bx, by - 9));
            popup("HEART!", 0xFFFF5F8A, bx, by - 6);
            burst(bx, by - 4, 0xFFFF5F8A, 12);
            sfx(SFX_ORB);
        } else if (ch == AdvMap.BRICK) {
            breakBrick(col, row);
        } else {
            sfx(SFX_BREAK);
        }
        hitMonstersOnTop(col, row);
    }

    private void breakBrick(int col, int row) {
        map.set(col, row, AdvMap.EMPTY);
        addScore(20);
        burst(col * T + T / 2f, row * T + T / 2f, 0xFF8C7BC4, 12);
        sfx(SFX_BREAK);
    }

    private void hitMonstersOnTop(int col, int row) {
        for (Monster m : monsters) {
            if (m.alive && m.type != Monster.FLIT && Math.abs(m.cx() - (col * T + T / 2f)) < T
                    && Math.abs((m.y + m.h) - row * T) < 3) {
                killMonster(m, false);
            }
        }
    }

    private boolean touchesSpikes(Hero p) {
        int row = (int) Math.floor((p.y + p.h - 1) / T);
        int left = (int) Math.floor((p.x + 2) / T), right = (int) Math.floor((p.x + p.w - 2) / T);
        for (int c = left; c <= right; c++) {
            if (map.isHazard(c, row) && p.y + p.h > row * T + 7) return true;
        }
        return false;
    }

    private void hurtHero(float fromX, boolean bounce) {
        Hero p = hero;
        if (p.invuln > 0 || p.hp <= 0) return;
        p.hp--;
        p.invuln = HURT_INVULN;
        p.hurtT = 0.35f;
        if (!p.flying) {
            p.vx = (p.cx() < fromX ? -1 : 1) * 140f;
            p.vy = bounce ? -260f : -180f;
        }
        sfx(SFX_HURT);
        shake(0.7f);
        hitStop = 0.06f;
        if (p.hp <= 0) {
            diedInBoss = p.flying;
            state = State.GAME_OVER;
            stateT = 0;
            p.vy = -260f;
            if (p.flying) boss.clearAttacks();
            p.flying = false;
            banner("GAME OVER", diedInBoss ? "Enter / tap: retry the boss" : "Enter / tap: retry the stage", 0xFFFF5F8A, 99f);
        }
    }

    private void addKi(int amount) {
        ki += amount;
        while (form < KI_THRESHOLDS.length - 1 && ki >= KI_THRESHOLDS[form + 1]) {
            form++;
            hero.invuln = Math.max(hero.invuln, 1.0f);
            banner(FORM_NAMES[form] + " FORM!", "Ki blasts are stronger", FORM_COLORS[form], 1.4f);
            flash(FORM_COLORS[form]);
            burst(hero.cx(), hero.cy(), FORM_COLORS[form], 30);
            sfx(SFX_TRANSFORM);
            shake(0.4f);
        }
    }

    private void updateMonsters(float dt) {
        float mult = diff.monsterSpeed;
        float viewMid = camX + viewW / 2f;
        Hero p = hero;
        for (Monster m : monsters) {
            if (!m.alive) continue;
            if (Math.abs(m.cx() - viewMid) > viewW * 0.8f) continue;   // only things near the screen move
            m.anim += dt;
            if (m.flash > 0) m.flash -= dt;
            if (m.type == Monster.FLIT) {
                updateFlit(m, dt, mult);
            } else {
                float speed = (m.type == Monster.BLOB ? 28f : 20f) * mult;
                m.vx = m.dirLeft ? -speed : speed;
                m.vy = Math.min(m.vy + GRAVITY * dt, MAX_FALL);
                if (moveX(m, dt)) m.dirLeft = !m.dirLeft;
                moveY(m, dt, true);
                if (m.onGround) {   // turn around at ledges
                    int aheadCol = (int) Math.floor((m.dirLeft ? m.x - 1 : m.x + m.w + 1) / T);
                    int belowRow = (int) Math.floor((m.y + m.h + 1) / T);
                    if (!map.isSolid(aheadCol, belowRow) && !map.isOneWay(aheadCol, belowRow)) m.dirLeft = !m.dirLeft;
                }
                if (m.y > map.heightPx() + 40) m.alive = false;
            }

            // hero vs monster: stomp from above, otherwise it hurts
            if (state == State.GAME_OVER || !p.overlaps(m)) continue;
            boolean fromAbove = p.vy > 0 && (p.y + p.h) - m.y < 8;
            if (m.stompable() && fromAbove) {
                killMonster(m, true);
                p.vy = -(STOMP_BOUNCE + 60f);
                p.jumpsUsed = 1;     // a stomp gives the double jump back
                p.jumpCut = false;
                p.stompChain++;
                int pts = 100 * (1 << Math.min(p.stompChain - 1, 4));
                addScore(pts);
                String label = "+" + pts * diff.scoreMultiplier;
                popup(p.stompChain > 1 ? "x" + p.stompChain + " " + label : label, 0xFFFFC23C, m.cx(), m.y - 4);
            } else {
                hurtHero(m.cx(), false);
            }
        }
    }

    private void updateFlit(Monster m, float dt, float mult) {
        Hero p = hero;
        switch (m.mode) {
            case 0:   // hover and wait
                m.x = m.homeX + (float) Math.sin(m.anim * 1.3f) * 10f;
                m.y = m.homeY + (float) Math.sin(m.anim * 3f) * 4f;
                if (Math.abs(p.cx() - m.cx()) < 95 && p.y > m.y) {
                    float dx = p.cx() - m.cx(), dy = p.cy() - m.cy();
                    float len = (float) Math.max(1, Math.hypot(dx, dy));
                    m.vx = dx / len * 100f * mult;
                    m.vy = dy / len * 100f * mult;
                    m.mode = 1;
                    m.timer = 1.1f;
                }
                break;
            case 1:   // swoop
                m.x += m.vx * dt;
                m.y += m.vy * dt;
                m.timer -= dt;
                if (m.timer <= 0) m.mode = 2;
                break;
            default:  // fly back home
                float dx = m.homeX - m.x, dy = m.homeY - m.y;
                float len = (float) Math.hypot(dx, dy);
                if (len < 3) {
                    m.mode = 0;
                    m.anim = 0;
                } else {
                    m.x += dx / len * 55f * dt;
                    m.y += dy / len * 55f * dt;
                }
                break;
        }
    }

    private void killMonster(Monster m, boolean stomped) {
        m.alive = false;
        kills++;
        addKi(2);
        if (!stomped) {
            addScore(50);
            popup("+" + 50 * diff.scoreMultiplier, 0xFFFFFFFF, m.cx(), m.y - 4);
        }
        burst(m.cx(), m.cy(), m.type == Monster.FLIT ? 0xFF7A4FD6 : m.type == Monster.SPIKE ? 0xFFC4C8D8 : 0xFFE0408F, 16);
        sfx(SFX_STOMP);
        hitStop = 0.035f;
    }

    private Shot newShot(float x, float y, float vx, float vy) {
        Shot s = new Shot();
        s.x = x;
        s.y = y;
        s.vx = vx;
        s.vy = vy;
        s.r = 3 + form * 0.6f;
        s.damage = SHOT_DAMAGE[form];
        s.color = FORM_COLORS[form];
        s.life = SHOT_LIFE;
        return s;
    }

    private void updateShotsInLevel(float dt) {
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot s = shots.get(i);
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.life -= dt;
            int col = (int) Math.floor(s.x / T), row = (int) Math.floor(s.y / T);
            boolean dead = s.life <= 0;
            if (map.isSolid(col, row)) {
                if (map.get(col, row) == AdvMap.BRICK) breakBrick(col, row);
                else burst(s.x, s.y, s.color, 4);
                dead = true;
            }
            if (!dead) {
                for (Monster m : monsters) {
                    if (!m.alive) continue;
                    if (s.x + s.r > m.x && s.x - s.r < m.x + m.w && s.y + s.r > m.y && s.y - s.r < m.y + m.h) {
                        m.hp -= s.damage;
                        m.flash = 0.1f;
                        if (m.hp <= 0) killMonster(m, false);
                        else {
                            burst(s.x, s.y, s.color, 5);
                            sfx(SFX_HIT);
                        }
                        dead = true;
                        break;
                    }
                }
            }
            if (dead) shots.remove(i);
        }
    }

    private void collectPickups() {
        Hero p = hero;
        for (Pickup k : pickups) {
            if (k.taken) continue;
            if (Math.abs(k.x - p.cx()) < p.w / 2f + 5 && Math.abs(k.y - p.cy()) < p.h / 2f + 5) {
                k.taken = true;
                if (k.kind == Pickup.ORB) {
                    addKi(1);
                    addScore(10);
                    burst(k.x, k.y, 0xFF2CE5D8, 6);
                } else {
                    p.hp = Math.min(p.maxHp, p.hp + 1);
                    burst(k.x, k.y, 0xFFFF5F8A, 10);
                }
                sfx(SFX_ORB);
            }
        }
    }

    // ------------------------------------------------------------------ boss fight

    /** Free flight on the cloud inside the arena (like dodging in a box). */
    private void updateFlight(float dt, AdvInput.Frame in, boolean canFire) {
        Hero p = hero;
        if (p.invuln > 0) p.invuln -= dt;
        if (p.shotCd > 0) p.shotCd -= dt;
        float dx = (in.right ? 1 : 0) - (in.left ? 1 : 0);
        float dy = (in.down ? 1 : 0) - (in.up ? 1 : 0);
        float len = (float) Math.hypot(dx, dy);
        if (len > 0) {
            dx /= len;
            dy /= len;
        }
        p.vx += (dx * FLY_SPEED - p.vx) * Math.min(1f, dt * 16f);
        p.vy += (dy * FLY_SPEED - p.vy) * Math.min(1f, dt * 16f);
        p.x = Math.max(4, Math.min(viewW - 4 - p.w, p.x + p.vx * dt));
        p.y = Math.max(10, Math.min(FLOOR_Y - p.h - 6, p.y + p.vy * dt));
        if (dx != 0) p.facingLeft = dx < 0;

        if (canFire && (in.fireHeld || in.firePressed) && p.shotCd <= 0 && boss != null) {
            float ax = boss.x - p.coreX(), ay = boss.y - p.coreY();
            float al = (float) Math.max(1, Math.hypot(ax, ay));
            shots.add(newShot(p.coreX(), p.coreY(), ax / al * BOSS_SHOT_SPEED, ay / al * BOSS_SHOT_SPEED));
            p.shotCd = SHOT_COOLDOWN[form];
            sfx(SFX_SHOT);
        }
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot s = shots.get(i);
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.life -= dt;
            if (s.life <= 0) shots.remove(i);
        }
    }

    private void updateBossFight(float dt) {
        Hero p = hero;
        boss.update(dt, p.coreX(), p.coreY());
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot s = shots.get(i);
            if (boss.hitBy(s.x - s.r, s.y - s.r, s.x + s.r, s.y + s.r)) {
                shots.remove(i);
                boolean enraged = boss.damage(s.damage);
                addScore(5);
                burst(s.x, s.y, s.color, 5);
                sfx(SFX_HIT);
                if (enraged) {
                    banner("ENRAGED!", AdvBoss.NAMES[stage] + " is getting serious", 0xFFFF5F8A, 1.6f);
                    shake(1f);
                    sfx(SFX_WARN);
                }
                if (boss.isDefeated()) {
                    boss.clearAttacks();
                    shots.clear();
                    addScore(2000 * (stage + 1));
                    state = State.BOSS_DEFEAT;
                    stateT = 0;
                    hitStop = 0.25f;
                    flash(0xFFFFFFFF);
                    popup("+" + 2000 * (stage + 1) * diff.scoreMultiplier, 0xFFFFC23C, boss.x, boss.y - boss.h / 2f - 6);
                    sfx(SFX_BOOM);
                    shake(1.5f);
                    return;
                }
            }
        }
        if (p.invuln <= 0 && boss.hitsHero(p.coreX(), p.coreY(), 3f)) hurtHero(p.coreX(), false);
    }

    // ------------------------------------------------------------------ AdvBoss.Events

    @Override
    public void bossWarn() {
        if (warnCooldown <= 0) {
            sfx(SFX_WARN);
            warnCooldown = 0.4f;
        }
    }

    @Override
    public void bossFire() {
        if (warnCooldown <= 0) {
            sfx(SFX_BEAM);
            shake(0.3f);
            warnCooldown = 0.15f;
        }
    }

    @Override
    public void bossSlam(float x, float y) {
        burst(x, y, 0xFFC9B48A, 24);
        shake(1.2f);
        sfx(SFX_BOOM);
    }

    @Override
    public void bossTeleport(float x, float y) {
        burst(x, y, 0xFFC99BFF, 16);
    }
}
