package com.example.kirunner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main menu: pick ADVENTURE (side-scroller + 3 boss fights) or CLASSIC RUN (the lab runner),
 * and EASY / HARD / EXTREME. Works with touch or keyboard (arrows / Tab to move, Enter to choose,
 * 1 / 2 / 3 to pick a difficulty).
 */
public class MenuActivity extends AppCompatActivity {

    public static final String PREFS = "ki_runner_prefs";      // same file MainActivity uses
    public static final String KEY_DIFFICULTY = "difficulty";
    public static final String KEY_MUTED = "muted";
    public static final String KEY_ADV_BEST = "adv_best_";
    private static final String KEY_CLASSIC_BEST = "best_score";

    private SharedPreferences prefs;
    private Difficulty difficulty;
    private TextView easy, hard, extreme, bestText, diffInfo;
    private final KeyDebounce debounce = new KeyDebounce();
    private boolean launching;     // stops a held Enter from opening the game twice

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);
        SystemUi.goImmersive(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        difficulty = Difficulty.fromName(prefs.getString(KEY_DIFFICULTY, Difficulty.EASY.name()));

        easy = findViewById(R.id.diffEasy);
        hard = findViewById(R.id.diffHard);
        extreme = findViewById(R.id.diffExtreme);
        bestText = findViewById(R.id.bestText);
        diffInfo = findViewById(R.id.diffInfo);
        easy.setOnClickListener(v -> choose(Difficulty.EASY));
        hard.setOnClickListener(v -> choose(Difficulty.HARD));
        extreme.setOnClickListener(v -> choose(Difficulty.EXTREME));

        View adventure = findViewById(R.id.btnAdventure);
        adventure.setOnClickListener(v -> {
            if (launching) return;
            launching = true;
            Intent i = new Intent(this, AdventureActivity.class);
            i.putExtra(AdventureActivity.EXTRA_DIFFICULTY, difficulty.name());
            startActivity(i);
        });
        findViewById(R.id.btnClassic).setOnClickListener(v -> {
            if (launching) return;
            launching = true;
            startActivity(new Intent(this, MainActivity.class));
        });
        adventure.requestFocus();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launching = false;
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) SystemUi.goImmersive(this);
    }

    /** dispatchKeyEvent (not onKeyDown) so the keys work even while a button has keyboard focus. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_3)
                    || (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F3);
            if (ours) {
                if (debounce.accept(code, event.getEventTime(), 0) && !launching) handleKey(code);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleKey(int keyCode) {
        switch (keyCode) {
            // Enter / Space work on the first press: click the focused button, or start ADVENTURE
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_CENTER: {
                // Enter starts the focused mode, picks a newly focused difficulty, and otherwise starts ADVENTURE
                View f = getCurrentFocus();
                boolean newDifficulty = f != null && (f == easy || f == hard || f == extreme) && !f.isSelected();
                if (f != null && (f.getId() == R.id.btnClassic || newDifficulty)) f.performClick();
                else findViewById(R.id.btnAdventure).performClick();
                return true;
            }
            case KeyEvent.KEYCODE_1: choose(Difficulty.EASY); return true;
            case KeyEvent.KEYCODE_2: choose(Difficulty.HARD); return true;
            case KeyEvent.KEYCODE_3: choose(Difficulty.EXTREME); return true;
            // hidden dev shortcut for demos and testing: F1 / F2 / F3 = straight to boss 1 / 2 / 3
            case KeyEvent.KEYCODE_F1: case KeyEvent.KEYCODE_F2: case KeyEvent.KEYCODE_F3: {
                launching = true;
                Intent i = new Intent(this, AdventureActivity.class);
                i.putExtra(AdventureActivity.EXTRA_DIFFICULTY, difficulty.name());
                i.putExtra(AdventureActivity.EXTRA_BOSS_STAGE, keyCode - KeyEvent.KEYCODE_F1);
                startActivity(i);
                return true;
            }
            default: return false;
        }
    }

    private void choose(Difficulty d) {
        difficulty = d;
        prefs.edit().putString(KEY_DIFFICULTY, d.name()).apply();
        refresh();
    }

    private void refresh() {
        TextView[] all = {easy, hard, extreme};
        for (int i = 0; i < all.length; i++) {
            boolean on = Difficulty.values()[i] == difficulty;
            all[i].setSelected(on);
            all[i].setTextColor(on ? 0xFF0C0D28 : 0xFFFFFFFF);
        }
        String[] info = {
                "7 hearts · slower bullets · great to learn the bosses",
                "5 hearts · full-speed attacks · score x2",
                "3 hearts · dense, fast bullet hell · score x3",
        };
        diffInfo.setText(info[difficulty.ordinal()]);
        bestText.setText(getString(R.string.menu_best, prefs.getInt(KEY_ADV_BEST + difficulty.name(), 0),
                prefs.getInt(KEY_CLASSIC_BEST, 0)));
    }
}
