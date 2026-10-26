package dev.emccasino.client.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * Central sprite texture locations for Casino GUIs.
 * All paths live under assets/emc_casino/textures/gui/ and can be overridden
 * by any resource pack (including the built-in dark_mode pack).
 */
public class CasinoSprites {
    private static final String NS = "emc_casino";

    // Main casino cabinet frame (256x256 nine-patch style)
    public static final ResourceLocation CASINO_FRAME = new ResourceLocation(NS, "textures/gui/casino_frame.png");
    // Header bar (256x32)
    public static final ResourceLocation HEADER_BAR = new ResourceLocation(NS, "textures/gui/header_bar.png");
    // Button normal (64x20), hover (64x20)
    public static final ResourceLocation BUTTON = new ResourceLocation(NS, "textures/gui/button.png");
    public static final ResourceLocation BUTTON_HOVER = new ResourceLocation(NS, "textures/gui/button_hover.png");
    // Card textures (20x28)
    public static final ResourceLocation CARD_FRONT = new ResourceLocation(NS, "textures/gui/cards/card_front.png");
    public static final ResourceLocation CARD_BACK = new ResourceLocation(NS, "textures/gui/cards/card_back.png");
    // Roulette wheel (64x64)
    public static final ResourceLocation ROULETTE_WHEEL = new ResourceLocation(NS, "textures/gui/roulette_wheel.png");
    // Chip (16x16)
    public static final ResourceLocation CHIP = new ResourceLocation(NS, "textures/gui/chip.png");
    // Pachinko board bg (128x128)
    public static final ResourceLocation PACHINKO_BOARD = new ResourceLocation(NS, "textures/gui/pachinko_board.png");
    // Balance bar (256x16)
    public static final ResourceLocation BALANCE_BAR = new ResourceLocation(NS, "textures/gui/balance_bar.png");
}
