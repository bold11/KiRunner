package com.example.kirunner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * STAGE 3: The game itself. Draws everything, moves and spawns obstacles with fair spacing,
 * detects collisions, tracks the score, and runs transformations, energy blasts, pause, slow-motion
 * and the dev overlay. Everything in the world is drawn through a Projection (camera), so the same game
 * can be shown flat (2D) or as 2.5D (flat sprites standing in a perspective world, with ground shadows).
 * The camera never changes the rules, only the drawing.
 * The rules themselves are in GameRules (unit tested). Methods that touch game state are synchronized
 * because the game thread and the UI thread (buttons, swipes) both call into this class.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, GameLoop {

    /** MainActivity implements this to update the HUD, score box and Game Over panel. */
    public interface GameListener {
        void onScoreChanged(int score);
        void onFormChanged(int formIndex);
        void onGameOver(RunStats stats);
        void onGameRestarted();
    }

    /** Aura / UI color for each transformation: Base, Storm, Jade, Void, Zenith. */
    public static final int[] FORM_COLORS = {
            Color.rgb(44, 229, 216), Color.rgb(170, 130, 255), Color.rgb(90, 235, 150),
            Color.rgb(205, 130, 255), Color.rgb(234, 246, 255)
    };
    private static final int[] FORM_DRAWABLES = {
            R.drawable.player, R.drawable.player_lv2, R.drawable.player_lv3,
            R.drawable.player_lv4, R.drawable.player_lv5
    };
    private static final int GOLD = Color.rgb(255, 194, 60);
    private static final int NAVY = Color.rgb(30, 26, 60);

    // ---- Look & feel tuning (the gameplay rules are in GameRules) ----
    private static final float PLAYER_WIDTH_OF_LANE = 1.0f;   // the fighter fills his lane
    private static final float OBSTACLE_SIZE_OF_LANE = 0.58f;
    private static final float RESTART_LOCKOUT = 0.6f;     // seconds before a restart tap is accepted
    private static final int MILESTONE_EVERY = 10;          // "POWER UP!" every 10 points (when not transforming)
    private static final float BANNER_TIME = 1.1f;
    private static final float POPUP_TIME = 0.8f;
    private static final float HINT_TIME = 3.5f;
    private static final float RESUME_COUNTDOWN = 3f;
    private static final float SLOW_MO_SCALE = 0.35f;       // game speed during slow-motion
    private static final float CLOSE_CALL_SLOW_MO = 0.35f;  // real seconds
    private static final float TRANSFORM_SLOW_MO = 0.30f;
    private static final float CRASH_SLOW_MO = 0.50f;
    // ---- Energy blast ----
    private static final float BLAST_WIDTH_OF_LANE = 0.30f;
    private static final float BLAST_HEIGHT_OF_LANE = 0.42f;
    private static final float MUZZLE_TIME = 0.12f;
    // ---- 2.5D camera ----
    private static final float FAR_SCALE = 0.45f;          // object size at the top of the screen in 3D
    private static final float CAMERA_EASE = 5f;           // how fast the camera flies between 2D and 3D
    private static final float SWAY = 0.35f;               // how much the far end follows the player

    private final Random random = new Random();
    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();
    private final int[] laneOrder = new int[GameRules.LANE_COUNT];

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint laneGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laneCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorPaint = new Paint();
    private final Paint trailPaint = new Paint();
    private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Dev overlay
    private final Paint debugImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugHitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugGrazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugGatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugBgPaint = new Paint();
    // 3D view
    private final Projection camera = new Projection();
    private final Paint obstaclePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enemyShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugBlastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF dst = new RectF();

    private GameThread thread;
    private float difficultySpeed = 1f;   // Classic: EASY / HARD / EXTREME speed multiplier
    private GameListener listener;
    private SoundManager sound;
    private FxLayer fx;

    private Bitmap backgroundSource, obstacleSource;
    private Bitmap[] playerSources;
    private Bitmap background, obstacleBitmap;
    private Player player;
    private String goText, powerUpText, closeText, hintText;
    private String[] formBanners;

    private int viewWidth, viewHeight;
    private float laneWidth;
    private float trailLength, floorGlowHeight, dashLength, dashPeriod, dashOffset;
    private int controlBarTop;      // top edge of the control bar, in this view's coordinates
    private final float swipeThreshold;
    private boolean ready;

    // ---- Game state ----
    private int score;
    private int closeCalls;
    private boolean gameOver;
    private boolean paused;
    private boolean debug;
    private boolean showHint;
    private float countdown;
    private int lastCountShown;
    private float realTime, animTime, runTime, gameOverRealTime;
    private float slowMoTimer;
    private boolean hasSpawned;
    private float spawnTop;          // where the newest obstacle's top would be now (even if it was blasted)
    private float requiredGap;
    private float blastCooldown;
    private float muzzleTimer;
    private int enemiesBlasted;
    private String bannerText;
    private int bannerColor = GOLD;
    private float bannerTimer;
    private String popupText;
    private float popupX, popupY, popupTimer;
    private float hintTimer;
    private volatile float fps;
    private boolean view3d;
    private boolean cameraSnap;
    private float camSway;
    private float gridSpacing, gridOffset, gridWidth, laneGlowWidth, laneCoreWidth;
    private String view3dText, view2dText;

    // ---- Touch (swipe) ----
    private float downX, downY;
    private boolean swipeHandled;

    public GameView(Context context) {
        this(context, null);
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
        // keep keyboard focus after touches, so the first arrow / Space key is never swallowed by touch mode
        setFocusableInTouchMode(true);
        swipeThreshold = 36 * getResources().getDisplayMetrics().density;

        // Images live in res/drawable-nodpi so Android doesn't rescale them.
        backgroundSource = BitmapFactory.decodeResource(getResources(), R.drawable.game_background);
        obstacleSource = BitmapFactory.decodeResource(getResources(), R.drawable.obstacle);
        playerSources = new Bitmap[FORM_DRAWABLES.length];
        for (int i = 0; i < FORM_DRAWABLES.length; i++) {
            playerSources[i] = BitmapFactory.decodeResource(getResources(), FORM_DRAWABLES[i]);
        }

        laneGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        laneCorePaint.setColor(Color.argb(95, 200, 255, 250));
        laneCorePaint.setStrokeCap(Paint.Cap.ROUND);

        Typeface display = ResourcesCompat.getFont(context, R.font.bangers);
        Typeface body = ResourcesCompat.getFont(context, R.font.rajdhani_bold);
        for (Paint p : new Paint[]{bannerPaint, bannerStroke, popupPaint, popupStroke}) {
            p.setTypeface(display);
            p.setTextAlign(Paint.Align.CENTER);
        }
        bannerPaint.setShadowLayer(24f, 0f, 0f, Color.rgb(255, 106, 0));
        for (Paint p : new Paint[]{bannerStroke, popupStroke}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(NAVY);
        }
        popupPaint.setColor(Color.rgb(44, 229, 216));
        hintPaint.setTypeface(body);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(Color.WHITE);
        hintPaint.setShadowLayer(10f, 0f, 2f, Color.BLACK);

        setupDebugPaint(debugImagePaint, Color.rgb(255, 80, 80), 3f);
        setupDebugPaint(debugHitPaint, Color.rgb(80, 255, 120), 4f);
        setupDebugPaint(debugGrazePaint, Color.rgb(255, 214, 60), 4f);
        setupDebugPaint(debugGatePaint, Color.rgb(255, 214, 60), 3f);
        setupDebugPaint(debugBarPaint, Color.rgb(44, 229, 216), 3f);
        setupDebugPaint(debugBlastPaint, Color.rgb(120, 200, 255), 3f);
        debugTextPaint.setTypeface(Typeface.MONOSPACE);
        debugTextPaint.setColor(Color.WHITE);
        debugBgPaint.setColor(Color.argb(170, 0, 0, 0));

        goText = context.getString(R.string.banner_go);
        powerUpText = context.getString(R.string.banner_power_up);
        closeText = context.getString(R.string.banner_close);
        hintText = context.getString(R.string.hint_controls);
        formBanners = getResources().getStringArray(R.array.form_banners);
        view3dText = context.getString(R.string.banner_3d);
        view2dText = context.getString(R.string.banner_2d);

        roadEdgePaint.setStyle(Paint.Style.STROKE);
        roadEdgePaint.setStrokeCap(Paint.Cap.ROUND);
        gridPaint.setStyle(Paint.Style.STROKE);
    }

    private static void setupDebugPaint(Paint p, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
    }

    public void setGameListener(GameListener listener) {
        this.listener = listener;
    }

    /** Difficulty only changes the falling speed; the spacing rule (player height + buffer) never changes. */
    public synchronized void setDifficulty(Difficulty d) {
        difficultySpeed = d.classicSpeed;
    }

    public void setSoundManager(SoundManager sound) {
        this.sound = sound;
    }

    /** First run only: show "SWIPE OR TAP LEFT / RIGHT" under the GO! banner. */
    public synchronized void setShowHint(boolean show) {
        showHint = show;
    }

    /** Called by MainActivity once the control bar has been laid out. */
    public synchronized void setControlBarTop(int top) {
        controlBarTop = top;
    }

    /** Called by GameThread about once per second. */
    @Override
    public void reportFps(float value) {
        fps = value;
    }

    // ---------------- Surface lifecycle ----------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new GameThread(holder, this);
        thread.setRunning(true);
        thread.start();
        requestFocus();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        setupSizes(width, height);
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

    /** Scales everything to the screen. Only rebuilds when the size actually changes (keeps game state on resume). */
    private synchronized void setupSizes(int width, int height) {
        if (ready && width == viewWidth && height == viewHeight) return;

        viewWidth = width;
        viewHeight = height;
        laneWidth = width / (float) GameRules.LANE_COUNT;

        background = centerCrop(backgroundSource, width, height);

        int playerW = (int) (laneWidth * PLAYER_WIDTH_OF_LANE);
        int playerH = playerW * playerSources[0].getHeight() / playerSources[0].getWidth();
        Bitmap[] forms = new Bitmap[playerSources.length];
        for (int i = 0; i < forms.length; i++) {
            forms[i] = Bitmap.createScaledBitmap(playerSources[i], playerW, playerH, true);
        }
        player = new Player(forms, FORM_COLORS);

        int obstacleSize = (int) (laneWidth * OBSTACLE_SIZE_OF_LANE);
        obstacleBitmap = Bitmap.createScaledBitmap(obstacleSource, obstacleSize, obstacleSize, true);

        trailLength = obstacleSize * 1.6f;
        trailPaint.setShader(new LinearGradient(0, 0, 0, trailLength,
                Color.argb(0, 255, 63, 174), Color.argb(110, 255, 63, 174), Shader.TileMode.CLAMP));

        floorGlowHeight = height * 0.30f;
        dashLength = height * 0.045f;
        dashPeriod = dashLength * 2.2f;
        laneGlowWidth = width * 0.012f;
        laneCoreWidth = Math.max(2f, width * 0.003f);
        gridSpacing = height * 0.07f;
        gridWidth = Math.max(2f, width * 0.0028f);
        roadEdgePaint.setStrokeWidth(width * 0.006f);
        roadPaint.setShader(new LinearGradient(0, 0, 0, height,
                Color.argb(95, 10, 10, 34), Color.argb(175, 14, 12, 40), Shader.TileMode.CLAMP));
        float shadowR = playerW * 0.45f;
        shadowPaint.setShader(new RadialGradient(0, 0, shadowR,
                new int[]{Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0)}, null, Shader.TileMode.CLAMP));
        enemyShadowPaint.setShader(new RadialGradient(0, 0, obstacleSize * 0.5f,
                new int[]{Color.argb(140, 0, 0, 0), Color.argb(0, 0, 0, 0)}, null, Shader.TileMode.CLAMP));

        float bannerSize = width * 0.16f;
        bannerPaint.setTextSize(bannerSize);
        bannerStroke.setTextSize(bannerSize);
        bannerStroke.setStrokeWidth(bannerSize * 0.08f);
        float popupSize = width * 0.075f;
        popupPaint.setTextSize(popupSize);
        popupStroke.setTextSize(popupSize);
        popupStroke.setStrokeWidth(popupSize * 0.12f);
        hintPaint.setTextSize(width * 0.05f);
        debugTextPaint.setTextSize(width * 0.028f);

        fx = new FxLayer(width, height);
        ready = true;
        restart();
    }

    /** Scales the background to fill the screen without stretching, cropping the overflow. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int sw = Math.max(w, Math.round(src.getWidth() * scale));
        int sh = Math.max(h, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h);
    }

    private int barTop() {
        return controlBarTop > 0 ? controlBarTop : viewHeight;
    }

    private void setFloorColor(int color) {
        floorPaint.setShader(new LinearGradient(0, 0, 0, floorGlowHeight,
                FxLayer.withAlpha(color, 0), FxLayer.withAlpha(color, 70), Shader.TileMode.CLAMP));
        // 3D road edges fade into the distance
        roadEdgePaint.setShader(new LinearGradient(0, 0, 0, viewHeight,
                FxLayer.withAlpha(color, 30), FxLayer.withAlpha(color, 230), Shader.TileMode.CLAMP));
    }

    // ---------------- Game loop: update ----------------

    @Override
    public synchronized void update(float dt) {
        if (!ready) return;
        updateCamera(dt);          // the camera can still move (e.g. 2D/3D switch) while paused
        if (paused) return;        // paused: the last frame stays on screen

        // 3-2-1 after resuming: the world stays frozen until it hits zero.
        if (countdown > 0) {
            countdown -= dt;
            int shown = (int) Math.ceil(Math.max(countdown, 0f));
            if (shown > 0 && shown != lastCountShown) {
                lastCountShown = shown;
                if (sound != null) sound.playTick();
            }
            if (countdown > 0) return;
            countdown = 0;
            showBanner(goText, GOLD);
            if (sound != null) sound.playGo();
        }

        realTime += dt;
        if (slowMoTimer > 0) slowMoTimer -= dt;
        float gdt = slowMoTimer > 0 ? dt * SLOW_MO_SCALE : dt;  // "game time" slows down in slow-motion
        animTime += gdt;

        float speed = viewHeight * GameRules.speedFraction(score) * difficultySpeed;
        fx.update(gdt, gameOver ? 0f : speed);
        if (bannerTimer > 0) bannerTimer -= dt;
        if (popupTimer > 0) popupTimer -= dt;
        if (muzzleTimer > 0) muzzleTimer -= dt;
        if (blastCooldown > 0) blastCooldown -= dt;   // real time, like dodging
        if (hintTimer > 0) hintTimer -= dt;
        // Real dt for the player: you can still dodge at full speed during slow-motion.
        player.update(dt, laneWidth, barTop(), animTime);
        if (gameOver) return; // effects keep animating, gameplay is frozen

        runTime += gdt;
        dashOffset = (dashOffset + speed * gdt) % dashPeriod;
        gridOffset = (gridOffset + speed * gdt) % gridSpacing;

        // Spawn only when the previous obstacle has fallen far enough (fair spacing rule).
        // spawnTop keeps falling even if that obstacle was blasted, so the spacing never breaks or stalls.
        spawnTop += speed * gdt;
        if (!hasSpawned || GameRules.canSpawn(spawnTop, requiredGap)) {
            // a row of 1-3 blocks in different lanes (always leaving free lanes); the spacing rule is per row
            int count = GameRules.blocksInRow(score, random.nextFloat());
            for (int i = 0; i < GameRules.LANE_COUNT; i++) laneOrder[i] = i;
            for (int i = GameRules.LANE_COUNT - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = laneOrder[i];
                laneOrder[i] = laneOrder[j];
                laneOrder[j] = tmp;
            }
            for (int i = 0; i < count; i++) {
                Obstacle o = new Obstacle(obstacleBitmap, laneOrder[i], laneWidth);
                obstacles.add(o);
                spawnTop = o.getTop();
            }
            hasSpawned = true;
            requiredGap = GameRules.spawnGap(player.getHeight(), random.nextFloat());
        }

        updateBlasts(gdt);

        Hitbox playerImage = player.getImageBox();
        Hitbox playerHit = playerImage.inset(GameRules.HITBOX_INSET);
        Iterator<Obstacle> it = obstacles.iterator();
        while (it.hasNext()) {
            Obstacle o = it.next();
            o.update(speed, gdt);
            Hitbox obstacleImage = o.getImageBox();

            // Collision (forgiving hitboxes) -> game stops
            if (GameRules.isCollision(playerImage, obstacleImage, player.isAirborne())) {
                it.remove();
                endGame(o);
                return;
            }
            // Images touched but hitboxes didn't: remember it, reward once it is safely past.
            if (!player.isAirborne() && GameRules.isNearMiss(playerImage, obstacleImage)) o.markGrazed();
            if (obstacleImage.inset(GameRules.HITBOX_INSET).top >= playerHit.bottom && o.claimCloseCall()) {
                onCloseCall();
            }

            // Top of obstacle passed the top of the control bar -> +1 and release it.
            if (GameRules.passedControlBar(o.getTop(), barTop())) {
                it.remove();
                onObstaclePassed();
            }
        }
    }

    /** Eases the camera between 2D (strength 0) and 3D (strength 1) and makes it follow the player. */
    private void updateCamera(float dt) {
        float target = view3d ? 1f : 0f;
        float t = camera.getStrength();
        if (cameraSnap) {
            t = target;
            cameraSnap = false;
        } else {
            t += (target - t) * Math.min(1f, dt * CAMERA_EASE);
            if (Math.abs(target - t) < 0.002f) t = target;
        }
        float swayTarget = (viewWidth / 2f - player.getCenterX()) * SWAY;
        camSway += (swayTarget - camSway) * Math.min(1f, dt * 4f);
        camera.configure(viewWidth, barTop() - player.getHeight() * 0.72f, FAR_SCALE); // player's row
        camera.setStrength(t);
        camera.setSway(camSway);
    }

    /** Moves every blast up its lane; the first enemy block it touches is destroyed (+1 point). */
    private void updateBlasts(float gdt) {
        float blastSpeed = viewHeight * GameRules.BLAST_SPEED;
        Iterator<Blast> bi = blasts.iterator();
        while (bi.hasNext()) {
            Blast b = bi.next();
            b.update(blastSpeed, gdt);
            if (b.isOffScreen()) {
                bi.remove();
                continue;
            }
            Hitbox box = b.getBox();
            for (Iterator<Obstacle> oi = obstacles.iterator(); oi.hasNext(); ) {
                Obstacle o = oi.next();
                if (GameRules.blastHits(box, o.getImageBox())) {
                    oi.remove();
                    bi.remove();
                    onEnemyBlasted(o, b);
                    break;
                }
            }
        }
    }

    private void onEnemyBlasted(Obstacle o, Blast b) {
        enemiesBlasted++;
        float cy = o.getCenterY();
        fx.hit(camera.x(o.getCenterX(), cy), cy, b.getColor());
        if (sound != null) sound.playHit();
        addPoints(GameRules.BLAST_KILL_POINTS, false);
    }

    private void onObstaclePassed() {
        addPoints(1, true);
    }

    private void addPoints(int points, boolean passSound) {
        score += points;
        if (listener != null) listener.onScoreChanged(score);
        if (passSound && sound != null) sound.playScore();

        int form = GameRules.formForScore(score);
        if (form > player.getForm()) {
            transformTo(form);
        } else if (score % MILESTONE_EVERY == 0) {
            showBanner(powerUpText, GOLD);
        }
    }

    /** Near miss: visual reward only (the score rule is unchanged). */
    private void onCloseCall() {
        closeCalls++;
        popupText = closeText;
        popupX = player.getCenterX();
        popupY = player.getCenterY() - player.getHeight() * 0.9f;
        popupTimer = POPUP_TIME;
        slowMoTimer = Math.max(slowMoTimer, CLOSE_CALL_SLOW_MO);
        if (sound != null) sound.playClose();
    }

    private void transformTo(int form) {
        int color = FORM_COLORS[form];
        player.setForm(form);
        fx.powerUp(player.getCenterX(), player.getCenterY(), color);
        fx.setTint(color);
        setFloorColor(color);
        showBanner(formBanners[form], color);
        slowMoTimer = Math.max(slowMoTimer, TRANSFORM_SLOW_MO);
        if (sound != null) sound.playTransform();
        if (listener != null) listener.onFormChanged(form);
    }

    private void endGame(Obstacle hit) {
        gameOver = true;
        gameOverRealTime = realTime;
        slowMoTimer = CRASH_SLOW_MO;
        fx.explode((hit.getCenterX() + player.getCenterX()) / 2f, (hit.getCenterY() + player.getCenterY()) / 2f);
        if (sound != null) sound.playCrash();
        if (listener != null) listener.onGameOver(new RunStats(score, runTime, player.getForm(), closeCalls, enemiesBlasted));
    }

    private void showBanner(String text, int color) {
        bannerText = text;
        bannerColor = color;
        bannerTimer = BANNER_TIME;
    }

    // ---------------- Game loop: draw ----------------

    @Override
    public synchronized void render(Canvas canvas) {
        if (!ready) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        float t = camera.getStrength();
        canvas.save();
        fx.applyShake(canvas);

        canvas.drawBitmap(background, 0, 0, null);
        if (t > 0.01f) drawRoad(canvas, t);
        fx.drawSpeedLines(canvas, camera);
        drawLanes(canvas);
        drawFloorGlow(canvas);

        // 2.5D: everything floats above the road, so the ground shadows go down first.
        if (t > 0.01f) {
            for (int i = 0; i < obstacles.size(); i++) drawEnemyShadow(canvas, obstacles.get(i), t);
            for (int i = 0; i < blasts.size(); i++) drawBlastGlow(canvas, blasts.get(i), t);
        }

        // Painter's algorithm: far obstacles first, then blasts, then the player, then anything already past the player.
        float playerRow = player.getCenterY();
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            if (o.getCenterY() < playerRow) drawObstacle(canvas, o, t);
        }
        for (int i = 0; i < blasts.size(); i++) drawBlast(canvas, blasts.get(i));
        if (t > 0.01f || player.isAirborne()) drawPlayerShadow(canvas, Math.max(t, 0.85f));
        player.draw(canvas, bitmapPaint);
        drawMuzzle(canvas);
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            if (o.getCenterY() >= playerRow) drawObstacle(canvas, o, t);
        }
        fx.drawParticles(canvas);

        canvas.restore();
        fx.drawFlash(canvas);
        drawPopup(canvas);
        drawBanner(canvas);
        drawHint(canvas);
        if (countdown > 0) drawCountdown(canvas);
        if (debug) drawDebug(canvas);
    }

    /** 3D only: a translucent road plane with a scrolling grid and glowing edges, fading in with the camera. */
    private void drawRoad(Canvas canvas, float t) {
        float bottom = barTop();
        float xl0 = camera.x(0, 0), xr0 = camera.x(viewWidth, 0);
        float xl1 = camera.x(0, bottom), xr1 = camera.x(viewWidth, bottom);
        path.reset();
        path.moveTo(xl0, 0);
        path.lineTo(xr0, 0);
        path.lineTo(xr1, bottom);
        path.lineTo(xl1, bottom);
        path.close();
        roadPaint.setAlpha((int) (255 * t));
        canvas.drawPath(path, roadPaint);

        int color = FORM_COLORS[player.getForm()];
        for (float y = -gridSpacing + gridOffset; y < bottom; y += gridSpacing) {
            float s = camera.scale(y);
            gridPaint.setColor(FxLayer.withAlpha(color, (int) (70 * t * Math.min(1f, s))));
            gridPaint.setStrokeWidth(gridWidth * s);
            canvas.drawLine(camera.x(0, y), y, camera.x(viewWidth, y), y, gridPaint);
        }
        roadEdgePaint.setAlpha((int) (255 * t));
        canvas.drawLine(xl0, 0, xl1, bottom, roadEdgePaint);
        canvas.drawLine(xr0, 0, xr1, bottom, roadEdgePaint);
    }

    /** Dashed glowing lane dividers that scroll with the game speed (and converge in 3D). */
    private void drawLanes(Canvas canvas) {
        float bottom = barTop();
        laneGlowPaint.setColor(FxLayer.withAlpha(FORM_COLORS[player.getForm()], 40));
        for (int i = 1; i < GameRules.LANE_COUNT; i++) {
            float x = laneWidth * i;
            for (float y = -dashPeriod + dashOffset; y < bottom; y += dashPeriod) {
                float y2 = Math.min(y + dashLength, bottom);
                float s = camera.scale((y + y2) / 2f);
                float xa = camera.x(x, y), xb = camera.x(x, y2);
                laneGlowPaint.setStrokeWidth(laneGlowWidth * s);
                laneCorePaint.setStrokeWidth(laneCoreWidth * s);
                canvas.drawLine(xa, y, xb, y2, laneGlowPaint);
                canvas.drawLine(xa, y, xb, y2, laneCorePaint);
            }
        }
    }

    /** Soft glow under the player's lane so it's always clear where you are. */
    private void drawFloorGlow(Canvas canvas) {
        float top = barTop() - floorGlowHeight, bottom = barTop();
        float half = laneWidth / 2f;
        float cx = player.getCenterX();
        float st = camera.scale(top), sb = camera.scale(bottom);
        float xt = camera.x(cx, top), xb = camera.x(cx, bottom);
        path.reset();                                  // local coords: the gradient runs 0..floorGlowHeight
        path.moveTo(xt - half * st, 0);
        path.lineTo(xt + half * st, 0);
        path.lineTo(xb + half * sb, floorGlowHeight);
        path.lineTo(xb - half * sb, floorGlowHeight);
        path.close();
        canvas.save();
        canvas.translate(0, top);
        canvas.drawPath(path, floorPaint);
        canvas.restore();
    }

    /** Draws one obstacle and its trail through the camera. In 3D, far ones are smaller and fade in (depth fog). */
    private void drawObstacle(Canvas canvas, Obstacle o, float t) {
        float cy = o.getCenterY();
        float size = o.getSize();
        float s = camera.scale(cy);
        float cx = camera.x(o.getCenterX(), cy);
        float fog = Math.max(0f, Math.min(1f, (cy + size) / (viewHeight * 0.45f)));
        int alpha = (int) (255 * (1f - t * (0.65f * (1f - fog))));

        // Energy trail: a trapezoid from trailLength above the block down to its center.
        float yT = cy - trailLength;
        float sT = camera.scale(yT);
        float xT = camera.x(o.getCenterX(), yT);
        float hw = size * 0.28f;
        path.reset();
        path.moveTo(xT - hw * sT, 0);
        path.lineTo(xT + hw * sT, 0);
        path.lineTo(cx + hw * s, trailLength);
        path.lineTo(cx - hw * s, trailLength);
        path.close();
        trailPaint.setAlpha(alpha);
        canvas.save();
        canvas.translate(0, yT);
        canvas.drawPath(path, trailPaint);
        canvas.restore();

        float half = size * s / 2f;
        dst.set(cx - half, cy - half, cx + half, cy + half);
        obstaclePaint.setAlpha(alpha);
        canvas.drawBitmap(o.getBitmap(), null, dst, obstaclePaint);
    }

    /** 2.5D only: enemy blocks hover, so each casts a soft shadow on the road a little below it. */
    private void drawEnemyShadow(Canvas canvas, Obstacle o, float t) {
        float cy = o.getCenterY();
        float s = camera.scale(cy);
        float size = o.getSize();
        float sy = cy + size * 0.62f * s;
        canvas.save();
        canvas.translate(camera.x(o.getCenterX(), sy), sy);
        canvas.scale(s, s * 0.28f);
        enemyShadowPaint.setAlpha((int) (255 * t * Math.min(1f, s * 1.4f)));
        canvas.drawCircle(0, 0, size * 0.5f, enemyShadowPaint);
        canvas.restore();
    }

    /** 2.5D only: a blast lights up the road under it as it flies. */
    private void drawBlastGlow(Canvas canvas, Blast b, float t) {
        float cy = b.getCenterY() + b.getHeight() * 0.9f;
        float s = camera.scale(cy);
        canvas.save();
        canvas.translate(camera.x(b.getCenterX(), cy), cy);
        canvas.scale(s, s * 0.3f);
        blastPaint.setColor(FxLayer.withAlpha(b.getColor(), (int) (90 * t)));
        canvas.drawCircle(0, 0, b.getWidth() * 1.1f, blastPaint);
        canvas.restore();
    }

    /** An energy blast: colored glow, bright core and a short tail, all scaled by the camera. */
    private void drawBlast(Canvas canvas, Blast b) {
        float cy = b.getCenterY();
        float s = camera.scale(cy);
        float cx = camera.x(b.getCenterX(), cy);
        float w = b.getWidth() * s, h = b.getHeight() * s;
        int color = b.getColor();
        float flicker = 1f + 0.12f * (float) Math.sin(b.getAge() * 60f);

        // tail pointing back toward the hero
        float tailY = cy + h * 1.4f;
        float tailX = camera.x(b.getCenterX(), tailY);
        path.reset();
        path.moveTo(cx - w * 0.45f, cy);
        path.lineTo(cx + w * 0.45f, cy);
        path.lineTo(tailX, tailY);
        path.close();
        blastPaint.setColor(FxLayer.withAlpha(color, 110));
        canvas.drawPath(path, blastPaint);

        blastPaint.setColor(FxLayer.withAlpha(color, 70));
        dst.set(cx - w * 0.9f * flicker, cy - h * 0.75f * flicker, cx + w * 0.9f * flicker, cy + h * 0.75f * flicker);
        canvas.drawOval(dst, blastPaint);
        blastPaint.setColor(FxLayer.withAlpha(color, 200));
        dst.set(cx - w * 0.55f, cy - h * 0.5f, cx + w * 0.55f, cy + h * 0.5f);
        canvas.drawOval(dst, blastPaint);
        blastPaint.setColor(Color.WHITE);
        dst.set(cx - w * 0.28f, cy - h * 0.3f, cx + w * 0.28f, cy + h * 0.3f);
        canvas.drawOval(dst, blastPaint);
    }

    /** A quick flash at the hero's hands when a blast is fired. */
    private void drawMuzzle(Canvas canvas) {
        if (muzzleTimer <= 0) return;
        float k = muzzleTimer / MUZZLE_TIME;
        float r = laneWidth * 0.16f * (0.6f + 0.4f * k);
        float x = player.getCenterX(), y = player.getCenterY() + player.getHeight() * 0.05f;
        blastPaint.setColor(FxLayer.withAlpha(FORM_COLORS[player.getForm()], (int) (160 * k)));
        canvas.drawCircle(x, y, r * 1.6f, blastPaint);
        blastPaint.setColor(FxLayer.withAlpha(Color.WHITE, (int) (230 * k)));
        canvas.drawCircle(x, y, r * 0.7f, blastPaint);
    }

    /** 2.5D only: a soft shadow on the road under the cloud. */
    private void drawPlayerShadow(Canvas canvas, float t) {
        float shrink = 1f - 0.45f * player.jumpLift();     // smaller while jumping = clearly in the air
        canvas.save();
        canvas.translate(player.getCenterX(), player.getCenterY() + player.getHeight() * 0.62f);
        canvas.scale(shrink, 0.22f * shrink);
        shadowPaint.setAlpha((int) (255 * t));
        canvas.drawCircle(0, 0, player.getWidth() * 0.45f, shadowPaint);
        canvas.restore();
    }

    private void drawBanner(Canvas canvas) {
        if (bannerTimer <= 0 || bannerText == null) return;
        float t = 1f - bannerTimer / BANNER_TIME; // 0 -> 1
        float scale = t < 0.15f ? 0.6f + (t / 0.15f) * 0.5f : 1.1f - Math.min((t - 0.15f) * 0.3f, 0.1f);
        int alpha = t > 0.7f ? (int) (255 * (1f - (t - 0.7f) / 0.3f)) : 255;
        drawBigText(canvas, bannerText, viewHeight * 0.38f, scale, alpha, bannerColor);
    }

    private void drawCountdown(Canvas canvas) {
        int n = (int) Math.ceil(countdown);
        float frac = countdown - (n - 1); // 1 -> 0 during each second
        drawBigText(canvas, String.valueOf(n), viewHeight * 0.44f, 1.2f + 0.8f * frac,
                (int) (255 * Math.min(1f, frac * 2f)), GOLD);
    }

    private void drawBigText(Canvas canvas, String text, float y, float scale, int alpha, int color) {
        canvas.save();
        canvas.translate(viewWidth / 2f, y);
        canvas.scale(scale, scale);
        bannerPaint.setColor(color);
        bannerPaint.setAlpha(alpha);
        bannerStroke.setAlpha(alpha);
        canvas.drawText(text, 0, 0, bannerStroke);
        canvas.drawText(text, 0, 0, bannerPaint);
        canvas.restore();
    }

    private void drawPopup(Canvas canvas) {
        if (popupTimer <= 0 || popupText == null) return;
        float t = 1f - popupTimer / POPUP_TIME;
        float y = popupY - t * viewHeight * 0.05f;
        int alpha = t > 0.6f ? (int) (255 * (1f - (t - 0.6f) / 0.4f)) : 255;
        popupStroke.setAlpha(alpha);
        popupPaint.setAlpha(alpha);
        canvas.drawText(popupText, popupX, y, popupStroke);
        canvas.drawText(popupText, popupX, y, popupPaint);
    }

    private void drawHint(Canvas canvas) {
        if (hintTimer <= 0) return;
        float fadeIn = Math.min(1f, (HINT_TIME - hintTimer) / 0.3f);
        float fadeOut = Math.min(1f, hintTimer / 0.5f);
        hintPaint.setAlpha((int) (235 * fadeIn * fadeOut));
        canvas.drawText(hintText, viewWidth / 2f, viewHeight * 0.47f, hintPaint);
    }

    /** Dev overlay (hold the SCORE box): shows the rules working live. */
    private void drawDebug(Canvas canvas) {
        Hitbox playerImage = player.getImageBox();
        drawBox(canvas, playerImage, debugImagePaint);
        drawBox(canvas, playerImage.inset(GameRules.HITBOX_INSET), debugHitPaint);
        for (Obstacle o : obstacles) {
            Hitbox img = o.getImageBox();
            drawBox(canvas, img, debugImagePaint);
            drawBox(canvas, img.inset(GameRules.HITBOX_INSET), o.isGrazed() ? debugGrazePaint : debugHitPaint);
        }
        for (Blast b : blasts) drawBox(canvas, b.getBox(), debugBlastPaint);

        float pad = viewWidth * 0.03f;
        float labelSize = debugTextPaint.getTextSize();
        canvas.drawLine(0, requiredGap, viewWidth, requiredGap, debugGatePaint);
        String gate = "SPAWN GATE";
        canvas.drawText(gate, viewWidth - pad - debugTextPaint.measureText(gate), requiredGap - labelSize * 0.4f, debugTextPaint);
        float bt = barTop();
        canvas.drawLine(0, bt, viewWidth, bt, debugBarPaint);
        canvas.drawText("SCORE / RELEASE LINE", pad, bt - labelSize * 0.4f, debugTextPaint);

        float speed = viewHeight * GameRules.speedFraction(score) * difficultySpeed * (slowMoTimer > 0 ? SLOW_MO_SCALE : 1f);
        String[] lines = {
                String.format(Locale.US, "FPS %.0f  SPEED %.0fpx/s%s", fps, speed, slowMoTimer > 0 ? " SLOW" : ""),
                "OBST " + obstacles.size() + "  CLOSE " + closeCalls,
                String.format(Locale.US, "BLAST %d  KO %d  CD %.2fs", blasts.size(), enemiesBlasted, Math.max(0f, blastCooldown)),
                String.format(Locale.US, "GAP  min %.0fpx = H+%.0fH", GameRules.minSpawnGap(player.getHeight()), GameRules.BUFFER_MULTIPLIER),
                String.format(Locale.US, "FORM %d/%d  VIEW %s %.0f%%", player.getForm() + 1, FORM_COLORS.length, view3d ? "2.5D" : "2D", camera.getStrength() * 100),
                "red=image green=hitbox -" + Math.round(GameRules.HITBOX_INSET * 100) + "%",
        };
        float lineH = labelSize * 1.35f;
        float maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, debugTextPaint.measureText(l));
        float x0 = pad, y0 = viewHeight * 0.12f;
        canvas.drawRect(x0 - pad * 0.5f, y0 - lineH, x0 + maxW + pad * 0.5f, y0 + lineH * (lines.length - 0.6f), debugBgPaint);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], x0, y0 + i * lineH, debugTextPaint);
    }

    /** Draws a world box the same way its sprite is drawn (scaled around its center by the camera). */
    private void drawBox(Canvas canvas, Hitbox b, Paint p) {
        float cy = (b.top + b.bottom) / 2f;
        float s = camera.scale(cy);
        float cx = camera.x((b.left + b.right) / 2f, cy);
        float hw = b.width() / 2f * s, hh = b.height() / 2f * s;
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, p);
    }

    // ---------------- Controls (called on the UI thread) ----------------

    private boolean canControl() {
        return ready && !gameOver && !paused && countdown <= 0;
    }

    public synchronized boolean moveLeft() {
        if (!canControl()) return false;
        return afterMove(player.moveLeft());
    }

    public synchronized boolean moveRight() {
        if (!canControl()) return false;
        return afterMove(player.moveRight());
    }

    /** Jump: hop over the blocks for a moment (Space / W / Up, swipe up, or the JUMP button). */
    public synchronized boolean jump() {
        if (!canControl() || !player.jump()) return false;
        if (sound != null) sound.playMove();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return true;
    }

    /** Fires an energy blast up the hero's lane (tap the screen). Returns true if a shot was fired. */
    public synchronized boolean fire() {
        if (!canControl() || !GameRules.canFire(blastCooldown)) return false;
        int form = player.getForm();
        float w = laneWidth * BLAST_WIDTH_OF_LANE, h = laneWidth * BLAST_HEIGHT_OF_LANE;
        float startTop = player.getImageBox().top - h * 0.2f;
        blasts.add(new Blast(player.getLane(), laneWidth, startTop, w, h, FORM_COLORS[form]));
        blastCooldown = GameRules.blastCooldown(form);
        muzzleTimer = MUZZLE_TIME;
        if (sound != null) sound.playBlast();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        return true;
    }

    private boolean afterMove(boolean moved) {
        if (moved) {
            if (sound != null) sound.playMove();
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        return moved;
    }

    public synchronized boolean isGameOver() {
        return gameOver;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Pauses a running game. Returns true if it was running (so the caller should show the pause screen). */
    public synchronized boolean pauseGame() {
        if (!ready || gameOver || paused) return false;
        paused = true;
        countdown = 0;
        return true;
    }

    /** Unpauses with a 3-2-1 countdown so the player isn't caught off guard. */
    public synchronized void resumeWithCountdown() {
        if (!paused) return;
        paused = false;
        countdown = RESUME_COUNTDOWN;
        lastCountShown = 0;
    }

    /** Switches between the flat 2D view and the 2.5D view (the camera flies over smoothly). Returns the new state. */
    public synchronized boolean toggle3d() {
        view3d = !view3d;
        if (ready && !paused) showBanner(view3d ? view3dText : view2dText, GOLD);
        if (sound != null) sound.playClose();
        return view3d;
    }

    /** Sets the view without animation (used for the saved setting at startup). */
    public synchronized void set3d(boolean on) {
        view3d = on;
        cameraSnap = true;
    }

    /** Toggles the dev overlay. Returns the new state. */
    public synchronized boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    /** Restarts only after a short delay, so a panicked tap during the crash doesn't skip Game Over. */
    public synchronized boolean restartIfAllowed() {
        if (gameOver && realTime - gameOverRealTime >= RESTART_LOCKOUT) {
            restart();
            return true;
        }
        return false;
    }

    /** STAGE 6: reset everything and start a new game. */
    public synchronized void restart() {
        if (!ready) return;
        obstacles.clear();
        blasts.clear();
        hasSpawned = false;
        spawnTop = 0;
        blastCooldown = 0;
        muzzleTimer = 0;
        enemiesBlasted = 0;
        score = 0;
        closeCalls = 0;
        runTime = 0;
        gameOver = false;
        paused = false;
        countdown = 0;
        slowMoTimer = 0;
        popupTimer = 0;
        player.reset();
        fx.reset();
        fx.setTint(FORM_COLORS[0]);
        setFloorColor(FORM_COLORS[0]);
        requiredGap = GameRules.spawnGap(player.getHeight(), random.nextFloat());
        showBanner(goText, GOLD);
        if (showHint) {
            hintTimer = HINT_TIME;
            showHint = false;
        }
        if (sound != null) sound.playGo();
        if (listener != null) {
            listener.onScoreChanged(0);
            listener.onFormChanged(0);
            listener.onGameRestarted();
        }
    }

    /** Swipe left/right to move (in addition to the buttons); tap to blast, or to restart after Game Over. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swipeHandled = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!swipeHandled) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > swipeThreshold && Math.abs(dx) > Math.abs(dy)) {
                        swipeHandled = true; // one lane per swipe
                        if (dx < 0) moveLeft();
                        else moveRight();
                    } else if (-dy > swipeThreshold && -dy > Math.abs(dx)) {
                        swipeHandled = true; // swipe up = jump
                        jump();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!swipeHandled) {
                    // A tap (not a swipe): restart after Game Over, otherwise fire an energy blast.
                    if (isGameOver()) restartIfAllowed();
                    else fire();
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
