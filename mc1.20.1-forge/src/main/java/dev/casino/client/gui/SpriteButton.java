package dev.casino.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button that draws itself from the packaged button sprites instead of the vanilla
 * grey slab, so the whole cabinet reads as one designed surface and a resource pack can
 * restyle buttons along with everything else.
 *
 * <p>Three visual states, in priority order: disabled (dimmed), hovered/focused
 * ({@code button_hover.png}), idle ({@code button.png}).
 */
public class SpriteButton extends Button {

    public static final int LABEL_PRIMARY = 0xFFFFD86B;
    public static final int LABEL_NORMAL  = 0xFFEDEDED;
    public static final int LABEL_RED     = 0xFFFF7B7B;
    public static final int LABEL_BLACK   = 0xFFB9B9B9;
    public static final int LABEL_GREEN   = 0xFF7DDE7D;

    private final int labelColor;

    protected SpriteButton(int x, int y, int w, int h, Component label, OnPress onPress, int labelColor) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.labelColor = labelColor;
    }

    public static SpriteButton of(int x, int y, int w, int h, String label, OnPress onPress) {
        return new SpriteButton(x, y, w, h, Component.literal(label), onPress, LABEL_NORMAL);
    }

    public static SpriteButton of(int x, int y, int w, int h, String label, int color, OnPress onPress) {
        return new SpriteButton(x, y, w, h, Component.literal(label), onPress, color);
    }

    public static SpriteButton primary(int x, int y, int w, int h, String label, OnPress onPress) {
        return new SpriteButton(x, y, w, h, Component.literal(label), onPress, LABEL_PRIMARY);
    }

    public static SpriteButton colored(int x, int y, int w, int h, String label, int color, OnPress onPress) {
        return new SpriteButton(x, y, w, h, Component.literal(label), onPress, color);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hot = this.isHoveredOrFocused() && this.active;
        var sprite = hot ? CasinoSprites.BUTTON_HOVER : CasinoSprites.BUTTON;

        graphics.blit(sprite, this.getX(), this.getY(), this.width, this.height,
                0f, 0f, 64, 20, 64, 20);
        graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                this.getMessage(), this.getX() + this.width / 2,
                this.getY() + (this.height - 8) / 2,
                this.active ? this.labelColor : 0xFF9A9A9A);
    }
}
