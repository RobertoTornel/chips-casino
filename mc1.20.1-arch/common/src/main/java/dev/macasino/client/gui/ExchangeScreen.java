package dev.macasino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.macasino.api.PriceTable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ExchangeScreen extends BaseCasinoScreen {

    private static final int ROW_H = 14;
    private static final int COL_W = 150;

    private record Row(String id, long base, long sell, long buy) {
    }

    private final List<Row> rows = new ArrayList<>();
    private double sellMultiplier = PriceTable.sellMultiplier();
    private double buyMultiplier = PriceTable.buyMultiplier();
    private String currency = "Chips";
    private String symbol = "\u25C8";

    public ExchangeScreen(BlockPos pos, String initialStateJson, long balance) {
        super(Component.literal("Exchange"), pos, initialStateJson, balance);
        this.bgWidth = 340;
        this.bgHeight = 230;
    }

    @Override
    protected void initGameWidgets() {
        int bottom = topPos + bgHeight - 42;
        addRenderableWidget(SpriteButton.of(contentX(), bottom, 72, 18, "\u00a7aSell Hand",
                b -> sendAction("SELL_MAIN_HAND", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 78, bottom, 68, 18, "\u00a7aSell All",
                b -> sendAction("SELL_ALL_TRADABLE", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 152, bottom, 68, 18, "\u00a7bDiamond",
                b -> buy("minecraft:diamond", 1)));
        addRenderableWidget(SpriteButton.of(contentX() + 226, bottom, 62, 18, "\u00a7bEmerald",
                b -> buy("minecraft:emerald", 1)));
    }

    private void buy(String itemId, int count) {
        JsonObject json = new JsonObject();
        json.addProperty("itemId", itemId);
        json.addProperty("count", count);
        sendAction("EXCHANGE_BUY", GSON.toJson(json));
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj == null) return;
            if (obj.has("balance")) playerBalance = obj.get("balance").getAsLong();
            if (obj.has("sellMultiplier")) sellMultiplier = obj.get("sellMultiplier").getAsDouble();
            if (obj.has("buyMultiplier")) buyMultiplier = obj.get("buyMultiplier").getAsDouble();
            if (obj.has("currency")) currency = obj.get("currency").getAsString();
            if (obj.has("symbol")) symbol = obj.get("symbol").getAsString();
            rows.clear();
            JsonArray arr = obj.has("showcase") ? obj.getAsJsonArray("showcase") : null;
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    rows.add(new Row(
                            o.get("id").getAsString(),
                            o.get("base").getAsLong(),
                            o.get("sell").getAsLong(),
                            o.get("buy").getAsLong()));
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int contentTop = contentY() + 14;
        g.drawString(this.font, "\u00a7eEXCHANGE", contentX(), contentTop, COLOR_TEXT_GOLD, false);
        g.drawString(this.font, "The house buys at 0.8x and sells at 1.2x — the spread is the game.",
                contentX(), contentY() + 16, COLOR_EXCHANGE, false);

        int headY = contentTop + 30;
        g.drawString(this.font, "\u00a7aSELL", contentX(), headY, COLOR_TEXT_GREEN, false);
        g.drawString(this.font, "\u00a7cBUY", contentX() + COL_W + 8, headY, COLOR_TEXT_RED, false);

        int y = headY + ROW_H + 2;
        int maxRows = Math.min(rows.size(), 10);
        for (int i = 0; i < maxRows; i++) {
            Row r = rows.get(i);
            int rowY = y + i * ROW_H;
            if (i % 2 == 0) {
                g.fill(contentX(), rowY - 2, contentX() + COL_W, rowY + ROW_H - 3, 0x22FFFFFF);
                g.fill(contentX() + COL_W + 8, rowY - 2,
                        contentX() + COL_W + 8 + COL_W, rowY + ROW_H - 3, 0x22FFFFFF);
            }
            String label = shortId(r.id());
            g.drawString(this.font, label, contentX(), rowY, COLOR_TEXT_WHITE, false);
            g.drawString(this.font, "\u00a7a+" + r.sell(), contentX() + COL_W - 40, rowY, COLOR_TEXT_GREEN, false);
            g.drawString(this.font, label, contentX() + COL_W + 8, rowY, COLOR_TEXT_WHITE, false);
            g.drawString(this.font, "\u00a7c-" + r.buy(), contentX() + COL_W + 8 + COL_W - 40, rowY, COLOR_TEXT_RED, false);
        }
        if (rows.isEmpty()) {
            g.drawString(this.font, "\u00a77No prices loaded", contentX(), y, 0xFF888888, false);
        }

        int footY = topPos + bgHeight - 40;
        g.drawString(this.font,
                "\u00a77Sell at \u00a7a" + fmtPercent(sellMultiplier)
                        + "\u00a77   \u00b7   Buy at \u00a7c" + fmtPercent(buyMultiplier),
                contentX(), footY, COLOR_TEXT_WHITE, false);
        g.drawString(this.font,
                "\u00a77Sell then buy back always loses — that is the house edge.",
                contentX(), footY + 12, 0xFF999999, false);
    }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        String path = i >= 0 ? id.substring(i + 1) : id;
        path = path.replace('_', ' ');
        if (path.length() > 16) path = path.substring(0, 15) + "\u2026";
        return path;
    }

    private static String fmtPercent(double d) {
        return Math.round(d * 100) + "%";
    }
}
