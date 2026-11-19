package com.example.kirunner;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.SparseArray;

import java.util.Random;

/**
 * Draws ADVENTURE mode into a small pixel buffer (virtual resolution, 224 px tall) that AdventureView
 * scales up with nearest-neighbour, so all the pixel art stays sharp.
 *
 * Layers, back to front: animated space background (parallax), distant asteroids, floating islands (2.5D),
 * far dust, the level (thick 3D blocks in 2.5D), shadows, pickups, monsters, the hero, shots, particles,
 * ambient darkness + additive glow lights, foreground dust and spires (2.5D), flash, vignette.
 *
 * 2D view keeps the background, lights and effects but flattens the blocks and drops the depth layers.
 */
public final class AdvRenderer {

    private static final int T = AdvMap.T;
    private static final int H = AdvGame.VIEW_H;
    private static final float DX = 4, DY = -4;            // 2.5D block extrusion (up and to the right)

    // ---- art
    private final Bitmap tiles, cloud, blob, flit, spike, heart, glow;
    private final Bitmap[] heroSheets = new Bitmap[5];
    private final Bitmap[] bosses = new Bitmap[3];
    private final Bitmap bgSheet;
    private final int bgFrameW, bgFrameH, bgFrames;

    // ---- buffer + layers
    public final int viewW;
    public final Bitmap buffer;
    private final Canvas bc;
    private final Bitmap asteroids, islands, spires, vignette;

    private final Paint px = new Paint();              // crisp pixels
    private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint();
    private final Paint flashPaint = new Paint();
    private final Paint ghostPaint = new Paint();
    private final Paint fadePaint = new Paint();
    private final Paint addPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final SparseArray<LightingColorFilter> tint = new SparseArray<>();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Path path = new Path();
    private final Random rnd = new Random(3);

    // ---- particles (plain arrays: no garbage mid-game)
    private static final int MAX_P = 320;
    private final float[] pX = new float[MAX_P], pY = new float[MAX_P], pVX = new float[MAX_P], pVY = new float[MAX_P];
    private final float[] pLife = new float[MAX_P], pMax = new float[MAX_P];
    private final int[] pColor = new int[MAX_P];
    private int pCount;
    private boolean particlesInArena;

    // ---- space dust at three depths
    private static final int DUST = 70;
    private final float[] dX = new float[DUST], dY = new float[DUST], dDepth = new float[DUST];

    // ---- hero after-images
    private static final int TRAIL = 5;
    private final float[] tX = new float[TRAIL], tY = new float[TRAIL];
    private final int[] tFrame = new int[TRAIL];
    private final boolean[] tFlip = new boolean[TRAIL];
    private int tCount, tHead;
    private float tTimer;
    private final LightingColorFilter[] trailTint = new LightingColorFilter[5];

    public float shakeTime, shakeAmount;
    private float flashTime;
    private int flashColor = Color.WHITE;
    private float time;

    private static final int[] STAGE_TINT = {0x00000000, 0x30FF4FA8, 0x50200018};
    private static final int[] AMBIENT_DARK = {50, 70, 105};
    private static final int[] TOP_FACE = {0xFF8FFFEA, 0xFF4A4790, 0xFFB7A8FF, 0xFFFFF0A8, 0xFF9A8A9A, 0, 0, 0xFFA07DE8};
    private static final int[] TOP_FACE_BACK = {0xFF3FB8B8, 0xFF34316A, 0xFF7E6FC8, 0xFFE0B040, 0xFF706070, 0, 0, 0xFF6A4FC0};
    private static final int[] SIDE_FACE = {0xFF1C1A3E, 0xFF1C1A3E, 0xFF2A2058, 0xFF8A5A10, 0xFF3A303A, 0, 0, 0xFF140F2A};
    private static final int[] SIDE_FACE_BACK = {0xFF0E0C24, 0xFF0E0C24, 0xFF17123A, 0xFF5A3A08, 0xFF241E24, 0, 0, 0xFF0A0718};

