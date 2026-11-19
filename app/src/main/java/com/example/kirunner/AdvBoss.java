package com.example.kirunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A boss and its bullet-hell attacks (plain Java, unit tested). Attacks come from every side of the
 * arena: rain from the top, walls from the left and right, rings closing in around the hero, beams that
 * warn first and then fire, homing orbs, aimed cards and floor shockwaves.
 *
 * Coordinates are arena pixels: 0..arenaW across, 0..floorY down. The boss is a box centred on (x, y).
 * Difficulty scales bullet speed, density, HP and the rest between attacks; below half HP the boss
 * becomes ENRAGED (faster, denser, and the final boss runs two attacks at once).
 */
public final class AdvBoss {

    public static final int WARDEN = 0, JINX = 1, NULL_KING = 2;
    public static final String[] NAMES = {"GRANITE WARDEN", "JINX", "THE NULL KING"};
    public static final String[] TITLES = {"Guardian of the Sky Gate", "The Laughing Phantom", "Ruler of Nothing"};
    static final int[] BASE_HP = {40, 55, 72};

    // Attack patterns
    static final int RAIN = 0, SIDES = 1, RING = 2, SPIRAL = 3, BEAMS = 4, HOMING = 5, BOX = 6,
            SLAM = 7, CARDS = 8, EDGES = 9;
    private static final int[][] PATTERNS = {
            {SLAM, RAIN, SIDES, BEAMS},                     // Warden
            {CARDS, RING, SPIRAL, SIDES, HOMING},           // Jinx
            {EDGES, BEAMS, BOX, SPIRAL, HOMING, RING},      // Null King
    };
    private static final int[][] ENRAGED_EXTRA = {{BOX}, {BEAMS, BOX}, {RAIN, SIDES}};
    private static final float[] DURATION = {3.2f, 3.8f, 3.0f, 3.4f, 3.7f, 4.0f, 3.6f, 3.0f, 3.6f, 3.4f};

    /** An enemy bullet. ORB flies straight; CARD waits, then dashes at the hero; HOMING turns toward the hero. */
    public static final class Bullet {
        public static final int ORB = 0, CARD = 1, HOMING = 2, ROCK = 3;
        public float x, y, vx, vy, r;
        public int kind, color;
        public float life = 8f, delay, dashSpeed, turn;

