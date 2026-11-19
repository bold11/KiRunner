package com.example.kirunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the 3D camera. Run: right-click > Run 'ProjectionTest' (or ./gradlew test) */
public class ProjectionTest {

    private static final float EPS = 0.001f;
    private static final float W = 1080f, PLAYER_ROW = 1900f, FAR = 0.45f;

    private Projection camera(float strength) {
        Projection p = new Projection();
        p.configure(W, PLAYER_ROW, FAR);
        p.setStrength(strength);
        return p;
    }

    @Test
    public void strengthZeroIsExactlyThe2DView() {
        Projection p = camera(0f);
        p.setSway(150f);
        assertEquals(1f, p.scale(0f), EPS);
        assertEquals(1f, p.scale(PLAYER_ROW), EPS);
        assertEquals(123f, p.x(123f, 0f), EPS);
        assertEquals(987f, p.x(987f, 1500f), EPS);
    }

    @Test
    public void playerRowIsNeverDistortedIn3D() {
        Projection p = camera(1f);
        p.setSway(150f);
        assertEquals(1f, p.scale(PLAYER_ROW), EPS);
        assertEquals(180f, p.x(180f, PLAYER_ROW), EPS); // lanes line up with the buttons at the player
    }

    @Test
    public void topOfScreenUsesFarScale() {
        assertEquals(FAR, camera(1f).scale(0f), EPS);
    }

    @Test
    public void lanesConvergeTowardTheTop() {
        Projection p = camera(1f);
        float roadWidthAtTop = p.x(W, 0f) - p.x(0f, 0f);
        assertEquals(FAR * W, roadWidthAtTop, 0.05f);
        assertTrue(p.scale(500f) < p.scale(1500f));     // farther rows are smaller
        assertEquals(W / 2f, p.x(W / 2f, 0f), EPS);      // the center lane stays centered without sway
    }

    @Test
    public void cameraFlightIsSmooth() {
        assertEquals(1f - (1f - FAR) * 0.5f, camera(0.5f).scale(0f), EPS);
    }

    @Test
    public void rowsFarBelowThePlayerStayFinite() {
        float s = camera(1f).scale(100_000f);
        assertTrue(s > 1f && s < 10f);
    }
}
