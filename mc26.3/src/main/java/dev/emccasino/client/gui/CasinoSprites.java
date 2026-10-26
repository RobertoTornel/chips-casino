package dev.emccasino.client.gui;

import net.minecraft.resources.Identifier;

/**
 * Central sprite texture locations for Casino GUIs.
 * All paths live under assets/emc_casino/textures/gui/ and can be overridden
 * by any resource pack (including the built-in dark_mode pack).
 */
public class CasinoSprites {
    private static final String NS = "emc_casino";

    // Main casino cabinet frame (256x256 nine-patch style)
    public static final Identifier CASINO_FRAME = Identifier.fromNamespaceAndPath(NS, "textures/gui/casino_frame.png");
    // Header bar (256x32)
    public static final Identifier HEADER_BAR = Identifier.fromNamespaceAndPath(NS, "textures/gui/header_bar.png");
    // Button normal (64x20), hover (64x20)
    public static final Identifier BUTTON = Identifier.fromNamespaceAndPath(NS, "textures/gui/button.png");
    public static final Identifier BUTTON_HOVER = Identifier.fromNamespaceAndPath(NS, "textures/gui/button_hover.png");
    // Card textures (20x28)
    public static final Identifier CARD_FRONT = Identifier.fromNamespaceAndPath(NS, "textures/gui/cards/card_front.png");
    public static final Identifier CARD_BACK = Identifier.fromNamespaceAndPath(NS, "textures/gui/cards/card_back.png");
    // Roulette wheel (64x64)
    public static final Identifier ROULETTE_WHEEL = Identifier.fromNamespaceAndPath(NS, "textures/gui/roulette_wheel.png");
    // Chip (16x16)
    public static final Identifier CHIP = Identifier.fromNamespaceAndPath(NS, "textures/gui/chip.png");
    // Pachinko board bg (128x128)
    public static final Identifier PACHINKO_BOARD = Identifier.fromNamespaceAndPath(NS, "textures/gui/pachinko_board.png");
    // Balance bar (256x16)
    public static final Identifier BALANCE_BAR = Identifier.fromNamespaceAndPath(NS, "textures/gui/balance_bar.png");
}
