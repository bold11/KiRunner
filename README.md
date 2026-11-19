# Ki Runner (PROG1415 Endless Runner Lab)

Two games in one app (the whole app runs in **landscape**), picked from the main menu with a difficulty (EASY / HARD / EXTREME):
- **ADVENTURE**: a pixel-art side-scroller (2D or 2.5D) with micro monsters and 3 bullet-hell boss fights.
- **CLASSIC RUN**: a 7-lane endless runner: dodge, jump over and blast the falling blocks (below).

**Changed from the original lab spec on purpose:** landscape instead of portrait, 7 lanes instead of 3, rows of
1-3 blocks (always at least 3 free lanes), and a jump. The spacing rule (player height + buffer) still applies
between rows, and the hitbox, scoring and release rules are unchanged.

The classic game is an endless runner for Android (Java). It meets every lab requirement and adds a live 2D/3D camera, unit-tested rules, a dev overlay, transformations, audio, pause/resume and more.

## For the teacher: try both games in 5 minutes
**Quickest (no building):** download `KiRunner.apk` from this repository's **Releases** page and open it on an
Android phone (allow "Install unknown apps" when asked) or drag it onto a running Android Studio emulator.
**From source:** see Setup below, then press Run. (Android only: it does not run on iPhone.)

1. The splash screen loads, then the **main menu** (landscape). Pick a difficulty: EASY / HARD / EXTREME.
2. **CLASSIC RUN** (the lab runner, upgraded): move with LEFT / RIGHT (or A / D, swipes), **JUMP** over blocks,
   fire a **KI** blast to destroy them. Every block that passes or is destroyed is +1. Press **2.5D** (top right)
   to fly the camera into perspective; hold the **SCORE** box to see the hitboxes and the spacing rule live.
3. **ADVENTURE**: a pixel-art side-scroller. Run (A / D), jump (Space, press again in the air for a double jump),
   stomp or blast the monsters, collect ki orbs to transform, and reach the gate: a bullet-hell **boss fight**
   follows (fly with W A S D, blasts aim at the boss). Three stages, three bosses. **V** switches 2D / 2.5D.
   Shortcut for a quick look: on the menu, **F1 / F2 / F3** jump straight to boss 1 / 2 / 3 (keyboard / emulator).
4. Music: original tracks, "Star Dash" in the stages and "Null Clash" in boss fights.

In the Android Studio emulator, turn on **Hardware Input** (keyboard icon in the Running Devices toolbar) for exact
keyboard controls, and click the emulator screen once so it receives the keys.

## Setup (open the project directly)
1. Use **Android Studio** (a late-2025 version or newer). VS Code's Java tools can't load Android projects, so the red errors you see there are expected.
2. Unzip, then Android Studio > **File > Open** > select the `KiRunner` folder (the one containing `settings.gradle`) > Trust Project.
3. Wait for the Gradle sync to finish. The first time it downloads Gradle 9.3.1, the Android Gradle Plugin 8.12.0 and SDK 36; accept if it asks to install SDK Platform 36.
4. Tools > Device Manager > create a phone emulator, then press **Run**.

