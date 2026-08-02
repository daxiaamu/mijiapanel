package com.daxiaamu.mijiapanel;

final class BrightnessSettings {
    static final String PREFERENCES = "panel_display";
    static final String LOCK_BRIGHTNESS = "lock_brightness";
    static final String BRIGHTNESS_PERCENT = "brightness_percent";
    static final String BURN_IN_PROTECTION = "burn_in_protection";
    static final String DRAW_IN_DISPLAY_CUTOUT = "draw_in_display_cutout";
    static final int DEFAULT_BRIGHTNESS_PERCENT = 50;
    static final boolean DEFAULT_BURN_IN_PROTECTION = false;
    static final boolean DEFAULT_DRAW_IN_DISPLAY_CUTOUT = false;

    private BrightnessSettings() {
    }

    static int clampPercent(int value) {
        return Math.max(1, Math.min(100, value));
    }
}