        Bullet(float x, float y, float vx, float vy, float r, int kind, int color) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.r = r; this.kind = kind; this.color = color;
        }
    }

    /** A beam across the arena: it flashes a warning line first, then fires for a moment. */
    public static final class Beam {
        public final boolean horizontal;
        public final float pos, half, warn, active;
        public float t;

        Beam(boolean horizontal, float pos, float half, float warn, float active) {
            this.horizontal = horizontal; this.pos = pos; this.half = half; this.warn = warn; this.active = active;
        }

        public boolean isFiring() {
            return t >= warn && t < warn + active;
        }

        public float warnProgress() {
            return Math.min(1f, t / warn);
        }

        boolean finished() {
            return t >= warn + active;
        }
    }

    /** What the boss needs to tell the game (sounds and effects). */
    public interface Events {
        void bossWarn();

        void bossFire();

        void bossSlam(float x, float y);

        void bossTeleport(float x, float y);
    }

    public final int type;
    public final float w, h;
    public float x, y;
    public int hp;
    public final int maxHp;
    public boolean enraged;
    public float flash;           // white flash when hit
    public float anim;
    public final List<Bullet> bullets = new ArrayList<>();
    public final List<Beam> beams = new ArrayList<>();

    private final Difficulty diff;
    private final Random rnd;
    private final float arenaW, floorY;
    private Events events;

    private int pattern = -1, second = -1;
    private float patternT, rest = 1.0f, fire1, fire2, fire3;
    private float homeX, homeY, tx, ty, retarget;
    private int patternIndex;
    private boolean slammed;

    public AdvBoss(int type, Difficulty diff, float arenaW, float floorY, long seed) {
        this.type = type;
        this.diff = diff;
        this.arenaW = arenaW;
        this.floorY = floorY;
        this.rnd = new Random(seed);
        this.w = type == JINX ? 40 : 44;
        this.h = type == JINX ? 44 : 54;
        this.maxHp = Math.max(10, Math.round(BASE_HP[type] * diff.bossHp));
        this.hp = maxHp;
        homeX = arenaW * 0.78f;
        homeY = floorY * 0.42f;
        x = arenaW + 60;       // slides in during the intro
        y = homeY;
        tx = homeX;
        ty = homeY;
    }

    public void setEvents(Events e) {
        events = e;
    }

    public boolean isDefeated() {
        return hp <= 0;
    }

    /** Called while the name card is showing: glide into the arena, no attacks yet. */
    public void updateIntro(float dt) {
        anim += dt;
        x += (homeX - x) * Math.min(1f, dt * 2.5f);
        y = homeY + (float) Math.sin(anim * 2) * 4;
    }

    /** Damage from a ki blast. Returns true if this hit made it ENRAGED. */
    public boolean damage(int amount) {
        if (hp <= 0) return false;
        hp = Math.max(0, hp - amount);
        flash = 0.08f;
        if (!enraged && hp > 0 && hp <= maxHp / 2) {
            enraged = true;
            clearAttacks();
            pattern = -1;
            second = -1;
            rest = 1.2f;
            return true;
        }
        return false;
    }

    public void clearAttacks() {
        bullets.clear();
        beams.clear();
    }

    private float speedMul() {
        return diff.bulletSpeed * (enraged ? 1.2f : 1f);
    }

    private float densityMul() {
        return diff.bulletDensity * (enraged ? 1.25f : 1f);
    }

    /** One frame of the fight. heroX/heroY is the hero's core (the only part bullets can hit). */
    public void update(float dt, float heroX, float heroY) {
        anim += dt;
        if (flash > 0) flash -= dt;
        move(dt);

        if (pattern < 0) {
            rest -= dt;
            if (rest <= 0) startNextPattern();
        } else {
            patternT += dt;
            runPattern(pattern, dt, heroX, heroY, true);
            if (second >= 0) runPattern(second, dt, heroX, heroY, false);
            if (patternT >= DURATION[pattern]) {
                pattern = -1;
                second = -1;
                rest = diff.patternRest * (enraged ? 0.7f : 1f);
            }
        }
        updateBullets(dt, heroX, heroY);
    }

    private void startNextPattern() {
        int[] list = PATTERNS[type];
        int[] extra = enraged ? ENRAGED_EXTRA[type] : new int[0];
        int total = list.length + extra.length;
        int pick = patternIndex % total;
        patternIndex += 1 + (rnd.nextFloat() < 0.35f ? 1 : 0);
        pattern = pick < list.length ? list[pick] : extra[pick - list.length];
        second = -1;
        if (type == NULL_KING && enraged) {
            int[] pool = {RAIN, EDGES, RING};
            second = pool[rnd.nextInt(pool.length)];
            if (second == pattern) second = -1;
        }
        patternT = 0;
        fire1 = fire2 = fire3 = 0;
        slammed = false;
        if (type == JINX) teleport();
        if (pattern == BEAMS && events != null) events.bossWarn();
    }

    private void teleport() {
        x = arenaW * (0.55f + rnd.nextFloat() * 0.33f);
        y = floorY * (0.22f + rnd.nextFloat() * 0.4f);
        tx = x;
        ty = y;
        if (events != null) events.bossTeleport(x, y);
    }

    private void move(float dt) {
        if (pattern == SLAM) {
            // rise, then crash down onto the floor, then float back up
            float landY = floorY - h / 2f;
            if (patternT < 0.8f) y += (floorY * 0.18f - y) * Math.min(1f, dt * 6f);
            else if (patternT < 1.0f) y += (landY - y) * Math.min(1f, dt * 22f);
            else if (patternT > 1.6f) y += (homeY - y) * Math.min(1f, dt * 2f);
            return;
        }
        retarget -= dt;
        if (retarget <= 0) {
            retarget = type == NULL_KING ? 2.2f : 1.6f;
            tx = arenaW * (0.6f + rnd.nextFloat() * 0.28f);
            ty = floorY * (0.2f + rnd.nextFloat() * 0.45f);
        }
        float k = Math.min(1f, dt * (type == WARDEN ? 1.2f : 1.8f));
        x += (tx - x) * k;
        y += (ty - y) * k + (float) Math.sin(anim * 2.4f) * 0.25f;
    }

    // ------------------------------------------------------------------ patterns

    private void runPattern(int p, float dt, float hx, float hy, boolean primary) {
        float spd = speedMul(), dens = densityMul();
        float t = patternT;
        switch (p) {
            case RAIN: {
                fire1 -= dt;
                while (fire1 <= 0) {
                    fire1 += 0.17f / dens;
                    float bx = 8 + rnd.nextFloat() * (arenaW - 16);
                    add(bx, -6, (rnd.nextFloat() - 0.5f) * 30, (85 + rnd.nextFloat() * 45) * spd, 4, Bullet.ORB, 0xFF8FE8FF);
                }
                break;
            }
            case SIDES: {
                fire1 -= dt;
                if (fire1 <= 0) {
                    fire1 += 1.0f;
                    boolean fromLeft = ((int) (t / 1.0f)) % 2 == 0;
                    float gapCenter = 30 + rnd.nextFloat() * (floorY - 60);
                    float gap = 40f / Math.max(0.8f, dens * 0.9f) + 10;
                    for (float by = 10; by < floorY - 4; by += 14) {
                        if (Math.abs(by - gapCenter) < gap / 2f) continue;
                        add(fromLeft ? -6 : arenaW + 6, by, (fromLeft ? 1 : -1) * 105 * spd, 0, 4, Bullet.ORB, 0xFFFFD06A);
                    }
                }
                break;
            }
            case RING: {
                fire1 -= dt;
                if (fire1 <= 0) {
                    fire1 += 0.62f;
                    int n = Math.round(13 * dens);
                    float off = t * 1.7f;
                    for (int i = 0; i < n; i++) {
                        double a = off + i * Math.PI * 2 / n;
                        add(x, y, (float) Math.cos(a) * 82 * spd, (float) Math.sin(a) * 82 * spd, 4, Bullet.ORB, 0xFFFF7CC4);
                    }
                }
                break;
            }
            case SPIRAL: {
                fire1 -= dt;
                while (fire1 <= 0) {
                    fire1 += 0.075f / dens;
                    double a = t * 3.1;
                    for (int k = 0; k < 2; k++) {
                        double b = a + k * Math.PI;
                        add(x, y, (float) Math.cos(b) * 95 * spd, (float) Math.sin(b) * 95 * spd, 3.5f, Bullet.ORB, 0xFFC99BFF);
                    }
                }
                break;
            }
            case BEAMS: {
                fire1 -= dt;
                if (fire1 <= 0 && t < DURATION[BEAMS] - 1.1f) {
                    fire1 += 1.25f;
                    int n = 2 + Math.round(dens);
                    for (int i = 0; i < n; i++) {
                        boolean horiz = rnd.nextBoolean();
                        float pos = horiz ? 16 + rnd.nextFloat() * (floorY - 32) : 16 + rnd.nextFloat() * (arenaW - 32);
                        // keep one aimed at the hero so standing still never works
                        if (i == 0) pos = horiz ? hy : hx;
                        beams.add(new Beam(horiz, pos, 7, 0.8f / Math.max(0.85f, spd), 0.35f));
                    }
                    if (events != null) events.bossWarn();
                }
                break;
            }
            case HOMING: {
                fire1 -= dt;
                if (fire1 <= 0 && t < 2.6f) {
                    fire1 += 0.75f / dens;
                    Bullet b = add(x, y, -60 * spd, (rnd.nextFloat() - 0.5f) * 80, 5, Bullet.HOMING, 0xFF7CFFB0);
                    b.turn = 2.0f;
                    b.life = 4.2f;
                    b.dashSpeed = 70 * spd;
                }
                break;
            }
            case BOX: {
                fire1 -= dt;
                if (fire1 <= 0) {
                    fire1 += 1.15f;
                    int n = Math.round(22 * dens);
                    int gapStart = rnd.nextInt(n);
                    int gapLen = Math.max(3, Math.round(4 / dens));
                    float rad = 150;
                    for (int i = 0; i < n; i++) {
                        int d = (i - gapStart + n) % n;
                        if (d < gapLen) continue;
                        double a = i * Math.PI * 2 / n;
                        float bx = hx + (float) Math.cos(a) * rad, by = hy + (float) Math.sin(a) * rad;
                        add(bx, by, -(float) Math.cos(a) * 62 * spd, -(float) Math.sin(a) * 62 * spd, 4, Bullet.ORB, 0xFFFF5FD2).life = 4.2f;
                    }
                    if (events != null) events.bossWarn();
                }
                break;
            }
            case SLAM: {
                if (!slammed && t >= 1.0f) {
                    slammed = true;
                    float fy = floorY;
                    for (int k = 0; k < 2; k++) {
                        float lane = fy - 6 - k * 13;
                        add(x, lane, -150 * spd * (1 - k * 0.25f), 0, 5, Bullet.ROCK, 0xFFC9B48A);
                        add(x, lane, 150 * spd * (1 - k * 0.25f), 0, 5, Bullet.ROCK, 0xFFC9B48A);
                    }
                    int rocks = Math.round(7 * dens);
                    for (int i = 0; i < rocks; i++) {
                        add(10 + rnd.nextFloat() * (arenaW - 20), -10 - rnd.nextFloat() * 60, 0,
                                (110 + rnd.nextFloat() * 50) * spd, 5, Bullet.ROCK, 0xFFC9B48A);
                    }
                    if (events != null) events.bossSlam(x, floorY);
                }
                fire2 -= dt;
                if (t > 1.3f && fire2 <= 0) {   // a second smaller tremor wave
                    fire2 += 0.55f;
                    add(-6, floorY - 6, 120 * spd, 0, 4, Bullet.ROCK, 0xFFC9B48A);
                    add(arenaW + 6, floorY - 6, -120 * spd, 0, 4, Bullet.ROCK, 0xFFC9B48A);
                }
                break;
            }
            case CARDS: {
                fire1 -= dt;
                if (fire1 <= 0 && t < DURATION[CARDS] - 0.8f) {
                    fire1 += 0.55f / Math.max(0.7f, dens);
                    int n = 3 + Math.round(2 * dens);
                    for (int i = 0; i < n; i++) {
                        double a = Math.PI * (0.5 + (i + 0.5) / n);   // fan out to the left
                        Bullet b = add(x, y, (float) Math.cos(a) * 55, (float) Math.sin(a) * 55, 4, Bullet.CARD, 0xFFFFFFFF);
                        b.delay = 0.55f + i * 0.05f;
                        b.dashSpeed = 210 * spd;
                    }
                }
                break;
            }
            case EDGES: {
                fire1 -= dt;
                while (fire1 <= 0) {
                    fire1 += 0.24f / dens;
                    float bx, by;
                    switch (rnd.nextInt(4)) {
                        case 0: bx = rnd.nextFloat() * arenaW; by = -6; break;
                        case 1: bx = rnd.nextFloat() * arenaW; by = floorY + 6; break;
                        case 2: bx = -6; by = rnd.nextFloat() * floorY; break;
                        default: bx = arenaW + 6; by = rnd.nextFloat() * floorY; break;
                    }
                    float dx = hx - bx, dy = hy - by;
                    float len = (float) Math.max(1, Math.hypot(dx, dy));
                    add(bx, by, dx / len * 92 * spd, dy / len * 92 * spd, 4, Bullet.ORB, 0xFFFF5FD2);
                }
                break;
            }
            default:
                break;
        }
        if (primary) fire3 += dt;
    }

    private Bullet add(float bx, float by, float vx, float vy, float r, int kind, int color) {
        Bullet b = new Bullet(bx, by, vx, vy, r, kind, color);
        bullets.add(b);
        return b;
    }

    private void updateBullets(float dt, float hx, float hy) {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.life -= dt;
            if (b.kind == Bullet.CARD && b.delay > 0) {
                b.delay -= dt;
                b.vx *= 0.94f;
                b.vy *= 0.94f;
                if (b.delay <= 0) {
                    float dx = hx - b.x, dy = hy - b.y;
                    float len = (float) Math.max(1, Math.hypot(dx, dy));
                    b.vx = dx / len * b.dashSpeed;
                    b.vy = dy / len * b.dashSpeed;
                }
            } else if (b.kind == Bullet.HOMING) {
                double cur = Math.atan2(b.vy, b.vx);
                double want = Math.atan2(hy - b.y, hx - b.x);
                double diffA = Math.atan2(Math.sin(want - cur), Math.cos(want - cur));
                double turn = Math.max(-b.turn * dt, Math.min(b.turn * dt, diffA));
                double a = cur + turn;
                b.vx = (float) Math.cos(a) * b.dashSpeed;
                b.vy = (float) Math.sin(a) * b.dashSpeed;
            }
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            boolean out = b.x < -80 || b.x > arenaW + 80 || b.y < -90 || b.y > floorY + 60;
            if (b.life <= 0 || out) bullets.remove(i);
        }
        for (int i = beams.size() - 1; i >= 0; i--) {
            Beam bm = beams.get(i);
            boolean wasFiring = bm.isFiring();
            bm.t += dt;
            if (!wasFiring && bm.isFiring() && events != null) events.bossFire();
            if (bm.finished()) beams.remove(i);
        }
    }

    // ------------------------------------------------------------------ hit tests

    /** True if a circle at (cx, cy) with radius r touches any bullet or firing beam. */
    public boolean hitsHero(float cx, float cy, float r) {
        for (Bullet b : bullets) {
            if (b.kind == Bullet.CARD && b.delay > 0.35f) continue;   // cards are harmless while appearing
            float dx = b.x - cx, dy = b.y - cy, rr = b.r + r;
            if (dx * dx + dy * dy < rr * rr) return true;
        }
        for (Beam bm : beams) {
            if (!bm.isFiring()) continue;
            float d = bm.horizontal ? Math.abs(cy - bm.pos) : Math.abs(cx - bm.pos);
            if (d < bm.half + r) return true;
        }
        // touching the boss itself
        return Math.abs(cx - x) < w * 0.38f + r && Math.abs(cy - y) < h * 0.4f + r;
    }

    /** Does a hero ki blast (box) hit the boss? */
    public boolean hitBy(float left, float top, float right, float bottom) {
        return left < x + w / 2f && right > x - w / 2f && top < y + h / 2f && bottom > y - h / 2f;
    }

    public int currentPattern() {
        return pattern;
    }
}