Command line (any terminal, including VS Code's): `gradlew.bat assembleDebug` on Windows, or `./gradlew assembleDebug`. This needs the Android SDK that Android Studio installs, and `ANDROID_HOME` set to it.

**If the sync fails with a version error** (for example on an older Android Studio): create a new project (Empty Views Activity, name `KiRunner`, package `com.example.kirunner`, Java, API 24) and copy this project's `app/src/main` and `app/src/test` folders over the generated ones. That project's build files will match your Android Studio.

## Controls
| Action | How |
|---|---|
| Move one lane | LEFT / RIGHT buttons, swipe left / right, or A / D / ← → |
| **Jump** (hop over blocks) | JUMP button, swipe up, or Space / W / ↑ |
| **Energy blast** | **KI button, tap the game screen, right-click, or Enter / J**: a ki blast flies up your lane and destroys the first enemy block it hits (+1). Short cooldown, faster in higher forms |
| **2D / 2.5D view** | **2.5D button (top-right)**: the camera flies between views, remembered between launches |
| Pause | Pause button (top-left). The game also auto-pauses if you leave the app |
| Resume | Tap the pause screen: 3-2-1 countdown, then GO! |
| Sound on/off | Speaker button (top-right) |
| Dev overlay | **Hold the SCORE box** |
| Play again | Tap the screen or either button on Game Over |

## ADVENTURE mode
**Goal:** cross 3 stages (Sky Meadows, Carnival Clouds, Null Citadel). Each ends at a boss gate. Beat all 3 bosses to win.

| Action | Keyboard | Touch |
|---|---|---|
| Run | **A D** or **← →** | ◀ ▶ |
| Jump / double jump (hold for higher) | **Space**, W or ↑ | JUMP |
| Ki blast (hold for auto-fire) | **Right mouse button (mouse 2)**, a left click on the game, or J / K | KI, or tap the screen |
| Fly up / down (boss fights) | W S or ↑ ↓ | ▲ ▼ |
| Confirm (start, retry, resume) | Enter | tap |
| Pause / resume | P or Esc (Back = pause, Back again = quit) | II |
| 2D / 2.5D view | V | 2.5D button |
| Show the last key pressed (troubleshooting) | F9 | |

Keys are matched by key code and by the physical key, so they also work with non-English keyboard layouts.
In the Android Studio emulator, click once on the emulator screen first so it receives the keyboard.

**Why the keyboard needs special handling in the emulator:** the Android Studio emulator panel never sends
"key released". Every key arrives as an instant tap (down + up within 2 ms). Measured on this PC: a held key is
one tap, then the Windows repeat delay (500 ms), then a tap every 33 ms; separate presses by a person are 150+ ms
apart. `AdvInput` decodes this like a remote-play client would: taps 33 ms apart = held; a tap ~500 ms after a
press = the first repeat (it only counts as a new press if no repeat follows within 60 ms, so holding Space never
double-jumps); anything else = a new press (quick double-jump taps always work). A held key is released 90 ms after
its repeats stop. Windows only repeats the last key pressed, so a run interrupted by Space / W / J keeps going
while the hero is in the air or the other key is held, then stops by itself. Menu keys (Enter, P, Esc, V) and
Classic's lane moves use `KeyDebounce` so a held key acts once (or at a steady pace).
`AdvInputTest` replays these measured timings. A real keyboard (proper down / up) skips all of this, and so does
the emulator with **Hardware Input** turned on in the Running Devices toolbar, if it forwards real key releases.
With a hardware keyboard attached, the touch buttons start hidden.

- **Levels:** stomp or blast micro monsters (blob, flit swoops at you, spikeball is blast-only), hit gold power
  blocks from below (ki orbs or a heart), break bricks, avoid spikes and pits (falling costs a heart, you respawn on
  the last safe ground). Stomping chains multiply the score.
- **Forms:** ki from orbs and defeated monsters unlocks BASE (yellow hair) > STORM > JADE > VOID > ZENITH (white hair).
  Every form fires faster and harder.
- **Bosses:** the hero rides the cloud and flies freely; only the glowing core on the chest can be hit (bullet-hell
  style). Attacks come from every side: rain from the top, bullet walls from the left and right with a gap, rings
  closing in around you, beams that flash a warning before they fire, homing orbs, aimed cards, floor shockwaves.
  Blasts auto-aim at the boss. At half HP a boss becomes ENRAGED (faster, denser; the final boss runs two attacks at once).
  1. **GRANITE WARDEN**: stone golem knight. Slams, rock rain, side walls, beams.
  2. **JINX**: jester phantom. Teleports, aimed cards, rings, spirals, homing orbs.
  3. **THE NULL KING**: hooded shadow monarch. Bullets from all four edges, beam grids, closing rings.
- **Checkpoints:** Game Over in a level restarts the stage; Game Over at a boss restarts the boss fight.
- **Difficulty:** EASY 7 hearts, slower bullets, weaker bosses; HARD 5 hearts, score x2; EXTREME 3 hearts,
  dense fast bullet hell, tougher bosses, score x3 (`Difficulty.java`). In CLASSIC it changes the falling speed
  only; the spacing rule is unchanged.
- **Dev shortcut:** on the menu, F1 / F2 / F3 start straight at boss 1 / 2 / 3.
- **Look:** the background is an animated pixel-art space scene (`tools/make_background.py` turns a GIF into
  `bg_space.png`; source in `art_source/`). In 2.5D you also get floating islands behind the level, thick blocks
  with lit top faces, ground shadows, space dust at three depths, and crystal spires passing in front of you.
  Both views have glow lighting, a vignette, after-images in higher forms, squash and stretch, dust puffs,
  score popups, stage fades and letterbox bars for boss entrances.
- **Editing levels:** change the chunks in `tools/make_levels.py`, run it, rebuild. Art: `tools/make_adventure_sprites.py`.
- **Code:** `AdvGame` (rules, plain Java), `AdvBoss` (bosses and attack patterns), `AdvMap` / `AdvLevels` (tiles),
  `AdvInput` (keyboard + touch), `AdvRenderer` (pixel drawing), `AdventureView` / `AdventureActivity` (screen, HUD).

**Lab note:** the splash now opens the menu. For grading CLASSIC exactly to the spec (game starts automatically
after the splash), set `OPEN_MENU = false` in `SplashActivity`.

## The 2.5D view (how it works)
The game logic always runs in flat 2D coordinates. `Projection` is a camera that only changes how things are **drawn**:
rows higher on the screen count as farther away, so with `scale = depth / (depth + distance)` the lanes converge,
far obstacles shrink and fade (depth fog), and the road grid, speed lines and trails follow the perspective. The camera
also sways as you change lanes. It is 2.5D: the sprites stay flat pixel art, but they stand in a perspective world
and cast soft shadows on the road (enemies hover above theirs, blasts light the road as they fly).

The player's row is never distorted (scale = 1), so the hitboxes, the spacing rule, the score line and the buttons
are identical in both views. The camera strength animates from 0 (flat) to 1 (3D), and at 0 it is mathematically
the exact 2D view (covered by `ProjectionTest`). The app starts in 2.5D; set
`DEFAULT_3D = false` in MainActivity to start in the flat 2D view (exactly the lab spec).

**Why not "real" 3D (OpenGL / Unity)?** The lab requires a SurfaceView + GameThread structure, image-based
square obstacles and a rectangular player, all in an Android Studio Java project. A 3D engine would replace all of
that. The perspective camera gives the 3D look while keeping every requirement.

## Requirement map
| Requirement | Where |
|---|---|
| Android phones, portrait only, touch only | Manifest `screenOrientation="portrait"`; buttons + swipes |
| Splash: progress bar, "The game will start momentarily...", simulated load, auto-transition, `finish()` | SplashActivity |
| Game starts automatically | GameView.restart() runs as soon as the screen is ready |
| Background image, 3 lanes, obstacles, player | GameView.render (through the camera) |
| Player starts center, moves left/right, can't pass outer lanes, sits above the bar | GameRules.START_LANE / clampLane, Player.update |
| Control bar `[LEFT] SCORE [RIGHT]` | activity_main.xml, MainActivity |
| Obstacles: random lane, continuous fall from the top, square image | GameView.update, Obstacle, obstacle.png |
| Released once top passes top of control bar | GameRules.passedControlBar |
| Spacing >= player height + buffer (buffer = 2x player height) | GameRules.minSpawnGap / spawnGap / canSpawn |
| Collision stops the game, with a forgiveness zone | GameRules.isCollision, HITBOX_INSET = 15% per side |
| Point when an obstacle passes; obstacles behind the bar still count | GameView.onObstaclePassed (blasting an enemy also gives +1, see Beyond the spec) |
| Game Over text, final score stays in the score box, tap/button restarts at 0 | MainActivity.showGameOver, GameView.restart |

## Beyond the spec
1. **2D/2.5D perspective camera** with a smooth camera flight, depth fog, a road grid, ground shadows and camera sway.
2. **Unit-tested rules and camera.** 58 JUnit tests over `GameRules`, `Hitbox`, `RunStats`, `Projection`, `Difficulty`,
   `AdvGame`, `AdvBoss` and a planning bot that plays and finishes all 3 ADVENTURE stages with the real physics.
3. **Dev overlay** (hold SCORE). Image box (red) vs forgiving hitbox (green), spawn gate, score line, FPS, speed, view.
4. **Transformations.** BASE > STORM (8) > JADE (16) > VOID (26) > ZENITH (38): new hair and aura colors, burst, banner, form meter.
5. **Close calls.** When the forgiveness zone saves you: "CLOSE!" and brief slow-motion. The score rule is unchanged.
6. **Pause, auto-pause, 3-2-1 resume.**
7. **Audio.** SoundPool effects plus two original music tracks composed and synthesized for this project
   (`tools/make_music.py`): "Star Dash" (150 BPM) for runs and stages, "Null Clash" (170 BPM) for boss fights,
   switching automatically. Mute switch in Classic.
8. **Game Over stats** (time, highest form, close calls, KOs) and best score saved on the device.
9. **Game feel.** Lane slide with lean, aura pulse, trails, speed lines, explosion with screen shake, haptics, difficulty ramp, notch-safe full screen.
10. **Energy blast.** Tap to fire; each form fires faster (`GameRules.BLAST_COOLDOWNS`). A hit destroys the enemy block with a pop and
    counts +1. The spawn gate keeps tracking a blasted block, so the spacing rule still holds. KOs are shown on Game Over.
11. **Pixel art.** The hero (5 forms) and the enemy block are original pixel art, generated by `tools/make_pixel_sprites.py`.

## Architecture
| File | Job |
|---|---|
| `GameRules`, `Hitbox`, `RunStats`, `Projection` | Pure Java rules, data and camera math (unit tested, no Android) |
| `Player`, `Obstacle`, `Blast` | Game objects: position, images, hitboxes, the jump |
| `GameThread` | 60 FPS loop: update, draw, sleep; measures FPS |
| `GameView` | SurfaceView: spawning, collision, scoring, forms, pause, slow-mo, camera, dev overlay, swipes |
| `FxLayer` | Visual effects only (particles, shockwave, shake, flash, speed lines) |
| `SoundManager` | Sound effects and music |
| `MainActivity` | HUD, control bar, pause and Game Over panels, best score, view and sound settings |
| `SplashActivity` | Simulated loading screen |
| `SystemUi` | Full-screen mode |

## Testing
- **Unit tests:** right-click the `test` folder > Run 'Tests' (or `./gradlew test`).
- **Verified before hand-off:** the code compiles; 58/58 unit tests pass (including the bot finishing every stage);
  ADVENTURE (menu, level, all 3 bosses, 2D and 2.5D, pause, Game Over) was run on the emulator with keyboard input; the blast build was run on an emulator (Medium Phone, API 37); the real game logic ran headlessly with a bot for 300 s
  of game time (switching to 3D and back mid-run), checking spacing, release, forms, pause/countdown, Game Over and restart
  every frame; and the draw calls of the real `render()` were recorded and replayed to images to check the 2D and 3D views.
- **Not verified:** a real phone or emulator (layout spacing, audio playback, performance). Run it before recording.

## Tuning
- Rules: top of `GameRules.java` (`HITBOX_INSET`, `BUFFER_MULTIPLIER`, speeds, `FORM_THRESHOLDS`)
- Feel and camera: top of `GameView.java` (`FAR_SCALE` = 3D strength, `SWAY`, slow-motion, banners)

## Demo video (about 2 min)
1. Launch: splash cycles the forms, bar fills, game auto-starts with GO!
2. Play in 2D with the buttons, then swipes; score climbs, form meter fills, STORM at 8.
3. Tap **3D**: the camera flies into perspective. Keep playing. Tap again to fly back.
4. Pause, leave the app, come back: still paused; tap: 3-2-1.
5. Hold SCORE: dev overlay. Show red vs green boxes (forgiveness), spawn gate (spacing) and score line, in both views.
6. Crash: explosion, Game Over panel, final score still in the score box, stats. Tap: score resets to 0.

## Credits
All art and audio are original, made for this project, except the ADVENTURE space background
(`art_source/space_background.gif`), supplied by the project owner from Pixilart; check its author's terms before publishing. Pixel sprites: `python tools/make_pixel_sprites.py`;
blast sounds: `python tools/make_blast_sounds.py`; music: `python tools/make_music.py` (needs ffmpeg). Fonts: Bangers and Rajdhani, SIL Open Font License 1.1 (see FONT_LICENSE_OFL.txt).
