package com.example.kirunner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

/**
 * ADVENTURE mode screen. Runs the AdvGame simulation on GameThread, renders it through AdvRenderer into a
 * small pixel buffer, scales that up to fill the screen, and draws the HUD and touch controls at full
 * resolution on top.
 *
 * Input: keyboard via handleKey() (from AdventureActivity), mouse buttons and touch via the motion events.
 * Keys are matched by key code first and by physical scan code second, so they work on any keyboard layout.
 */
public class AdventureView extends SurfaceView implements SurfaceHolder.Callback, GameLoop {

    /** AdventureActivity implements this. */
    public interface Host {
        void exitToMenu();

        void saveBest(Difficulty d, int score);

        int best(Difficulty d);
    }

    private static final String TAG = "KiInput";
    private static final int BTN_NONE = -1, BTN_MOUSE = -2;
    private static final int ACT_CONFIRM = 100, ACT_PAUSE = 101, ACT_VIEW = 102, ACT_QUIT = 103, ACT_DEBUG = 104;

    private final AdvInput input = new AdvInput();
    private final AdvInput.Frame frame = new AdvInput.Frame();
    private final Paint upscale = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint heartPaint = new Paint();
    private final RectF r = new RectF();
    private final SparseIntArray pointerButtons = new SparseIntArray();

    private GameThread thread;
    private Host host;
    private SoundManager sound;
    private Difficulty difficulty = Difficulty.EASY;
    private int debugBossStage = -1;
    private AdvGame game;
    private AdvRenderer renderer;
    private volatile boolean paused;
    private boolean depth = true;      // true = 2.5D, false = 2D
    private boolean savedResult;
    private int screenW, screenH;
    private float scale, dp;
    private float bossGhost = 1f;      // trailing "damage" part of the boss bar
    private volatile boolean keyDebug;
    private volatile String lastKey = "-";
    private final KeyDebounce debounce = new KeyDebounce();
    private int seenModeChanges;
    private boolean bossMusic;
    private float keyTipT, realKeysT;

    private final RectF bLeft = new RectF(), bRight = new RectF(), bUp = new RectF(), bDown = new RectF();
    private final RectF bJump = new RectF(), bFire = new RectF(), bPause = new RectF(), bView = new RectF();
    private final RectF bResume = new RectF(), bQuit = new RectF();

    public AdventureView(Context context) {
        this(context, null);
    }

