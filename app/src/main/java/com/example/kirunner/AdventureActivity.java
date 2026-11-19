package com.example.kirunner;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;

/**
 * ADVENTURE mode (landscape): hosts AdventureView and forwards the keyboard to it.
 * A / D or arrows move, Space / W / Up jump, right mouse button (or J / K) fires a ki blast,
 * Enter confirms, P / Esc pause, V toggles 2D / 2.5D.
 */
public class AdventureActivity extends AppCompatActivity implements AdventureView.Host {

    public static final String EXTRA_DIFFICULTY = "difficulty";
    /** Dev shortcut (menu: F1 / F2 / F3): start directly at that stage's boss fight. */
    public static final String EXTRA_BOSS_STAGE = "boss_stage";

    private AdventureView view;
    private SoundManager sound;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MenuActivity.PREFS, MODE_PRIVATE);
        Difficulty d = Difficulty.fromName(getIntent().getStringExtra(EXTRA_DIFFICULTY));
        sound = new SoundManager(this, prefs.getBoolean(MenuActivity.KEY_MUTED, false));
        view = new AdventureView(this);
        view.setup(this, sound, d, getIntent().getIntExtra(EXTRA_BOSS_STAGE, -1));
        setContentView(view);
        SystemUi.goImmersive(this);
    }

    /** Every key goes to the game first (works no matter which view has focus). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if ((action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) && view.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sound.startMusic();
    }

    @Override
    protected void onPause() {
        view.pause();
        sound.pauseMusic();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) SystemUi.goImmersive(this);
    }

    @Override
    protected void onDestroy() {
        sound.release();
        super.onDestroy();
    }

    // ---- AdventureView.Host

    @Override
    public void exitToMenu() {
        finish();
    }

    @Override
    public void saveBest(Difficulty d, int score) {
        String key = MenuActivity.KEY_ADV_BEST + d.name();
        if (score > prefs.getInt(key, 0)) prefs.edit().putInt(key, score).apply();
    }

    @Override
    public int best(Difficulty d) {
        return prefs.getInt(MenuActivity.KEY_ADV_BEST + d.name(), 0);
    }
}