    public AdvRenderer(Resources res, int viewW) {
        this.viewW = viewW;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        tiles = BitmapFactory.decodeResource(res, R.drawable.adv_tiles, o);
        cloud = BitmapFactory.decodeResource(res, R.drawable.adv_cloud, o);
        blob = BitmapFactory.decodeResource(res, R.drawable.adv_blob, o);
        flit = BitmapFactory.decodeResource(res, R.drawable.adv_flit, o);
        spike = BitmapFactory.decodeResource(res, R.drawable.adv_spike, o);
        int[] heroIds = {R.drawable.adv_hero_0, R.drawable.adv_hero_1, R.drawable.adv_hero_2, R.drawable.adv_hero_3, R.drawable.adv_hero_4};
        for (int i = 0; i < 5; i++) heroSheets[i] = BitmapFactory.decodeResource(res, heroIds[i], o);
        bosses[0] = BitmapFactory.decodeResource(res, R.drawable.boss_warden, o);
        bosses[1] = BitmapFactory.decodeResource(res, R.drawable.boss_jinx, o);
        bosses[2] = BitmapFactory.decodeResource(res, R.drawable.boss_null, o);
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inScaled = false;
        bo.inPreferredConfig = Bitmap.Config.RGB_565;      // background has no transparency: half the memory
        bgSheet = BitmapFactory.decodeResource(res, R.drawable.bg_space, bo);
        bgFrameW = bgSheet.getWidth();
        bgFrameH = 300;
        bgFrames = Math.max(1, bgSheet.getHeight() / bgFrameH);
        heart = makeHeart();
        glow = makeGlow();

        buffer = Bitmap.createBitmap(viewW, H, Bitmap.Config.ARGB_8888);
        bc = new Canvas(buffer);
        px.setFilterBitmap(false);
        px.setAntiAlias(false);
        fill.setAntiAlias(false);
        flashPaint.setColorFilter(new LightingColorFilter(0xFFFFFFFF, 0x00FFFFFF));
        fadePaint.setFilterBitmap(false);
        addPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        asteroids = makeAsteroids(viewW * 2);
        islands = makeIslands(viewW * 2);
        spires = makeSpires(viewW * 3);
        vignette = makeVignette(viewW);
        Random r = new Random(11);
        for (int i = 0; i < DUST; i++) {
            dX[i] = r.nextFloat() * viewW * 2;
            dY[i] = r.nextFloat() * H;
            dDepth[i] = i < 30 ? 0.35f : i < 58 ? 0.75f : 1.35f;   // far, mid, in front of the action
        }
    }

    // ------------------------------------------------------------------ generated art

