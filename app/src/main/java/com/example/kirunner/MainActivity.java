package com.example.kirunner;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * STAGE 4 + 6: Hosts the GameView, the LEFT / SCORE / RIGHT control bar, the top HUD
 * (pause, form meter, sound), and the Pause and Game Over panels.
 */
public class MainActivity extends AppCompatActivity implements GameView.GameListener {

    private static final String PREFS = "ki_runner_prefs";
    private static final String KEY_BEST = "best_score";
    private static final String KEY_MUTED = "muted";
    private static final String KEY_HINT_SEEN = "hint_seen";
    private static final String KEY_3D = "view_25d";
    /** First launch starts in the 2.5D view. Set false to start in the flat 2D view (exactly the lab spec). */
    private static final boolean DEFAULT_3D = true;
    private static final long OVERLAY_DELAY_MS = 450; // let the slow-mo explosion play first

    private GameView gameView;
    private SoundManager sound;
    private SharedPreferences prefs;
    private String[] formNames;

    private TextView scoreText;
    private TextView formText;
    private ProgressBar formMeter;
    private ImageButton pauseButton;
    private ImageButton soundButton;
    private TextView viewButton;
    private View pauseOverlay;
    private View gameOverOverlay;
    private View gameOverPanel;
    private TextView finalScoreText, bestScoreText, newBestText, playAgainText;
    private TextView statTimeText, statFormText, statCloseText, statKoText;
    private ObjectAnimator blink;
    private final KeyDebounce debounce = new KeyDebounce();
    private static final long LANE_REPEAT_MS = 170;   // holding A / D moves one lane every 0.17 s
    private static final long FIRE_REPEAT_MS = 260;   // holding fire keeps shooting

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemUi.goImmersive(this);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        formNames = getResources().getStringArray(R.array.form_names);

        gameView = findViewById(R.id.gameView);
        scoreText = findViewById(R.id.scoreText);
        formText = findViewById(R.id.formText);
        formMeter = findViewById(R.id.formMeter);
        pauseButton = findViewById(R.id.pauseButton);
        soundButton = findViewById(R.id.soundButton);
        viewButton = findViewById(R.id.viewButton);
        pauseOverlay = findViewById(R.id.pauseOverlay);
        gameOverOverlay = findViewById(R.id.gameOverOverlay);
        gameOverPanel = findViewById(R.id.gameOverPanel);
        finalScoreText = findViewById(R.id.finalScoreText);
        bestScoreText = findViewById(R.id.bestScoreText);
        newBestText = findViewById(R.id.newBestText);
        playAgainText = findViewById(R.id.playAgainText);
        statTimeText = findViewById(R.id.statTimeText);
        statFormText = findViewById(R.id.statFormText);
        statCloseText = findViewById(R.id.statCloseText);
        statKoText = findViewById(R.id.statKoText);
        View root = findViewById(R.id.main);
        View topHud = findViewById(R.id.topHud);
        View controlBar = findViewById(R.id.controlBar);
        View scoreBox = findViewById(R.id.scoreBox);
        Button leftButton = findViewById(R.id.leftButton);
        Button rightButton = findViewById(R.id.rightButton);
        Button jumpButton = findViewById(R.id.jumpButton);
        Button fireButton = findViewById(R.id.fireButton);

        sound = new SoundManager(this, prefs.getBoolean(KEY_MUTED, false));
        updateSoundIcon();
        gameView.setSoundManager(sound);
        gameView.setDifficulty(Difficulty.fromName(prefs.getString(MenuActivity.KEY_DIFFICULTY, Difficulty.EASY.name())));
        gameView.setGameListener(this);
        boolean start3d = prefs.getBoolean(KEY_3D, DEFAULT_3D);
        gameView.set3d(start3d);
        updateViewButton(start3d);
        if (!prefs.getBoolean(KEY_HINT_SEEN, false)) {
            gameView.setShowHint(true);
            prefs.edit().putBoolean(KEY_HINT_SEEN, true).apply();
        }

