package com.example.kirunner;

import java.util.ArrayList;
import java.util.List;

/**
 * One ADVENTURE stage as a grid of 16 px tiles (plain Java, unit tested).
 * Parses the strings in AdvLevels: terrain stays in the grid, while orbs, monsters, the start and the
 * boss gate are pulled out into lists. The grid is mutable (bricks break, power blocks get used).
 */
public final class AdvMap {

    public static final int T = 16;

    public static final char EMPTY = '.';
    public static final char GROUND = '#';
    public static final char BRICK = 'B';
    public static final char POWER = '?';
    public static final char POWER_HEART = 'H';
    public static final char USED = 'U';
    public static final char CLOUD = '=';
    public static final char SPIKES = '^';

    /** Something found in the level text that isn't terrain. */
    public static final class Spawn {
        public final char type;
        public final int col, row;

        Spawn(char type, int col, int row) {
            this.type = type;
            this.col = col;
            this.row = row;
        }
    }

    public final int cols, rows;
    private final char[][] grid;
    public final List<Spawn> monsters = new ArrayList<>();
    public final List<Spawn> orbs = new ArrayList<>();
    public final int startCol, startRow;
    public final int exitCol, exitRow;

    public AdvMap(String[] text) {
        rows = text.length;
        cols = text[0].length();
        grid = new char[rows][cols];
        int sc = 1, sr = rows - 3, ec = cols - 2, er = rows - 3;
        for (int r = 0; r < rows; r++) {
            if (text[r].length() != cols) throw new IllegalArgumentException("row " + r + " has the wrong length");
            for (int c = 0; c < cols; c++) {
                char ch = text[r].charAt(c);
                switch (ch) {
                    case 'S': sc = c; sr = r; ch = EMPTY; break;
                    case 'E': ec = c; er = r; ch = EMPTY; break;
                    case 'o': orbs.add(new Spawn(ch, c, r)); ch = EMPTY; break;
                    case 'g': case 'f': case 'x': monsters.add(new Spawn(ch, c, r)); ch = EMPTY; break;
                    default: break;
                }
                grid[r][c] = ch;
            }
        }
        startCol = sc;
        startRow = sr;
        exitCol = ec;
        exitRow = er;
    }

    public char get(int col, int row) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return EMPTY;
        return grid[row][col];
    }

    public void set(int col, int row, char ch) {
        if (row >= 0 && row < rows && col >= 0 && col < cols) grid[row][col] = ch;
    }

    /** Blocks movement from every side. The left and right ends of the stage act as walls. */
    public boolean isSolid(int col, int row) {
        if (col < 0 || col >= cols) return row >= 0 && row < rows;
        char ch = get(col, row);
        return ch == GROUND || ch == BRICK || ch == POWER || ch == POWER_HEART || ch == USED;
    }

    /** Cloud platforms: you can jump up through them and land on top. */
    public boolean isOneWay(int col, int row) {
        return get(col, row) == CLOUD;
    }

    public boolean isHazard(int col, int row) {
        return get(col, row) == SPIKES;
    }

    public float widthPx() {
        return cols * T;
    }

    public float heightPx() {
        return rows * T;
    }
}
