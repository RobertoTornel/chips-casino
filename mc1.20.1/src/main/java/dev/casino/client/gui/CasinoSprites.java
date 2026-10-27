package dev.casino.client.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * Central sprite texture locations for Casino GUIs, with their native pixel sizes.
 *
 * All paths live under assets/casino/textures/gui/ and can be overridden by any
 * resource pack (including the built-in dark_mode pack). Native sizes are needed
 * because 1.20.1's {@code GuiGraphics.blit} takes an explicit texture region rather
 * than normalised UVs.
 */
public final class CasinoSprites {
    private static final String NS = "casino";

    public static final ResourceLocation CASINO_FRAME  = new ResourceLocation(NS, "textures/gui/casino_frame.png");
    public static final ResourceLocation HEADER_BAR    = new ResourceLocation(NS, "textures/gui/header_bar.png");
    public static final ResourceLocation BALANCE_BAR   = new ResourceLocation(NS, "textures/gui/balance_bar.png");
    public static final ResourceLocation BUTTON        = new ResourceLocation(NS, "textures/gui/button.png");
    public static final ResourceLocation BUTTON_HOVER  = new ResourceLocation(NS, "textures/gui/button_hover.png");
    public static final ResourceLocation CARD_FRONT    = new ResourceLocation(NS, "textures/gui/cards/card_front.png");
    public static final ResourceLocation CARD_BACK     = new ResourceLocation(NS, "textures/gui/cards/card_back.png");
    public static final ResourceLocation ROULETTE_WHEEL= new ResourceLocation(NS, "textures/gui/roulette_wheel.png");
    public static final ResourceLocation CHIP          = new ResourceLocation(NS, "textures/gui/chip.png");
    public static final ResourceLocation PACHINKO_BOARD= new ResourceLocation(NS, "textures/gui/pachinko_board.png");
    public static final ResourceLocation SLOT_REEL_WINDOW = new ResourceLocation(NS, "textures/gui/slot_reel_window.png");
    public static final ResourceLocation SYMBOL_CHERRY = new ResourceLocation(NS, "textures/gui/slot_symbol_cherry.png");
    public static final ResourceLocation SYMBOL_LEMON = new ResourceLocation(NS, "textures/gui/slot_symbol_lemon.png");
    public static final ResourceLocation SYMBOL_BELL = new ResourceLocation(NS, "textures/gui/slot_symbol_bell.png");
    public static final ResourceLocation SYMBOL_BAR = new ResourceLocation(NS, "textures/gui/slot_symbol_bar.png");
    public static final ResourceLocation SYMBOL_SEVEN = new ResourceLocation(NS, "textures/gui/slot_symbol_seven.png");

    // Native pixel sizes, used to map the full texture into an arbitrary destination box.
    public static final int CASINO_FRAME_W = 256, CASINO_FRAME_H = 256;
    public static final int HEADER_BAR_W = 256,   HEADER_BAR_H = 32;
    public static final int BALANCE_BAR_W = 256,  BALANCE_BAR_H = 16;
    public static final int BUTTON_W = 64,        BUTTON_H = 20;
    public static final int CARD_W = 20,          CARD_H = 28;
    public static final int ROULETTE_WHEEL_W = 64, ROULETTE_WHEEL_H = 64;
    public static final int CHIP_W = 16,          CHIP_H = 16;
    public static final int PACHINKO_BOARD_W = 128, PACHINKO_BOARD_H = 128;
    public static final int SLOT_REEL_WINDOW_W = 96, SLOT_REEL_WINDOW_H = 96;
    public static final int SYMBOL_W = 16, SYMBOL_H = 16;

    private CasinoSprites() {
    }
}
