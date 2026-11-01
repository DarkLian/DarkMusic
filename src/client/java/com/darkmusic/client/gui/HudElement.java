package com.darkmusic.client.gui;

/**
 * What the HUD editor can move. Navigation names it, so it lives beside Navigation rather than in the editor.
 */
public enum HudElement {
    PLAYER("lyrics.hud.player"), LYRICS("lyrics.hud.element"), CARD("hud.darkmusic.card_element");

    private final String label;

    HudElement(String label) {
        this.label = label;
    }

    /**
     * The translation key of the element's name.
     */
    public String label() {
        return label;
    }
}
