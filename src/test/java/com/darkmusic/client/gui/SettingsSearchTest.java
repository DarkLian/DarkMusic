package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsSearchTest {
    @Test
    void everyWordMustMatchTheLabelHelpOrSectionIgnoringCase() {
        String help = "Hide the HUD after music has been paused or stopped this long. HUD Visibility and Motion";
        assertTrue(SettingsSearch.matches("idle", "Hide When Idle: Off", help));
        assertTrue(SettingsSearch.matches("  HIDE   paused ", "Hide When Idle: Off", help), "Case and extra spaces do not matter");
        assertTrue(SettingsSearch.matches("motion", "Hide When Idle: Off", help), "Group names are searchable");
        assertFalse(SettingsSearch.matches("idle lyrics", "Hide When Idle: Off", help), "All words must appear");
        assertTrue(SettingsSearch.matches("封面", "在 Discord 顯示封面", ""), "Chinese labels match too");
        assertTrue(SettingsSearch.matches("", "Anything", ""));
    }
}
