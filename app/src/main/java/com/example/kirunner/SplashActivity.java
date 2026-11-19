package com.example.kirunner;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * STAGE 5: Fake "loading" screen with a progress bar, then opens the game automatically.
 * While it loads, the hero cycles through all five transformations as a teaser.
 */
public class SplashActivity extends AppCompatActivity {

    private static final int LOAD_TIME_MS = 3000; // total simulated load time
    /** true = the splash opens the main menu; false = straight into CLASSIC RUN (exactly the lab spec). */
    private static final boolean OPEN_MENU = true;
    private static final int STEPS = 100;
    private static final long HERO_CYCLE_MS = 600;
    private static final int[] HERO_FORMS = {
            R.drawable.player, R.drawable.player_lv2, R.drawable.player_lv3,
            R.drawable.player_lv4, R.drawable.player_lv5
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar progressBar;
    private TextView percentText;
    private ImageView hero;
    private ObjectAnimator floatAnim;
    private int progress = 0;
    private int heroIndex = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress++;
            progressBar.setProgress(progress);
            percentText.setText(getString(R.string.percent_format, progress));
            if (progress >= STEPS) {
                launchGame();
            } else {
                handler.postDelayed(this, LOAD_TIME_MS / STEPS);
            }
        }
    };

    private final Runnable cycleHero = new Runnable() {
        @Override
        public void run() {
            heroIndex = (heroIndex + 1) % HERO_FORMS.length;
            hero.animate().alpha(0.15f).setDuration(110).withEndAction(() -> {
                hero.setImageResource(HERO_FORMS[heroIndex]);
                hero.animate().alpha(1f).setDuration(160).start();
            }).start();
            handler.postDelayed(this, HERO_CYCLE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        SystemUi.goImmersive(this);

        progressBar = findViewById(R.id.progressBar);
        percentText = findViewById(R.id.percentText);
        hero = findViewById(R.id.splashPlayer);
        progressBar.setMax(STEPS);

        animateIntro();
        handler.postDelayed(tick, LOAD_TIME_MS / STEPS);
        handler.postDelayed(cycleHero, HERO_CYCLE_MS);
    }

    private void animateIntro() {
        float dp = getResources().getDisplayMetrics().density;
        View logo = findViewById(R.id.splashLogo);
        View tagline = findViewById(R.id.splashTagline);

        logo.setAlpha(0f);
        logo.setTranslationY(-40 * dp);
        logo.setScaleX(0.85f);
        logo.setScaleY(0.85f);
        logo.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(700).setInterpolator(new OvershootInterpolator(1.6f)).start();

        tagline.setAlpha(0f);
        tagline.animate().alpha(1f).setStartDelay(400).setDuration(500).start();

        floatAnim = ObjectAnimator.ofFloat(hero, View.TRANSLATION_Y, -10 * dp, 10 * dp);
        floatAnim.setDuration(1300);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        floatAnim.start();
    }

    @SuppressWarnings("deprecation")
    private void launchGame() {
        startActivity(new Intent(this, OPEN_MENU ? MenuActivity.class : MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish(); // so Back from the game doesn't return to the splash screen
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick); // stop the timers if the user leaves early
        handler.removeCallbacks(cycleHero);
        hero.animate().cancel();
        if (floatAnim != null) floatAnim.cancel();
        super.onDestroy();
    }
}
