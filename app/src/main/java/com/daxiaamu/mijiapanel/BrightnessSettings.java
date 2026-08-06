package com.daxiaamu.mijiapanel;

final class BrightnessSettings {
    static final String PREFERENCES = "panel_display";
    static final String LOCK_BRIGHTNESS = "lock_brightness";
    static final String BRIGHTNESS_PERCENT = "brightness_percent";
    static final String BURN_IN_PROTECTION = "burn_in_protection";
    static final String DRAW_IN_DISPLAY_CUTOUT = "draw_in_display_cutout";
    static final String ABSENCE_BEHAVIOR = "absence_behavior";
    static final String ABSENCE_DELAY_SECONDS = "absence_delay_seconds";
    static final String PRESENCE_DETECTION = "presence_detection";
    static final String PANEL_ACTIVE = "panel_active";
    static final String PANEL_STATE_TOKEN = "panel_state_token";
    static final String PANEL_STATE_ACTION =
            "com.daxiaamu.mijiapanel.action.PANEL_STATE";
    static final String PRESENCE_STATE_ACTION =
            "com.daxiaamu.mijiapanel.action.PRESENCE_STATE";
    static final String EXTRA_PANEL_ACTIVE = "panel_active";
    static final String EXTRA_PANEL_STATE_TOKEN = "panel_state_token";
    static final String EXTRA_PERSON_PRESENT = "person_present";
    static final String EXTRA_PRESENCE_READY = "presence_ready";
    static final String PRESENCE_DETECTION_READY = "presence_detection_ready";
    static final String SYSTEM_BRIDGE_BOOT_COUNT = "system_bridge_boot_count";
    static final String EXTRA_SYSTEM_BRIDGE_START = "system_bridge_start";
    static final String SYSTEM_BRIDGE_ACTION =
            "com.daxiaamu.mijiapanel.action.SYSTEM_BRIDGE";
    static final String EXTRA_SYSTEM_BRIDGE_COMMAND = "command";
    static final String EXTRA_SYSTEM_BRIDGE_TOKEN = "token";
    static final int SYSTEM_BRIDGE_COMMAND_START = 1;
    static final int SYSTEM_BRIDGE_COMMAND_GO_TO_SLEEP = 3;
    static final int SYSTEM_BRIDGE_COMMAND_PROBE = 4;
    static final int SYSTEM_BRIDGE_COMMAND_WAKE_UP = 5;
    static final String PERSON_PRESENT = "person_present";
    static final int DEFAULT_BRIGHTNESS_PERCENT = 50;
    static final boolean DEFAULT_BURN_IN_PROTECTION = false;
    static final boolean DEFAULT_DRAW_IN_DISPLAY_CUTOUT = false;
    static final int ABSENCE_SCREEN_OFF = 0;
    static final int ABSENCE_MINIMUM_BRIGHTNESS = 1;
    static final int DEFAULT_ABSENCE_BEHAVIOR = ABSENCE_SCREEN_OFF;
    static final int MIN_ABSENCE_DELAY_SECONDS = 5;
    static final int MAX_ABSENCE_DELAY_SECONDS = 300;
    static final int DEFAULT_ABSENCE_DELAY_SECONDS = 30;
    static final boolean DEFAULT_PRESENCE_DETECTION = false;

    private BrightnessSettings() {
    }

    static int clampPercent(int value) {
        return Math.max(1, Math.min(100, value));
    }

    static int clampAbsenceDelaySeconds(int value) {
        int clamped = Math.max(
                MIN_ABSENCE_DELAY_SECONDS,
                Math.min(MAX_ABSENCE_DELAY_SECONDS, value));
        return Math.round(clamped / 5.0f) * 5;
    }
}