    public AdventureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        dp = getResources().getDisplayMetrics().density;
        upscale.setFilterBitmap(false);
        upscale.setAntiAlias(false);
        Typeface display = ResourcesCompat.getFont(context, R.font.bangers);
        Typeface body = ResourcesCompat.getFont(context, R.font.rajdhani_bold);
        text.setTypeface(display);
        text.setTextAlign(Paint.Align.CENTER);
        textStroke.setTypeface(display);
        textStroke.setTextAlign(Paint.Align.CENTER);
        textStroke.setStyle(Paint.Style.STROKE);
        textStroke.setStrokeJoin(Paint.Join.ROUND);
        textStroke.setColor(0xFF1A1430);
        small.setTypeface(body);
        border.setStyle(Paint.Style.STROKE);
        heartPaint.setFilterBitmap(false);
        // a hardware keyboard is attached (PC emulator, tablet keyboard): start with the touch buttons hidden
        input.setUsingKeyboard(getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY);
    }

    public void setup(Host host, SoundManager sound, Difficulty d, int debugBossStage) {
        this.host = host;
        this.sound = sound;
        this.difficulty = d;
        this.debugBossStage = debugBossStage;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new GameThread(holder, this);
        thread.setRunning(true);
        thread.start();
        requestFocus();
    }

    @Override
    public synchronized void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        screenW = width;
        screenH = height;
        scale = height / (float) AdvGame.VIEW_H;
        int viewW = (int) Math.ceil(width / scale);
        if (game == null || renderer == null || renderer.viewW != viewW) {
            renderer = new AdvRenderer(getResources(), viewW);
            game = new AdvGame(difficulty, viewW);
            game.setListener(new AdvGame.Listener() {
                @Override
                public void sound(int id) {
                    if (sound != null) sound.playAdventure(id);
                }

                @Override
                public void burst(float x, float y, int color, int count) {
                    renderer.burst(x, y, color, count, game.inArena());
                }

                @Override
                public void shake(float strength) {
                    renderer.shake(strength);
                }

                @Override
                public void flash(int color) {
                    renderer.flash(color);
                }
            });
            if (debugBossStage >= 0) game.debugStartAtBoss(debugBossStage);
        }
        layoutButtons();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (thread == null) return;
        thread.setRunning(false);
        boolean retry = true;
        while (retry) {
            try {
                thread.join();
                retry = false;
            } catch (InterruptedException ignored) {
                // try again
            }
        }
        thread = null;
    }

    private void layoutButtons() {
        float u = Math.min(screenH * 0.12f, 60 * dp);
        float pad = 18 * dp;
        float by = screenH - pad - u;
        bLeft.set(pad, by - u * 0.5f, pad + u, by + u * 0.5f);
        bRight.set(pad + u * 2.1f, by - u * 0.5f, pad + u * 3.1f, by + u * 0.5f);
        bUp.set(pad + u * 1.05f, by - u * 1.55f, pad + u * 2.05f, by - u * 0.55f);
        bDown.set(pad + u * 1.05f, by + u * 0.05f, pad + u * 2.05f, by + u * 1.0f);
        bFire.set(screenW - pad - u * 1.15f, by - u * 0.6f, screenW - pad, by + u * 0.55f);
        bJump.set(screenW - pad - u * 2.5f, by - u * 0.15f, screenW - pad - u * 1.35f, by + u * 1.0f);
        float t = 10 * dp, s = 40 * dp;
        bPause.set(screenW - t - s, t, screenW - t, t + s);
        bView.set(screenW - t * 2 - s * 2.4f, t, screenW - t * 2 - s, t + s);
        float cw = 160 * dp, ch = 46 * dp;
        bResume.set(screenW / 2f - cw - 8 * dp, screenH * 0.68f, screenW / 2f - 8 * dp, screenH * 0.68f + ch);
        bQuit.set(screenW / 2f + 8 * dp, screenH * 0.68f, screenW / 2f + cw + 8 * dp, screenH * 0.68f + ch);
    }

    public synchronized void pause() {
        if (game != null && game.state != AdvGame.State.GAME_OVER && game.state != AdvGame.State.VICTORY) paused = true;
        input.releaseAll();
    }

    @Override
    public void reportFps(float fps) {
        // not shown in ADVENTURE mode
    }

    // ------------------------------------------------------------------ update

    @Override
    public synchronized void update(float dt) {
        if (game == null) return;
        input.setHeroState(!game.hero.onGround && !game.hero.flying, game.hero.flying);
        input.snapshot(frame, SystemClock.uptimeMillis());
        if (frame.viewPressed) {
            depth = !depth;
            game.banner(depth ? "2.5D VIEW" : "2D VIEW", "", 0xFFFFC23C, 0.8f);
        }
        boolean ended = game.state == AdvGame.State.GAME_OVER || game.state == AdvGame.State.VICTORY;
        if (frame.pausePressed) {
            if (ended) {
                exit();
                return;
            }
            paused = !paused;
        }
        if (paused) {
            if (frame.confirmPressed) paused = false;
            return;
        }
        if (game.state == AdvGame.State.VICTORY) {
            saveResultOnce();
            if (game.stateT > 1.2f && (frame.confirmPressed || frame.firePressed)) {
                exit();
                return;
            }
        } else if (game.state == AdvGame.State.GAME_OVER) {
            saveResultOnce();
            if (game.stateT > 1.0f && frame.confirmPressed) savedResult = false;
        }
        game.update(dt, frame);
        renderer.update(dt, game);
        // boss fights get their own, heavier track
        boolean wantBoss = game.inArena() && game.state != AdvGame.State.STAGE_CLEAR;
        if (wantBoss != bossMusic && sound != null) {
            bossMusic = wantBoss;
            final int res = wantBoss ? R.raw.music_boss : R.raw.music_level;
            post(() -> sound.setTrack(res));
        }
        if (game.boss != null) {
            float target = game.boss.hp / (float) game.boss.maxHp;
            bossGhost = bossGhost > target ? Math.max(target, bossGhost - dt * 0.35f) : target;
        } else {
            bossGhost = 1f;
        }
    }

    private void saveResultOnce() {
        if (savedResult || host == null) return;
        savedResult = true;
        host.saveBest(difficulty, game.score);
    }

    private void exit() {
        saveResultOnce();
        if (host != null) post(() -> host.exitToMenu());
    }

    // ------------------------------------------------------------------ render

    @Override
    public synchronized void render(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (game == null) return;
        renderer.draw(game, depth);
        float sx = 0, sy = 0;
        if (renderer.shakeTime > 0) {
            sx = (float) (Math.random() - 0.5) * renderer.shakeAmount * scale;
            sy = (float) (Math.random() - 0.5) * renderer.shakeAmount * scale;
        }
        Bitmap buf = renderer.buffer;
        r.set(sx, sy, sx + buf.getWidth() * scale, sy + buf.getHeight() * scale);
        canvas.drawBitmap(buf, null, r, upscale);
        drawPopups(canvas);
        drawCinematics(canvas);
        drawHud(canvas);
    }

    private void drawPopups(Canvas c) {
        for (AdvGame.Popup p : game.popups) {
            float x = renderer.worldToBufferX(game, p.x) * scale;
            float y = (p.y - p.t * 22) * scale;
            int a = (int) (255 * Math.max(0, Math.min(1, (0.9f - p.t) / 0.35f)));
            bigText(c, p.text, x, y, 17 * dp, p.color, a);
        }
    }

    /** Fades between stages and letterbox bars for the boss entrance. */
    private void drawCinematics(Canvas c) {
        AdvGame g = game;
        float fade = 0;
        if (g.state == AdvGame.State.STAGE_INTRO && g.stateT < 0.5f) fade = 1f - g.stateT / 0.5f;
        if (g.state == AdvGame.State.STAGE_CLEAR && g.stateT > 2.3f) fade = Math.min(1f, (g.stateT - 2.3f) / 0.5f);
        if (fade > 0) {
            box.setColor(Color.argb((int) (255 * fade), 0, 0, 0));
            c.drawRect(0, 0, screenW, screenH, box);
        }
        if (g.state == AdvGame.State.BOSS_INTRO) {
            float k = Math.min(1f, g.stateT / 0.3f) * Math.min(1f, Math.max(0f, (2.3f - g.stateT) / 0.3f));
            float bar = screenH * 0.11f * k;
            box.setColor(0xFF000000);
            c.drawRect(0, 0, screenW, bar, box);
            c.drawRect(0, screenH - bar, screenW, screenH, box);
        }
    }

    private void drawHud(Canvas c) {
        AdvGame g = game;
        float m = 14 * dp;
        int fc = AdvGame.FORM_COLORS[g.form];

        // ---- left panel: hearts, form, ki meter
        Bitmap heart = renderer.heartBitmap();
        float hs = 7 * scale * 0.55f, gap = 4 * dp;
        float panelW = Math.max(g.hero.maxHp * (hs + gap), 150 * dp);
        float fy = m + hs + 20 * dp, barY = fy + 7 * dp;
        panel(c, m - 8 * dp, m - 8 * dp, m + panelW + 8 * dp, barY + 14 * dp, fc);
        boolean low = g.hero.hp == 1;
        for (int i = 0; i < g.hero.maxHp; i++) {
            float pulse = low && i == 0 ? 1f + 0.15f * (float) Math.sin(g.time * 10) : 1f;
            heartPaint.setAlpha(i < g.hero.hp ? 255 : 50);
            float cx = m + i * (hs + gap) + hs / 2f, cy = m + hs / 2f;
            r.set(cx - hs / 2f * pulse, cy - hs / 2f * pulse, cx + hs / 2f * pulse, cy + hs / 2f * pulse);
            c.drawBitmap(heart, null, r, heartPaint);
        }
        small.setTextAlign(Paint.Align.LEFT);
        small.setTextSize(15 * dp);
        small.setColor(fc);
        c.drawText(AdvGame.FORM_NAMES[g.form] + "  ·  KI " + g.ki, m, fy, small);
        box.setColor(0x88000000);
        r.set(m, barY, m + panelW, barY + 6 * dp);
        c.drawRoundRect(r, 3 * dp, 3 * dp, box);
        float prog = 1f;
        if (g.form < AdvGame.KI_THRESHOLDS.length - 1) {
            int from = AdvGame.KI_THRESHOLDS[g.form], to = AdvGame.KI_THRESHOLDS[g.form + 1];
            prog = (g.ki - from) / (float) (to - from);
        }
        box.setColor(fc);
        r.set(m, barY, m + panelW * Math.max(0.02f, Math.min(1, prog)), barY + 6 * dp);
        c.drawRoundRect(r, 3 * dp, 3 * dp, box);

        // ---- score panel
        float sw = 190 * dp;
        panel(c, screenW / 2f - sw / 2, m - 8 * dp, screenW / 2f + sw / 2, m + 42 * dp, 0xFFFFC23C);
        bigText(c, String.format(Locale.US, "%06d", g.score), screenW / 2f, m + 20 * dp, 26 * dp, 0xFFFFC23C, 255);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(11 * dp);
        small.setColor(0xCCFFFFFF);
        c.drawText("STAGE " + (g.stage + 1) + " · " + AdvGame.STAGE_NAMES[g.stage] + " · " + difficulty.name(),
                screenW / 2f, m + 35 * dp, small);

        // ---- boss bar
        if (g.boss != null && g.inArena() && g.state != AdvGame.State.STAGE_CLEAR && g.state != AdvGame.State.BOSS_INTRO) {
            AdvBoss b = g.boss;
            float bw = screenW * 0.44f, bx = screenW / 2f - bw / 2f, byy = m + 64 * dp;
            panel(c, bx - 10 * dp, byy - 16 * dp, bx + bw + 10 * dp, byy + 18 * dp, b.enraged ? 0xFFFF3060 : 0xFFFF5FD2);
            small.setTextSize(12 * dp);
            small.setColor(b.enraged ? 0xFFFF8FA8 : 0xFFFFFFFF);
            c.drawText(AdvBoss.NAMES[b.type] + (b.enraged ? "  ·  ENRAGED" : ""), screenW / 2f, byy - 3 * dp, small);
            box.setColor(0xAA000000);
            c.drawRect(bx, byy + 2 * dp, bx + bw, byy + 11 * dp, box);
            box.setColor(0xCCFFFFFF);
            c.drawRect(bx, byy + 2 * dp, bx + bw * bossGhost, byy + 11 * dp, box);
            box.setColor(b.enraged ? 0xFFFF3060 : 0xFFFF5FD2);
            c.drawRect(bx, byy + 2 * dp, bx + bw * b.hp / (float) b.maxHp, byy + 11 * dp, box);
        }

        pill(c, bView, depth ? "2.5D" : "2D", depth ? 0xFF2CE5D8 : 0xAAFFFFFF);
        pill(c, bPause, "II", 0xFFFFFFFF);

        // ---- banner
        if (g.bannerT > 0 && !g.bannerTitle.isEmpty()) {
            float a = Math.min(1f, g.bannerT / 0.3f);
            bigText(c, g.bannerTitle, screenW / 2f, screenH * 0.42f, 54 * dp, g.bannerColor, (int) (255 * a));
            if (!g.bannerSub.isEmpty()) {
                small.setTextAlign(Paint.Align.CENTER);
                small.setTextSize(18 * dp);
                small.setColor(Color.argb((int) (230 * a), 255, 255, 255));
                c.drawText(g.bannerSub, screenW / 2f, screenH * 0.42f + 30 * dp, small);
            }
        }

        // ---- keyboard mode: tip when only taps arrive, confirmation when real keys are detected
        int changes = input.tapModeChanges();
        if (changes != seenModeChanges) {
            seenModeChanges = changes;
            if (input.isTapMode()) keyTipT = 9f;
            else realKeysT = 2.5f;
        }
        if (keyTipT > 0 && input.isTapMode() && !g.inArena()) {
            keyTipT -= 1f / 60f;
            float tw = Math.min(screenW - 40 * dp, 640 * dp), tx = screenW / 2f - tw / 2f, ty = screenH * 0.70f;
            panel(c, tx, ty, tx + tw, ty + 52 * dp, 0xFFFFC23C);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(14 * dp);
            small.setColor(0xFFFFE07A);
            c.drawText("The emulator is sending keys as taps, so holding and letting go can't be exact.",
                    screenW / 2f, ty + 21 * dp, small);
            small.setColor(0xFFFFFFFF);
            c.drawText("Turn on  HARDWARE INPUT  (keyboard icon in the Running Devices toolbar, next to Snapshots)",
                    screenW / 2f, ty + 41 * dp, small);
        } else if (!input.isTapMode()) {
            keyTipT = 0;
        }
        if (realKeysT > 0) {
            realKeysT -= 1f / 60f;
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(16 * dp);
            small.setColor(0xFF7CFFB0);
            c.drawText("Real keyboard detected: controls are exact now", screenW / 2f, screenH * 0.74f, small);
        }

        // ---- first-stage controls reminder
        if (g.stage == 0 && g.time < 8f && g.state == AdvGame.State.PLAY) {
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(14 * dp);
            small.setColor(Color.argb((int) (220 * Math.min(1f, (8f - g.time) / 1f)), 255, 255, 255));
            c.drawText("A / D or ← → move   ·   Space / W jump (again in the air = double jump)   ·   Right-click: ki blast",
                    screenW / 2f, screenH - 16 * dp, small);
        }

        if (g.state == AdvGame.State.GAME_OVER && g.stateT > 1f) {
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(14 * dp);
            small.setColor(0xCCFFFFFF);
            c.drawText("Esc / P: back to menu", screenW / 2f, screenH * 0.42f + 54 * dp, small);
        }
        if (g.state == AdvGame.State.VICTORY) drawVictory(c);
        if (!input.isUsingKeyboard() && !paused && g.state != AdvGame.State.VICTORY && g.state != AdvGame.State.GAME_OVER) {
            drawTouchControls(c, g.inArena());
        }
        if (paused) drawPause(c);
        if (keyDebug) {
            small.setTextAlign(Paint.Align.LEFT);
            small.setTextSize(13 * dp);
            small.setColor(0xFFFFFF00);
            c.drawText("last key: " + lastKey + "   (F9 hides this)", m, screenH - 10 * dp, small);
        }
    }

    private void panel(Canvas c, float l, float t, float rr, float b, int accent) {
        r.set(l, t, rr, b);
        box.setColor(0x99100C28);
        c.drawRoundRect(r, 10 * dp, 10 * dp, box);
        border.setStrokeWidth(1.5f * dp);
        border.setColor(FxLayer.withAlpha(accent, 150));
        c.drawRoundRect(r, 10 * dp, 10 * dp, border);
    }

    private void bigText(Canvas c, String s, float x, float y, float size, int color, int alpha) {
        text.setTextSize(size);
        textStroke.setTextSize(size);
        textStroke.setStrokeWidth(size * 0.12f);
        textStroke.setAlpha(alpha);
        text.setColor(color);
        text.setAlpha(alpha);
        c.drawText(s, x, y, textStroke);
        c.drawText(s, x, y, text);
    }

    private void pill(Canvas c, RectF b, String label, int color) {
        box.setColor(0x99100C28);
        c.drawRoundRect(b, b.height() / 2, b.height() / 2, box);
        border.setStrokeWidth(1.5f * dp);
        border.setColor(FxLayer.withAlpha(color, 140));
        c.drawRoundRect(b, b.height() / 2, b.height() / 2, border);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(b.height() * 0.42f);
        small.setColor(color);
        c.drawText(label, b.centerX(), b.centerY() + b.height() * 0.15f, small);
    }

    private void drawTouchControls(Canvas c, boolean arena) {
        circle(c, bLeft, "◀");
        circle(c, bRight, "▶");
        if (arena) {
            circle(c, bUp, "▲");
            circle(c, bDown, "▼");
        }
        circle(c, bJump, arena ? "▲" : "JUMP");
        circle(c, bFire, "KI");
    }

    private void circle(Canvas c, RectF b, String label) {
        box.setColor(0x2EFFFFFF);
        c.drawOval(b, box);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(b.height() * (label.length() > 2 ? 0.26f : 0.4f));
        small.setColor(0xB3FFFFFF);
        c.drawText(label, b.centerX(), b.centerY() + b.height() * 0.13f, small);
    }

    private void drawPause(Canvas c) {
        box.setColor(0xB30A0820);
        c.drawRect(0, 0, screenW, screenH, box);
        bigText(c, "PAUSED", screenW / 2f, screenH * 0.3f, 60 * dp, 0xFF2CE5D8, 255);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(15 * dp);
        small.setColor(0xFFFFFFFF);
        c.drawText("Move: A / D or ← →   ·   Jump: Space / W / ↑ (again in the air = double jump)", screenW / 2f, screenH * 0.42f, small);
        c.drawText("Ki blast: right mouse button, a left click, or J / K  (hold to auto-fire)", screenW / 2f, screenH * 0.48f, small);
        c.drawText("Boss fights: fly with W A S D or the arrows   ·   V: 2D / 2.5D", screenW / 2f, screenH * 0.54f, small);
        small.setColor(0xFFB9B4E6);
        c.drawText("Enter / P: resume   ·   Q / Back: quit to menu   ·   F9: show keys", screenW / 2f, screenH * 0.61f, small);
        pill(c, bResume, "RESUME", 0xFF2CE5D8);
        pill(c, bQuit, "QUIT", 0xFFFF5F8A);
    }

    private void drawVictory(Canvas c) {
        AdvGame g = game;
        box.setColor(0xB30A0820);
        c.drawRect(0, 0, screenW, screenH, box);
        bigText(c, "YOU WIN!", screenW / 2f, screenH * 0.3f, 70 * dp, 0xFFFFC23C, 255);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(18 * dp);
        small.setColor(0xFFFFFFFF);
        int total = Math.round(g.time);
        c.drawText(String.format(Locale.US, "SCORE %d   ·   BEST %d   ·   TIME %d:%02d   ·   KOs %d   ·   %s",
                g.score, Math.max(g.score, host != null ? host.best(difficulty) : 0), total / 60, total % 60, g.kills,
                difficulty.name()), screenW / 2f, screenH * 0.45f, small);
        small.setColor(0xFFB9B4E6);
        c.drawText("All three bosses defeated. Try a harder difficulty!", screenW / 2f, screenH * 0.53f, small);
        if (g.stateT > 1.2f) {
            small.setColor(0xFF2CE5D8);
            c.drawText("Enter / click: back to menu", screenW / 2f, screenH * 0.65f, small);
        }
    }

    // ------------------------------------------------------------------ keyboard

    /** Key code -> game key. Falls back to the physical scan code (Linux input codes) for other layouts. */
    static int mapKey(int keyCode, int scanCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return AdvInput.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return AdvInput.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return AdvInput.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return AdvInput.DOWN;
            case KeyEvent.KEYCODE_SPACE: return AdvInput.JUMP;
            case KeyEvent.KEYCODE_J: case KeyEvent.KEYCODE_K: return AdvInput.FIRE;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER:
                return ACT_CONFIRM;
            case KeyEvent.KEYCODE_P: case KeyEvent.KEYCODE_ESCAPE: return ACT_PAUSE;
            case KeyEvent.KEYCODE_V: return ACT_VIEW;
            case KeyEvent.KEYCODE_Q: return ACT_QUIT;
            case KeyEvent.KEYCODE_F9: return ACT_DEBUG;
            default: break;
        }
        switch (scanCode) {
            case 105: case 30: return AdvInput.LEFT;     // Left arrow, A
            case 106: case 32: return AdvInput.RIGHT;    // Right arrow, D
            case 103: case 17: return AdvInput.UP;       // Up arrow, W
            case 108: case 31: return AdvInput.DOWN;     // Down arrow, S
            case 57: return AdvInput.JUMP;               // Space
            case 36: case 37: return AdvInput.FIRE;      // J, K
            case 28: case 96: return ACT_CONFIRM;        // Enter
            case 25: case 1: return ACT_PAUSE;           // P, Esc
            case 47: return ACT_VIEW;                    // V
            case 16: return ACT_QUIT;                    // Q
            default: return BTN_NONE;
        }
    }

    /** Keyboard events from AdventureActivity. Returns true if the key was used. */
    public boolean handleKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getRepeatCount() == 0) {
            lastKey = KeyEvent.keyCodeToString(e.getKeyCode()) + (down ? " down" : " up after "
                    + (e.getEventTime() - e.getDownTime()) + "ms") + "  scan " + e.getScanCode()
                    + "  src 0x" + Integer.toHexString(e.getSource());
            Log.d(TAG, lastKey);
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // a right-click can arrive as BACK from the mouse: treat it as a ki blast
            if ((e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                input.setMouse(AdvInput.FIRE, down);
                return true;
            }
            if (!down) {   // act on release, so the menu underneath never sees a stray Back
                if (paused) exit();
                else input.pressPause();
            }
            return true;
        }
        int k = mapKey(e.getKeyCode(), e.getScanCode());
        if (k == BTN_NONE) return false;
        if (down && e.getRepeatCount() > 0) return true;     // held key: its state is already "down"
        // menu-type keys: a held key (a stream of taps) acts once, not 30 times a second
        boolean act = down && (k < ACT_CONFIRM || debounce.accept(e.getKeyCode(), e.getEventTime(), 0));
        switch (k) {
            case ACT_CONFIRM: if (act) input.pressConfirm(); return true;
            case ACT_PAUSE: if (act) input.pressPause(); return true;
            case ACT_VIEW: if (act) input.pressView(); return true;
            case ACT_QUIT: if (act && paused) exit(); return true;
            case ACT_DEBUG: if (act) keyDebug = !keyDebug; return true;
            default:
                input.setKey(k, down, e.getEventTime());
                return true;
        }
    }

    // ------------------------------------------------------------------ mouse + touch

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE)
                && e.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
            input.setMouse(AdvInput.FIRE, a == MotionEvent.ACTION_BUTTON_PRESS);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        boolean isMouse = e.getToolType(idx) == MotionEvent.TOOL_TYPE_MOUSE;
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (e.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                input.setMouse(AdvInput.FIRE, action == MotionEvent.ACTION_BUTTON_PRESS);
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                if (isMouse && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    pointerButtons.put(e.getPointerId(idx), BTN_MOUSE);
                    input.setMouse(AdvInput.FIRE, true);
                    return true;
                }
                if (paused) {
                    if (bQuit.contains(x, y)) exit();
                    else input.pressConfirm();
                    return true;
                }
                if (bPause.contains(x, y)) {
                    input.pressPause();
                    return true;
                }
                if (bView.contains(x, y)) {
                    input.pressView();
                    return true;
                }
                AdvGame g = game;
                if (g != null && (g.state == AdvGame.State.GAME_OVER || g.state == AdvGame.State.VICTORY
                        || g.state == AdvGame.State.STAGE_CLEAR)) {
                    input.pressConfirm();
                    return true;
                }
                if (isMouse || input.isUsingKeyboard()) {
                    // keyboard + mouse players: a click anywhere on the game area is a ki blast
                    pointerButtons.put(e.getPointerId(idx), BTN_MOUSE);
                    input.setMouse(AdvInput.FIRE, true);
                    return true;
                }
                int b = buttonAt(x, y);
                pointerButtons.put(e.getPointerId(idx), b);
                input.setTouch(b, true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int old = pointerButtons.get(id, BTN_NONE);
                    if (old == BTN_MOUSE || old == BTN_NONE) continue;
                    int now = buttonAt(e.getX(i), e.getY(i));
                    if (now != old) {
                        input.setTouch(old, false);
                        input.setTouch(now, true);
                        pointerButtons.put(id, now);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int id = e.getPointerId(idx);
                int old = pointerButtons.get(id, BTN_NONE);
                if (old == BTN_MOUSE) input.setMouse(AdvInput.FIRE, false);
                else if (old != BTN_NONE) input.setTouch(old, false);
                pointerButtons.delete(id);
                if (action != MotionEvent.ACTION_POINTER_UP) {
                    input.releaseAllTouches();
                    pointerButtons.clear();
                }
                if (action == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            default:
                return true;
        }
    }

    /** Touch buttons; anywhere else on the screen fires a ki blast. */
    private int buttonAt(float x, float y) {
        boolean arena = game != null && game.inArena();
        if (grow(bLeft, x, y)) return AdvInput.LEFT;
        if (grow(bRight, x, y)) return AdvInput.RIGHT;
        if (arena && grow(bUp, x, y)) return AdvInput.UP;
        if (arena && grow(bDown, x, y)) return AdvInput.DOWN;
        if (grow(bJump, x, y)) return AdvInput.JUMP;
        return AdvInput.FIRE;
    }

    private boolean grow(RectF b, float x, float y) {
        float g = 10 * dp;
        return x > b.left - g && x < b.right + g && y > b.top - g && y < b.bottom + g;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