    private static Bitmap makeHeart() {
        String[] rows = {".XX.XX.", "XWRXRRX", "XRRRRRX", "XRRRRRX", ".XRRRX.", "..XRX..", "...X..."};
        Bitmap b = Bitmap.createBitmap(7, 7, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                char c = rows[y].charAt(x);
                b.setPixel(x, y, c == 'X' ? 0xFF1A1430 : c == 'R' ? 0xFFFF4F6A : c == 'W' ? 0xFFFFFFFF : 0);
            }
        }
        return b;
    }

    public Bitmap heartBitmap() {
        return heart;
    }

    /** Soft white radial blob; tinted and added on top for lights. */
    private static Bitmap makeGlow() {
        int s = 64;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x18FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.25f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return b;
    }

    /** Distant asteroids drifting in the void (tileable). */
    private static Bitmap makeAsteroids(int w) {
        Bitmap b = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        Random r = new Random(21);
        for (int i = 0; i < w / 40; i++) {
            float x = r.nextFloat() * w, y = 30 + r.nextFloat() * (H * 0.6f), rad = 2 + r.nextFloat() * 6;
            p.setColor(0xCC141030);
            rock(c, p, x, y, rad, r);
            p.setColor(0x665A55A8);
            c.drawRect(x - rad * 0.5f, y - rad * 0.6f, x, y - rad * 0.6f + 1, p);
        }
        return b;
    }

    private static void rock(Canvas c, Paint p, float x, float y, float rad, Random r) {
        Path path = new Path();
        int n = 7;
        for (int k = 0; k < n; k++) {
            double a = k * Math.PI * 2 / n;
            float rr = rad * (0.7f + r.nextFloat() * 0.45f);
            float vx = x + (float) Math.cos(a) * rr, vy = y + (float) Math.sin(a) * rr * 0.8f;
            if (k == 0) path.moveTo(vx, vy);
            else path.lineTo(vx, vy);
        }
        path.close();
        c.drawPath(path, p);
    }

    /** Floating islands far behind the level (tileable): they sell the depth in 2.5D. */
    private static Bitmap makeIslands(int w) {
        Bitmap b = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint body = new Paint(), top = new Paint(), glint = new Paint();
        body.setColor(0xFF1A1740);
        top.setColor(0xFF2F8C96);
        glint.setColor(0xFF8FFFEA);
        Random r = new Random(5);
        float x = 20;
        while (x < w - 60) {
            float iw = 34 + r.nextFloat() * 50, iy = H * (0.38f + r.nextFloat() * 0.3f), depth = 14 + r.nextFloat() * 22;
            Path p = new Path();
            p.moveTo(x, iy);
            p.lineTo(x + iw, iy);
            p.lineTo(x + iw * 0.8f, iy + depth * 0.6f);
            p.lineTo(x + iw * 0.55f, iy + depth);
            p.lineTo(x + iw * 0.35f, iy + depth * 0.75f);
            p.lineTo(x + iw * 0.15f, iy + depth * 0.5f);
            p.close();
            c.drawPath(p, body);
            c.drawRect(x, iy - 2, x + iw, iy + 1, top);
            for (int k = 0; k < 3; k++) {
                float gx = x + 4 + r.nextFloat() * (iw - 8);
                c.drawRect(gx, iy - 3, gx + 1, iy - 2, glint);
            }
            if (r.nextFloat() < 0.5f) {         // crystal spike on top
                float cx = x + iw * (0.3f + r.nextFloat() * 0.4f);
                Path s = new Path();
                s.moveTo(cx - 3, iy - 1);
                s.lineTo(cx, iy - 10 - r.nextFloat() * 8);
                s.lineTo(cx + 3, iy - 1);
                s.close();
                c.drawPath(s, top);
            }
            x += iw + 60 + r.nextFloat() * 120;
        }
        return b;
    }

    /** Dark crystal spires that pass in front of the player (foreground, 2.5D only). */
    private static Bitmap makeSpires(int w) {
        Bitmap b = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(), rim = new Paint();
        p.setColor(0xF0060414);
        rim.setColor(0xAA6A4FC0);
        Random r = new Random(8);
        float x = 120;
        while (x < w - 80) {
            int n = 1 + r.nextInt(3);
            for (int k = 0; k < n; k++) {
                float sx = x + k * (10 + r.nextFloat() * 8), sw = 8 + r.nextFloat() * 10, sh = 22 + r.nextFloat() * 30;
                Path s = new Path();
                s.moveTo(sx - sw / 2, H);
                s.lineTo(sx - sw * 0.15f, H - sh);
                s.lineTo(sx + sw * 0.2f, H - sh * 0.9f);
                s.lineTo(sx + sw / 2, H);
                s.close();
                c.drawPath(s, p);
                c.drawRect(sx - sw * 0.15f, H - sh, sx - sw * 0.15f + 1, H - sh * 0.5f, rim);
            }
            x += 260 + r.nextFloat() * 260;
        }
        return b;
    }

    private static Bitmap makeVignette(int w) {
        Bitmap b = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        float r = (float) Math.hypot(w / 2f, H / 2f);
        p.setShader(new RadialGradient(w / 2f, H / 2f, r, new int[]{0x00000000, 0x00000000, 0x99000010},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, w, H, p);
        return b;
    }

    // ------------------------------------------------------------------ effects (game thread)

    public void burst(float x, float y, int color, int n, boolean arena) {
        if (arena != particlesInArena) {
            pCount = 0;
            particlesInArena = arena;
        }
        for (int k = 0; k < n && pCount < MAX_P; k++) {
            int i = pCount++;
            double a = rnd.nextFloat() * Math.PI * 2;
            float sp = 30 + rnd.nextFloat() * 110;
            pX[i] = x;
            pY[i] = y;
            pVX[i] = (float) Math.cos(a) * sp;
            pVY[i] = (float) Math.sin(a) * sp - 30;
            pMax[i] = pLife[i] = 0.3f + rnd.nextFloat() * 0.5f;
            pColor[i] = color;
        }
    }

    public void shake(float strength) {
        shakeTime = 0.3f;
        shakeAmount = Math.max(shakeAmount, strength * 3f);
    }

    public void flash(int color) {
        flashColor = color;
        flashTime = 0.28f;
    }

    public void update(float dt, AdvGame g) {
        time += dt;
        for (int i = 0; i < pCount; ) {
            pLife[i] -= dt;
            if (pLife[i] <= 0) {
                int last = --pCount;
                pX[i] = pX[last]; pY[i] = pY[last]; pVX[i] = pVX[last]; pVY[i] = pVY[last];
                pLife[i] = pLife[last]; pMax[i] = pMax[last]; pColor[i] = pColor[last];
                continue;
            }
            pVY[i] += 260 * dt;
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            i++;
        }
        for (int i = 0; i < DUST; i++) {
            dX[i] -= (4 + dDepth[i] * 6) * dt;
            dY[i] -= (2 + dDepth[i] * 3) * dt;
            if (dY[i] < -4) dY[i] = H + 4;
            if (dX[i] < 0) dX[i] += viewW * 2;
        }
        if (shakeTime > 0) {
            shakeTime -= dt;
            if (shakeTime <= 0) shakeAmount = 0;
        }
        if (flashTime > 0) flashTime -= dt;

        // after-images when moving fast in a higher form (or always when flying)
        AdvGame.Hero h = g.hero;
        tTimer -= dt;
        boolean fast = Math.abs(h.vx) > 90 || Math.abs(h.vy) > 200;
        if (tTimer <= 0 && fast && (g.form >= 1 || h.flying)) {
            tTimer = 0.045f;
            tX[tHead] = h.x - 5;
            tY[tHead] = h.y - 4;
            tFrame[tHead] = heroFrame(g);
            tFlip[tHead] = h.facingLeft;
            tHead = (tHead + 1) % TRAIL;
            tCount = Math.min(TRAIL, tCount + 1);
        } else if (!fast && tTimer <= -0.15f) {
            tCount = Math.max(0, tCount - 1);
            tTimer = 0;
        }
    }

    // ------------------------------------------------------------------ frame

    public void draw(AdvGame g, boolean depth) {
        if (g.inArena()) {
            drawSpace(g.stage, 0.5f + (float) Math.sin(time * 0.05f) * 0.45f);
            drawArena(g, depth);
        } else {
            float range = g.map.widthPx() - viewW;
            drawSpace(g.stage, range > 0 ? g.camX / range : 0.5f);
            drawLevel(g, depth);
        }
        if (flashTime > 0) {
            fill.setColor(FxLayer.withAlpha(flashColor, (int) (170 * flashTime / 0.28f)));
            bc.drawRect(0, 0, viewW, H, fill);
        }
        bc.drawBitmap(vignette, 0, 0, px);
    }

    /** The animated space background, scrolled by how far through the level the camera is. */
    private void drawSpace(int stage, float scroll) {
        int frame = ((int) (time / 0.15f)) % bgFrames;
        float k = Math.max(H * 1.12f / bgFrameH, viewW * 1.22f / bgFrameW);
        float w = bgFrameW * k, h = bgFrameH * k;
        float x = -(w - viewW) * Math.max(0, Math.min(1, scroll));
        float y = -(h - H) / 2f + (float) Math.sin(time * 0.3f) * 2f;
        src.set(0, frame * bgFrameH, bgFrameW, frame * bgFrameH + bgFrameH);
        dst.set(x, y, x + w, y + h);
        bc.drawBitmap(bgSheet, src, dst, smooth);
        if (STAGE_TINT[stage] != 0) {
            fill.setColor(STAGE_TINT[stage]);
            bc.drawRect(0, 0, viewW, H, fill);
        }
    }

    private void drawLayer(Bitmap layer, float offset, float dy, Paint p) {
        int w = layer.getWidth();
        float x = -(((offset % w) + w) % w);
        bc.drawBitmap(layer, x, dy, p);
        bc.drawBitmap(layer, x + w, dy, p);
    }

    private void drawDust(float camX, boolean front) {
        for (int i = 0; i < DUST; i++) {
            boolean isFront = dDepth[i] > 1f;
            if (isFront != front) continue;
            float wrap = viewW * 2;
            float x = ((dX[i] - camX * dDepth[i]) % wrap + wrap) % wrap - viewW * 0.5f;
            if (x < -2 || x > viewW + 2) continue;
            int a = front ? 150 : dDepth[i] < 0.5f ? 90 : 140;
            float s = front ? 2 : 1;
            fill.setColor(Color.argb(a, 200, 190, 255));
            bc.drawRect(x, dY[i], x + s, dY[i] + s, fill);
        }
    }

    // ------------------------------------------------------------------ level

    private int tileIndex(AdvMap m, int stage, int c, int r) {
        char ch = m.get(c, r);
        switch (ch) {
            case AdvMap.GROUND:
                if (stage == 2) return 7;
                return m.isSolid(c, r - 1) ? 1 : 0;
            case AdvMap.BRICK: return 2;
            case AdvMap.POWER: case AdvMap.POWER_HEART: return 3;
            case AdvMap.USED: return 4;
            case AdvMap.CLOUD: return 5;
            case AdvMap.SPIKES: return 6;
            default: return -1;
        }
    }

    private void drawLevel(AdvGame g, boolean depth) {
        AdvMap m = g.map;
        int cam = Math.round(g.camX);

        // depth layers behind the level
        drawLayer(asteroids, g.camX * 0.18f, 0, px);
        if (depth) {
            drawLayer(islands, g.camX * 0.45f, 10, px);
            fill.setColor(0x22101030);           // haze between the islands and the level
            bc.drawRect(0, 0, viewW, H, fill);
        }
        drawDust(g.camX, false);
        // dim everything behind the level so the playfield (tiles, monsters, hero) reads clearly
        fill.setColor(Color.argb(AMBIENT_DARK[g.stage], 6, 4, 24));
        bc.drawRect(0, 0, viewW, H, fill);

        int c0 = Math.max(0, cam / T - 1), c1 = Math.min(m.cols - 1, (cam + viewW) / T + 1);
        bc.save();
        bc.translate(-cam, 0);

        if (depth) drawBlockFaces(g, m, c0, c1);
        for (int r = 0; r < m.rows; r++) {
            for (int c = c0; c <= c1; c++) {
                int idx = tileIndex(m, g.stage, c, r);
                if (idx < 0) continue;
                src.set(idx * T, 0, idx * T + T, T);
                dst.set(c * T, r * T, c * T + T, r * T + T);
                bc.drawBitmap(tiles, src, dst, px);
                if (depth && idx != 5 && idx != 6 && !m.isSolid(c, r - 1)) {   // rim light on the front edge
                    fill.setColor(0x55FFFFFF);
                    bc.drawRect(c * T, r * T, c * T + T, r * T + 1, fill);
                }
            }
        }

        drawGate(g);

        if (depth) {
            shadow(m, g.hero.cx(), g.hero.y + g.hero.h, g.hero.w + 4);
            for (AdvGame.Monster mo : g.monsters) {
                if (mo.alive && visible(mo.x, cam)) shadow(m, mo.cx(), mo.y + mo.h, mo.w);
            }
        }

        for (AdvGame.Pickup k : g.pickups) {
            if (k.taken || !visible(k.x, cam)) continue;
            float bob = (float) Math.sin(time * 4 + k.x * 0.1f) * 1.5f;
            if (k.kind == AdvGame.Pickup.ORB) drawOrb(k.x, k.y + bob, 0xFF2CE5D8);
            else bc.drawBitmap(heart, Math.round(k.x - 3), Math.round(k.y - 3 + bob), px);
        }

        for (AdvGame.Monster mo : g.monsters) {
            if (!mo.alive || !visible(mo.x, cam)) continue;
            Bitmap sheet = mo.type == AdvGame.Monster.FLIT ? flit : mo.type == AdvGame.Monster.SPIKE ? spike : blob;
            int fw = sheet.getWidth() / 2, fh = sheet.getHeight();
            int frame = ((int) (mo.anim * (mo.type == AdvGame.Monster.FLIT ? 9 : 4))) % 2;
            float sx = mo.cx() - fw / 2f, sy = mo.y + mo.h - fh + (mo.type == AdvGame.Monster.FLIT ? 2 : 1);
            drawFrame(sheet, frame, fw, fh, sx, sy, mo.type == AdvGame.Monster.BLOB && !mo.dirLeft,
                    mo.flash > 0 ? flashPaint : px);
        }

        drawTrail(g);
        drawShots(g);
        drawHero(g);
        drawParticles(false);
        bc.restore();

        // a light touch of shade over the playfield, then the glow lights on top
        fill.setColor(Color.argb(18, 6, 4, 24));
        bc.drawRect(0, 0, viewW, H, fill);
        drawLevelLights(g, cam, c0, c1);

        if (depth) {
            drawDust(g.camX, true);
            drawLayer(spires, g.camX * 1.35f, 0, px);
        }
    }

    /** 2.5D: every exposed block gets a lit top face and a shaded side face, two tones each. */
    private void drawBlockFaces(AdvGame g, AdvMap m, int c0, int c1) {
        for (int r = 0; r < m.rows; r++) {
            for (int c = c0; c <= c1; c++) {
                int idx = tileIndex(m, g.stage, c, r);
                if (idx < 0 || TOP_FACE[idx] == 0) continue;
                float x = c * T, y = r * T;
                if (!m.isSolid(c, r - 1)) {
                    quad(x, y, x + T, y, x + T + DX * 0.5f, y + DY * 0.5f, x + DX * 0.5f, y + DY * 0.5f, TOP_FACE[idx]);
                    quad(x + DX * 0.5f, y + DY * 0.5f, x + T + DX * 0.5f, y + DY * 0.5f, x + T + DX, y + DY, x + DX, y + DY,
                            TOP_FACE_BACK[idx]);
                }
                if (!m.isSolid(c + 1, r)) {
                    quad(x + T, y, x + T + DX * 0.5f, y + DY * 0.5f, x + T + DX * 0.5f, y + T + DY * 0.5f, x + T, y + T, SIDE_FACE[idx]);
                    quad(x + T + DX * 0.5f, y + DY * 0.5f, x + T + DX, y + DY, x + T + DX, y + T + DY, x + T + DX * 0.5f,
                            y + T + DY * 0.5f, SIDE_FACE_BACK[idx]);
                }
            }
        }
    }

    private void drawLevelLights(AdvGame g, int cam, int c0, int c1) {
        AdvGame.Hero h = g.hero;
        int fc = AdvGame.FORM_COLORS[g.form];
        light(h.cx() - cam, h.cy(), 30 + (float) Math.sin(time * 5) * 2, fc, 120);
        for (AdvGame.Shot s : g.shots) light(s.x - cam, s.y, 16 + s.r * 2, s.color, 200);
        for (AdvGame.Pickup k : g.pickups) {
            if (!k.taken && visible(k.x, cam)) light(k.x - cam, k.y, 11, k.kind == AdvGame.Pickup.ORB ? 0xFF2CE5D8 : 0xFFFF5F8A, 140);
        }
        AdvMap m = g.map;
        for (int r = 0; r < m.rows; r++) {
            for (int c = c0; c <= c1; c++) {
                char ch = m.get(c, r);
                if (ch == AdvMap.POWER || ch == AdvMap.POWER_HEART) light(c * T + T / 2f - cam, r * T + T / 2f, 14, 0xFFFFC23C, 70);
                else if (ch == AdvMap.SPIKES) light(c * T + T / 2f - cam, r * T + 12, 9, 0xFF7FE6FF, 60);
            }
        }
        float pulse = (float) Math.sin(time * 4) * 0.5f + 0.5f;
        light(g.gateX + T / 2f - cam, g.gateY - 18, 34 + pulse * 6, 0xFFFF5FD2, 150);
        for (AdvGame.Monster mo : g.monsters) {
            if (mo.alive && visible(mo.x, cam)) light(mo.cx() - cam, mo.cy(), 10, 0xFFE0408F, 50);
        }
        if (particlesInArena) return;
        for (int i = 0; i < pCount; i += 3) light(pX[i] - cam, pY[i], 5, pColor[i], (int) (90 * pLife[i] / pMax[i]));
    }

    /** Additive glow: brightens whatever is underneath, in the light's color. */
    private void light(float x, float y, float radius, int color, int alpha) {
        if (x < -radius || x > viewW + radius || alpha <= 0) return;
        LightingColorFilter f = tint.get(color);
        if (f == null) {
            f = new LightingColorFilter(color | 0xFF000000, 0);
            tint.put(color, f);
        }
        addPaint.setColorFilter(f);
        addPaint.setAlpha(Math.min(255, alpha));
        dst.set(x - radius, y - radius, x + radius, y + radius);
        bc.drawBitmap(glow, null, dst, addPaint);
    }

    private boolean visible(float x, int cam) {
        return x > cam - 40 && x < cam + viewW + 40;
    }

    private void quad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int color) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.close();
        fill.setColor(color);
        bc.drawPath(path, fill);
    }

    /** Soft ellipse on the first floor below a point; smaller and fainter the higher the thing is. */
    private void shadow(AdvMap m, float x, float footY, float width) {
        int col = (int) Math.floor(x / T);
        int row = (int) Math.floor(footY / T);
        for (int r = Math.max(0, row); r < Math.min(m.rows, row + 8); r++) {
            if (m.isSolid(col, r) || m.isOneWay(col, r)) {
                float gy = r * T;
                float k = Math.max(0.2f, 1f - Math.max(0, gy - footY) / 90f);
                fill.setColor(Color.argb((int) (110 * k), 0, 0, 10));
                dst.set(x - width * 0.6f * k + DX * 0.5f, gy - 2 + DY * 0.5f, x + width * 0.6f * k + DX * 0.5f, gy + 2 + DY * 0.5f);
                bc.drawOval(dst, fill);
                return;
            }
        }
    }

    private void drawGate(AdvGame g) {
        float x = g.gateX, bottom = g.gateY;
        float pulse = (float) Math.sin(time * 4) * 0.5f + 0.5f;
        fill.setColor(0xFF1A1430);
        bc.drawRect(x - 4, bottom - 40, x + T + 4, bottom, fill);
        fill.setColor(0xFF5A3F9A);
        bc.drawRect(x - 3, bottom - 39, x + T + 3, bottom, fill);
        fill.setColor(Color.rgb(120 + (int) (100 * pulse), 30, 150));
        bc.drawRect(x, bottom - 36, x + T, bottom, fill);
        fill.setColor(0xFFFF5FD2);
        for (int i = 0; i < 5; i++) {
            float yy = bottom - 34 + ((time * 22 + i * 7) % 34);
            bc.drawRect(x + 3, yy, x + T - 3, yy + 1, fill);
        }
        fill.setColor(0xFFFFC23C);
        bc.drawRect(x + 7, bottom - 54, x + 9, bottom - 47, fill);
        bc.drawRect(x + 7, bottom - 45, x + 9, bottom - 43, fill);
    }

    private void drawOrb(float x, float y, int color) {
        fill.setColor(color);
        bc.drawRect(x - 2, y - 3, x + 2, y + 3, fill);
        bc.drawRect(x - 3, y - 2, x + 3, y + 2, fill);
        fill.setColor(0xFFFFFFFF);
        bc.drawRect(x - 1, y - 2, x, y - 1, fill);
    }

    private void drawFrame(Bitmap sheet, int frame, int fw, int fh, float x, float y, boolean flip, Paint p) {
        src.set(frame * fw, 0, frame * fw + fw, fh);
        int ix = Math.round(x), iy = Math.round(y);
        dst.set(ix, iy, ix + fw, iy + fh);
        if (flip) {
            bc.save();
            bc.scale(-1, 1, ix + fw / 2f, 0);
            bc.drawBitmap(sheet, src, dst, p);
            bc.restore();
        } else {
            bc.drawBitmap(sheet, src, dst, p);
        }
    }

    private int heroFrame(AdvGame g) {
        AdvGame.Hero h = g.hero;
        if (g.state == AdvGame.State.GAME_OVER || h.hurtT > 0) return 4;
        if (h.flying) return 0;
        if (!h.onGround) return 3;
        if (Math.abs(h.vx) > 12) return 1 + ((int) (h.anim * 10)) % 2;
        return 0;
    }

    private void drawTrail(AdvGame g) {
        if (tCount == 0) return;
        if (trailTint[g.form] == null) trailTint[g.form] = new LightingColorFilter(0, AdvGame.FORM_COLORS[g.form] & 0xFFFFFF);
        ghostPaint.setColorFilter(trailTint[g.form]);
        Bitmap sheet = heroSheets[g.form];
        for (int k = 0; k < tCount; k++) {
            int i = (tHead - 1 - k + TRAIL * 2) % TRAIL;
            ghostPaint.setAlpha(110 - k * 20);
            drawFrame(sheet, tFrame[i], 20, 28, tX[i], tY[i], tFlip[i], ghostPaint);
        }
    }

    private void drawHero(AdvGame g) {
        AdvGame.Hero h = g.hero;
        if (h.invuln > 0 && g.state != AdvGame.State.GAME_OVER && ((int) (h.invuln * 14)) % 2 == 0) return;   // blink
        Bitmap sheet = heroSheets[g.form];
        int frame = heroFrame(g);
        float sx = h.x - 5, sy = h.y - 4;
        if (h.flying) {
            float bob = (float) Math.sin(h.anim * 5) * 1.2f;
            sy += bob;
            drawFrame(sheet, frame, 20, 28, sx, sy, h.facingLeft, px);
            bc.drawBitmap(cloud, Math.round(h.cx() - 16), Math.round(h.y + h.h - 5 + bob), px);
            float cx = h.coreX(), cy = h.coreY() + bob;      // the only part bullets can hit
            fill.setColor(FxLayer.withAlpha(AdvGame.FORM_COLORS[g.form], 220));
            bc.drawCircle(cx, cy, 3.5f, fill);
            fill.setColor(0xFFFFFFFF);
            bc.drawRect(cx - 1.5f, cy - 1.5f, cx + 1.5f, cy + 1.5f, fill);
        } else {
            float st = h.stretch;                            // squash and stretch around the feet
            float feet = h.y + h.h;
            bc.save();
            bc.scale(2f - st, st, h.cx(), feet);
            drawFrame(sheet, frame, 20, 28, sx, sy, h.facingLeft, px);
            bc.restore();
        }
    }

    private void drawShots(AdvGame g) {
        for (AdvGame.Shot s : g.shots) {
            float tx = s.x - s.vx * 0.025f, ty = s.y - s.vy * 0.025f;   // short tail behind the blast
            fill.setColor(FxLayer.withAlpha(s.color, 120));
            bc.drawCircle(tx, ty, s.r * 0.8f, fill);
            fill.setColor(s.color);
            bc.drawCircle(s.x, s.y, s.r + 0.5f, fill);
            fill.setColor(0xFFFFFFFF);
            bc.drawCircle(s.x, s.y, Math.max(1f, s.r - 1.5f), fill);
        }
    }

    private void drawParticles(boolean arena) {
        if (arena != particlesInArena) return;
        for (int i = 0; i < pCount; i++) {
            float a = pLife[i] / pMax[i];
            fill.setColor(FxLayer.withAlpha(pColor[i], (int) (255 * a)));
            float s = a > 0.5f ? 2 : 1;
            bc.drawRect(pX[i] - s / 2, pY[i] - s / 2, pX[i] + s / 2, pY[i] + s / 2, fill);
        }
    }

    // ------------------------------------------------------------------ boss arena

    private void drawArena(AdvGame g, boolean depth) {
        float floor = AdvGame.FLOOR_Y;
        drawLayer(asteroids, time * 8, 0, px);
        drawDust(time * 20, false);
        fill.setColor(Color.argb(AMBIENT_DARK[g.stage] + 35, 6, 4, 24));   // background only: bullets must pop
        bc.drawRect(0, 0, viewW, H, fill);
        if (depth) {
            // perspective floor: lines run to a vanishing point, rows get closer together with distance
            float vx = viewW / 2f;
            fill.setColor(0xF0120E2A);
            bc.drawRect(0, floor, viewW, H, fill);
            fill.setColor(FxLayer.withAlpha(AdvGame.FORM_COLORS[g.form], 110));
            for (int i = -14; i <= 14; i++) {
                float bx = vx + i * 44;
                line(vx + (bx - vx) * 0.45f, floor, bx, H);
            }
            for (int k = 0; k < 5; k++) {
                float t = ((k + (time * 1.5f) % 1f) / 5f);
                line(0, floor + (H - floor) * t * t, viewW, floor + (H - floor) * t * t);
            }
            fill.setColor(0xFFC9B6FF);
            bc.drawRect(0, floor - 1, viewW, floor, fill);
        } else {
            for (float y = floor; y < H; y += T) {
                for (int c = 0; c * T < viewW; c++) {
                    src.set(7 * T, 0, 8 * T, T);
                    dst.set(c * T, y, c * T + T, y + T);
                    bc.drawBitmap(tiles, src, dst, px);
                }
            }
        }
        fill.setColor(0x55FF5FD2);
        bc.drawRect(0, 0, viewW, 2, fill);
        bc.drawRect(0, 0, 2, floor, fill);
        bc.drawRect(viewW - 2, 0, viewW, floor, fill);

        AdvBoss b = g.boss;
        if (depth && b != null) {
            groundShadow(g.hero.cx(), floor, g.hero.y + g.hero.h, 22);
            if (!b.isDefeated() || g.stateT < 2f) groundShadow(b.x, floor, b.y + b.h / 2f, b.w);
        }

        if (b != null) {
            for (AdvBoss.Beam bm : b.beams) {
                if (bm.isFiring()) {
                    fill.setColor(0xFFFF5FD2);
                    beamRect(bm, bm.half + 2);
                    fill.setColor(0xFFFFFFFF);
                    beamRect(bm, bm.half - 2);
                } else {
                    boolean on = ((int) (bm.t * 16)) % 2 == 0;
                    fill.setColor(on ? 0xCCFF5FD2 : 0x66FF5FD2);
                    beamRect(bm, 0.6f + bm.warnProgress() * 1.2f);
                }
            }
            boolean defeated = b.isDefeated();
            if (!defeated || g.stateT < 2.0f) {
                Bitmap art = bosses[b.type];
                float shakeX = defeated ? (rnd.nextFloat() - 0.5f) * 4 : 0;
                Paint p = b.flash > 0 || (defeated && ((int) (g.stateT * 12)) % 2 == 0) ? flashPaint : px;
                if (defeated && p == px) {
                    fadePaint.setAlpha((int) (255 * Math.max(0, 1 - g.stateT / 2f)));
                    p = fadePaint;
                }
                float bob = (float) Math.sin(b.anim * 2.2f) * 1.5f;
                bc.drawBitmap(art, Math.round(b.x - 32 + shakeX), Math.round(b.y - 32 - (64 - b.h) / 2f + 4 + bob), p);
            }
            for (AdvBoss.Bullet bl : b.bullets) drawBullet(bl);
        }

        drawTrail(g);
        drawShots(g);
        drawHero(g);
        drawParticles(true);

        AdvGame.Hero h = g.hero;
        light(h.cx(), h.cy(), 30, AdvGame.FORM_COLORS[g.form], 130);
        for (AdvGame.Shot s : g.shots) light(s.x, s.y, 14 + s.r * 2, s.color, 200);
        if (b != null && (!b.isDefeated() || g.stateT < 2f)) {
            float pulse = (float) Math.sin(b.anim * 6) * 4;
            light(b.x, b.y, 60 + pulse, b.enraged ? 0xFFFF3060 : 0xFFC99BFF, b.enraged ? 170 : 110);
            for (AdvBoss.Bullet bl : b.bullets) light(bl.x, bl.y, bl.r * 3.2f, bl.color, 150);
            for (AdvBoss.Beam bm : b.beams) {
                if (bm.isFiring()) {
                    addPaint.setColorFilter(null);
                    fill.setColor(0x55FF5FD2);
                    beamRect(bm, bm.half + 10);
                }
            }
        }
        if (particlesInArena) {
            for (int i = 0; i < pCount; i += 3) light(pX[i], pY[i], 5, pColor[i], (int) (90 * pLife[i] / pMax[i]));
        }
        if (depth) drawDust(time * 20, true);
    }

    private void line(float x1, float y1, float x2, float y2) {
        int n = (int) Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= n; i++) {
            float t = n == 0 ? 0 : i / (float) n;
            float lx = x1 + (x2 - x1) * t, ly = y1 + (y2 - y1) * t;
            bc.drawRect(lx, ly, lx + 1, ly + 1, fill);
        }
    }

    private void groundShadow(float x, float floor, float bottom, float width) {
        float k = Math.max(0.25f, 1f - Math.max(0, floor - bottom) / 200f);
        fill.setColor(Color.argb((int) (120 * k), 0, 0, 10));
        dst.set(x - width * 0.6f * k, floor - 3, x + width * 0.6f * k, floor + 3);
        bc.drawOval(dst, fill);
    }

    private void beamRect(AdvBoss.Beam bm, float half) {
        if (bm.horizontal) bc.drawRect(0, bm.pos - half, viewW, bm.pos + half, fill);
        else bc.drawRect(bm.pos - half, 0, bm.pos + half, AdvGame.FLOOR_Y, fill);
    }

    private void drawBullet(AdvBoss.Bullet b) {
        switch (b.kind) {
            case AdvBoss.Bullet.CARD: {
                float ang = (float) Math.toDegrees(Math.atan2(b.vy, b.vx));
                bc.save();
                bc.rotate(ang, b.x, b.y);
                fill.setColor(b.delay > 0.35f ? 0x88FF5FD2 : 0xFFFF5FD2);
                bc.drawRect(b.x - 5, b.y - 3, b.x + 5, b.y + 3, fill);
                fill.setColor(0xFFFFFFFF);
                bc.drawRect(b.x - 4, b.y - 2, b.x + 4, b.y + 2, fill);
                fill.setColor(0xFF1A1430);
                bc.drawRect(b.x - 1, b.y - 1, b.x + 1, b.y + 1, fill);
                bc.restore();
                break;
            }
            case AdvBoss.Bullet.ROCK:
                fill.setColor(0xFF1A1430);
                bc.drawRect(b.x - b.r - 1, b.y - b.r - 1, b.x + b.r + 1, b.y + b.r + 1, fill);
                fill.setColor(b.color);
                bc.drawRect(b.x - b.r, b.y - b.r, b.x + b.r, b.y + b.r, fill);
                fill.setColor(0xFFE8DCC0);
                bc.drawRect(b.x - b.r, b.y - b.r, b.x, b.y - b.r + 2, fill);
                break;
            default:
                fill.setColor(b.color);
                bc.drawCircle(b.x, b.y, b.r, fill);
                fill.setColor(0xFFFFFFFF);
                bc.drawCircle(b.x, b.y, Math.max(1f, b.r - 2f), fill);
                break;
        }
    }

    /** For the HUD: where a world point ends up on the buffer (x only; y is unchanged). */
    public float worldToBufferX(AdvGame g, float x) {
        return g.inArena() ? x : x - Math.round(g.camX);
    }
}