        // Keep the HUD and control bar clear of any notch / system bar the device still shows.
        final int hudPaddingTop = topHud.getPaddingTop();
        final int barPaddingBottom = controlBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, 0, safe.right, 0);
            topHud.setPadding(topHud.getPaddingLeft(), hudPaddingTop + safe.top,
                    topHud.getPaddingRight(), topHud.getPaddingBottom());
            controlBar.setPadding(controlBar.getPaddingLeft(), controlBar.getPaddingTop(),
                    controlBar.getPaddingRight(), barPaddingBottom + safe.bottom);
            return insets;
        });

        // Tell the GameView where the control bar starts (re-runs if the layout changes).
        controlBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                gameView.setControlBarTop(controlBar.getTop() - gameView.getTop()));

        // Either button restarts after Game Over; otherwise it moves the player.
        leftButton.setOnClickListener(v -> onControlPressed(true));
        rightButton.setOnClickListener(v -> onControlPressed(false));
        jumpButton.setOnClickListener(v -> {
            if (gameView.isGameOver()) gameView.restartIfAllowed();
            else gameView.jump();
        });
        fireButton.setOnClickListener(v -> {
            if (gameView.isGameOver()) gameView.restartIfAllowed();
            else gameView.fire();
        });
        // Tapping anywhere on the Game Over screen restarts too.
        gameOverOverlay.setOnClickListener(v -> gameView.restartIfAllowed());

        pauseButton.setOnClickListener(v -> {
            if (gameView.pauseGame()) {
                showPauseOverlay();
                sound.pauseMusic();
            }
        });
        pauseOverlay.setOnClickListener(v -> resumeGame());

        soundButton.setOnClickListener(v -> {
            boolean mute = !sound.isMuted();
            sound.setMuted(mute);
            prefs.edit().putBoolean(KEY_MUTED, mute).apply();
            updateSoundIcon();
        });

        // 2D / 2.5D camera switch (same game logic, different camera)
        viewButton.setOnClickListener(v -> {
            boolean on = gameView.toggle3d();
            prefs.edit().putBoolean(KEY_3D, on).apply();
            updateViewButton(on);
            pop(viewButton, 1.3f);
        });

        // Hidden dev overlay for demos: hold the SCORE box to show hitboxes, spawn gate, FPS.
        scoreBox.setOnLongClickListener(v -> {
            boolean on = gameView.toggleDebug();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Toast.makeText(this, on ? R.string.dev_on : R.string.dev_off, Toast.LENGTH_SHORT).show();
            return true;
        });

        blink = ObjectAnimator.ofFloat(playAgainText, View.ALPHA, 1f, 0.35f);
        blink.setDuration(700);
        blink.setRepeatMode(ValueAnimator.REVERSE);
        blink.setRepeatCount(ValueAnimator.INFINITE);
    }

    /** Right mouse button fires a ki blast (it would otherwise act like Back). */
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS
                && ev.getActionButton() == android.view.MotionEvent.BUTTON_SECONDARY) {
            if (gameView.isGameOver()) gameView.restartIfAllowed();
            else gameView.fire();
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /**
     * Keyboard: A / D or arrows move, Space / W / Up jump, Enter / J / right-click fire, P / Esc pause, V 2D / 2.5D.
     * Keys are matched by key code and by physical scan code, so they work on any keyboard layout.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && (event.getSource() & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) gameView.fire();   // right-click arriving as Back
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        long t = event.getEventTime();
        int key = AdventureView.mapKey(code, event.getScanCode());
        if (key == AdvInput.LEFT || key == AdvInput.RIGHT) {
            if (debounce.accept(code, t, LANE_REPEAT_MS)) onControlPressed(key == AdvInput.LEFT);
            return true;
        }
        if (key == AdvInput.FIRE) {
            if (debounce.accept(code, t, FIRE_REPEAT_MS)) gameView.fire();
            return true;
        }
        if (key == AdvInput.UP || key == AdvInput.JUMP) {
            if (debounce.accept(code, t, 0)) {
                if (gameView.isGameOver()) gameView.restartIfAllowed();
                else gameView.jump();
            }
            return true;
        }
        // every other key acts once per press, even when held (no 30-per-second pause toggling)
        boolean known = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_P
                || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_V;
        if (known && !debounce.accept(code, t, 0)) return true;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A:
                onControlPressed(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D:
                onControlPressed(false);
                return true;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (pauseOverlay.getVisibility() == View.VISIBLE) resumeGame();
                else if (gameView.isGameOver()) gameView.restartIfAllowed();
                else gameView.fire();
                return true;
            case KeyEvent.KEYCODE_P: case KeyEvent.KEYCODE_ESCAPE:
                if (pauseOverlay.getVisibility() == View.VISIBLE) resumeGame();
                else if (gameView.pauseGame()) {
                    showPauseOverlay();
                    sound.pauseMusic();
                }
                return true;
            case KeyEvent.KEYCODE_V:
                viewButton.performClick();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void onControlPressed(boolean left) {
        if (gameView.isGameOver()) {
            gameView.restartIfAllowed();
        } else if (left) {
            gameView.moveLeft();
        } else {
            gameView.moveRight();
        }
    }

    private void resumeGame() {
        hidePauseOverlay();
        gameView.resumeWithCountdown();
        sound.startMusic();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!gameView.isPaused()) sound.startMusic();
    }

    @Override
    protected void onPause() {
        // Auto-pause when the app goes to the background, so the player never dies off-screen.
        if (gameView.pauseGame()) showPauseOverlay();
        sound.pauseMusic();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) SystemUi.goImmersive(this); // re-hide bars after dialogs / app switching
    }

    @Override
    protected void onDestroy() {
        blink.cancel();
        sound.release();
        super.onDestroy();
    }

    // ---- GameListener callbacks come from the game thread, so hop to the UI thread ----

    @Override
    public void onScoreChanged(int score) {
        runOnUiThread(() -> {
            scoreText.setText(String.valueOf(score));
            formMeter.setProgress(Math.round(GameRules.formProgress(score) * 100));
            if (score > 0) pop(scoreText, 1.4f);
        });
    }

    @Override
    public void onFormChanged(int formIndex) {
        runOnUiThread(() -> {
            formText.setText(formNames[formIndex]);
            formText.setTextColor(GameView.FORM_COLORS[formIndex]);
            if (formIndex > 0) pop(formText, 1.6f);
        });
    }

    @Override
    public void onGameOver(RunStats stats) {
        runOnUiThread(() -> showGameOver(stats));
    }

    @Override
    public void onGameRestarted() {
        runOnUiThread(() -> {
            hideGameOver();
            hidePauseOverlay();
            pauseButton.setVisibility(View.VISIBLE);
            sound.setDucked(false);
        });
    }

    // ---- UI helpers ----

    private void updateViewButton(boolean on) {
        viewButton.setTextColor(on ? GameView.FORM_COLORS[0] : 0x99FFFFFF);
    }

    private void updateSoundIcon() {
        soundButton.setImageResource(sound.isMuted() ? R.drawable.ic_sound_off : R.drawable.ic_sound_on);
    }

    private void pop(View v, float from) {
        v.animate().cancel();
        v.setScaleX(from);
        v.setScaleY(from);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    private void showPauseOverlay() {
        pauseOverlay.animate().cancel();
        pauseOverlay.setAlpha(0f);
        pauseOverlay.setVisibility(View.VISIBLE);
        pauseOverlay.animate().alpha(1f).setStartDelay(0).setDuration(180).start();
    }

    private void hidePauseOverlay() {
        if (pauseOverlay.getVisibility() != View.VISIBLE) return;
        pauseOverlay.animate().cancel();
        pauseOverlay.animate().alpha(0f).setStartDelay(0).setDuration(150)
                .withEndAction(() -> pauseOverlay.setVisibility(View.GONE)).start();
    }

    private void showGameOver(RunStats stats) {
        // Score box is NOT reset here, so the final score stays visible.
        int best = prefs.getInt(KEY_BEST, 0);
        boolean newBest = stats.score > best;
        if (newBest) {
            best = stats.score;
            prefs.edit().putInt(KEY_BEST, best).apply();
        }
        finalScoreText.setText(getString(R.string.final_score, stats.score));
        bestScoreText.setText(getString(R.string.best_score, best));
        newBestText.setVisibility(newBest ? View.VISIBLE : View.GONE);
        statTimeText.setText(stats.timeText());
        statFormText.setText(formNames[stats.formIndex]);
        statFormText.setTextColor(GameView.FORM_COLORS[stats.formIndex]);
        statCloseText.setText(String.valueOf(stats.closeCalls));
        statKoText.setText(String.valueOf(stats.enemiesBlasted));

        pauseButton.setVisibility(View.INVISIBLE);
        sound.setDucked(true);
        gameView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        gameOverOverlay.animate().cancel();
        gameOverOverlay.setAlpha(0f);
        gameOverOverlay.setVisibility(View.VISIBLE);
        gameOverOverlay.animate().alpha(1f).setStartDelay(OVERLAY_DELAY_MS).setDuration(250).start();

        gameOverPanel.setScaleX(0.8f);
        gameOverPanel.setScaleY(0.8f);
        gameOverPanel.animate().scaleX(1f).scaleY(1f).setStartDelay(OVERLAY_DELAY_MS).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2f)).start();

        blink.start();
    }

    private void hideGameOver() {
        blink.cancel();
        playAgainText.setAlpha(1f);
        if (gameOverOverlay.getVisibility() != View.VISIBLE) return;
        gameOverOverlay.animate().cancel();
        gameOverOverlay.animate().alpha(0f).setStartDelay(0).setDuration(150)
                .withEndAction(() -> gameOverOverlay.setVisibility(View.GONE)).start();
    }
}
