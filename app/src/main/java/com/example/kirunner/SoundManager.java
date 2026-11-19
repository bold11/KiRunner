package com.example.kirunner;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

/**
 * Sound effects (SoundPool: low latency, safe to call from the game thread)
 * and looping background music (MediaPlayer: call from the UI thread).
 * Music (original, made by tools/make_music.py): "Star Dash" for runs and stages, "Null Clash" for boss fights.
 * All audio is original, synthesized for this project.
 */
public class SoundManager {

    private static final float MUSIC_VOLUME = 0.45f;
    private static final float MUSIC_DUCKED = 0.15f;

    private final SoundPool pool;
    private final int sGo, sMove, sScore, sTick, sClose, sTransform, sCrash, sBlast, sHit;
    private MediaPlayer music;
    private final Context appContext;
    private int track;                 // raw resource of the current music
    private boolean musicWanted;       // the screen wants music playing (not paused / backgrounded)
    private volatile boolean muted;
    private boolean ducked;

    public SoundManager(Context context, boolean startMuted) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        sGo = pool.load(context, R.raw.sfx_go, 1);
        sMove = pool.load(context, R.raw.sfx_move, 1);
        sScore = pool.load(context, R.raw.sfx_score, 1);
        sTick = pool.load(context, R.raw.sfx_tick, 1);
        sClose = pool.load(context, R.raw.sfx_close, 1);
        sTransform = pool.load(context, R.raw.sfx_transform, 1);
        sCrash = pool.load(context, R.raw.sfx_crash, 1);
        sBlast = pool.load(context, R.raw.sfx_blast, 1);
        sHit = pool.load(context, R.raw.sfx_hit, 1);

        appContext = context.getApplicationContext();
        track = R.raw.music_level;
        music = createPlayer(track);
        muted = startMuted;
    }

    private void play(int soundId, float volume) {
        if (!muted && soundId != 0) pool.play(soundId, volume, volume, 1, 0, 1f);
    }

    public void playGo() { play(sGo, 0.8f); }

    public void playMove() { play(sMove, 0.5f); }

    public void playScore() { play(sScore, 0.4f); }

    public void playTick() { play(sTick, 0.7f); }

    public void playClose() { play(sClose, 0.75f); }

    public void playTransform() { play(sTransform, 0.9f); }

    public void playCrash() { play(sCrash, 1f); }

    public void playBlast() { play(sBlast, 0.55f); }

    public void playHit() { play(sHit, 0.8f); }

    // ---- ADVENTURE mode: one entry point, ids from AdvGame.SFX_* ----

    public void playAdventure(int id) {
        if (muted) return;
        switch (id) {
            case AdvGame.SFX_JUMP: rate(sMove, 0.45f, 1.25f); break;
            case AdvGame.SFX_ORB: rate(sScore, 0.35f, 1.3f); break;
            case AdvGame.SFX_STOMP: rate(sHit, 0.7f, 1.2f); break;
            case AdvGame.SFX_SHOT: rate(sBlast, 0.35f, 1.15f); break;
            case AdvGame.SFX_HIT: rate(sHit, 0.45f, 1.5f); break;
            case AdvGame.SFX_HURT: rate(sCrash, 0.6f, 1.3f); break;
            case AdvGame.SFX_TRANSFORM: rate(sTransform, 0.9f, 1f); break;
            case AdvGame.SFX_GO: rate(sGo, 0.8f, 1f); break;
            case AdvGame.SFX_WARN: rate(sTick, 0.7f, 0.8f); break;
            case AdvGame.SFX_BOOM: rate(sCrash, 0.9f, 0.8f); break;
            case AdvGame.SFX_BREAK: rate(sClose, 0.4f, 1.6f); break;
            case AdvGame.SFX_BEAM: rate(sBlast, 0.6f, 0.6f); break;
            default: break;
        }
    }

    private void rate(int soundId, float volume, float rate) {
        if (soundId != 0) pool.play(soundId, volume, volume, 1, 0, rate);
    }

    // ---- Music (UI thread) ----

    private MediaPlayer createPlayer(int resId) {
        MediaPlayer p = MediaPlayer.create(appContext, resId);
        if (p != null) {
            p.setLooping(true);
            float v = ducked ? MUSIC_DUCKED : MUSIC_VOLUME;
            p.setVolume(v, v);
        }
        return p;
    }

    /** Switches the background music (UI thread). Keeps playing if music was playing. */
    public void setTrack(int resId) {
        if (resId == track) return;
        track = resId;
        if (music != null) {
            try {
                music.stop();
            } catch (IllegalStateException ignored) {
                // not started yet
            }
            music.release();
        }
        music = createPlayer(resId);
        if (musicWanted) startMusic();
    }

    public void startMusic() {
        musicWanted = true;
        if (music == null || muted) return;
        try {
            if (!music.isPlaying()) music.start();
        } catch (IllegalStateException ignored) {
            // player not ready; skip
        }
    }

    public void pauseMusic() {
        musicWanted = false;
        if (music == null) return;
        try {
            if (music.isPlaying()) music.pause();
        } catch (IllegalStateException ignored) {
            // player not ready; skip
        }
    }

    /** Quieter music behind the Game Over panel. */
    public void setDucked(boolean duck) {
        ducked = duck;
        if (music == null) return;
        float v = ducked ? MUSIC_DUCKED : MUSIC_VOLUME;
        try {
            music.setVolume(v, v);
        } catch (IllegalStateException ignored) {
            // player not ready; skip
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean mute) {
        muted = mute;
        if (mute) pauseMusic();
        else startMusic();
    }

    public void release() {
        pool.release();
        if (music != null) {
            music.release();
            music = null;
        }
    }
}
