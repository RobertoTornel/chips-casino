package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.casino.api.PriceTable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Exchange screen: sell items for money, buy items with money.
 *
 * <p>Layout is two columns — "Sell" on the left, "Buy" on the right — because the whole
 * point is the spread between them. Both columns list the same items side by side so the
 * player can see, on one row, that selling a diamond pays less than buying one costs.
 * That visual adjacency is the economy lesson.
 *
 * <p>All textures come from {@link CasinoSprites}, so a resource pack can retheme the
 * whole thing.
 */
public class ExchangeScreen extends BaseCasinoScreen {

    private static final int ROW_H = 14;
    private static final int COL_W = 150;

    private record Row(String id, long base, long sell, long buy) {
    }

    private final List<Row> rows = new ArrayList<>();
    private double sellMultiplier = PriceTable.SELL_MULTIPLIER;
    private double buyMultiplier = PriceTable.BUY_MULTIPLIER;
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

        addRenderableWidget(SpriteButton.of(leftPos + 10, bottom, 80, 18, "Sell Hand", b ->sendAction("SELL_MAIN_HAND", "{}")));

        addRenderableWidget(SpriteButton.of(leftPos + 94, bottom, 60, 18, "Buy x1", b ->buy("minecraft:diamond", 1)));

        addRenderableWidget(SpriteButton.of(leftPos + 158, bottom, 62, 18, "Buy x16", b ->buy("minecraft:diamond", 16)));

        addRenderableWidget(SpriteButton.of(leftPos + 224, bottom, 60, 18, "Refresh", b ->sendAction("EXCHANGE_REFRESH", "{}")));
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
            if (obj == null) {
                return;
            }
            if (obj.has("balance")) {
                playerBalance = obj.get("balance").getAsLong();
            }
            if (obj.has("sellMultiplier")) {
                sellMultiplier = obj.get("sellMultiplier").getAsDouble();
            }
            if (obj.has("buyMultiplier")) {
                buyMultiplier = obj.get("buyMultiplier").getAsDouble();
            }
            if (obj.has("currency")) {
                currency = obj.get("currency").getAsString();
            }
            if (obj.has("symbol")) {
                symbol = obj.get("symbol").getAsString();
            }
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
        } catch (Exception ignored) {
            // a malformed state packet should leave the last good render on screen
        }
    }

    @Override
    protected void renderGameContent(GuiGraphics ex, int mouseX, int mouseY, float partial) {
        int contentTop = topPos + 34;

        // --- column headings, and the spread explained right on the panel ---
        ex.drawString(this.font, "\u00a7aTHE HOUSE BUYS", leftPos + 12, contentTop, COLOR_TEXT_GREEN, false);
        ex.drawString(this.font, "\u00a7cTHE HOUSE SELLS", leftPos + 12 + COL_W + 8, contentTop, COLOR_TEXT_RED, false);

        int y = contentTop + 14;
        int maxRows = Math.min(rows.size(), 12);
        for (int i = 0; i < maxRows; i++) {
            Row r = rows.get(i);
            int rowY = y + i * ROW_H;

            // alternating row tint so long lists stay readable
            if (i % 2 == 0) {
                ex.fill(leftPos + 10, rowY - 2, leftPos + 10 + COL_W, rowY + ROW_H - 3, 0x22FFFFFF);
                ex.fill(leftPos + 10 + COL_W + 8, rowY - 2,
                        leftPos + 10 + COL_W + 8 + COL_W, rowY + ROW_H - 3, 0x22FFFFFF);
            }

            String label = shortId(r.id());

            // sell column
            ex.drawString(this.font, label, leftPos + 12, rowY, COLOR_TEXT_WHITE, false);
            String sellTxt = "\u00a7a+\u00a7e" + r.sell();
            ex.drawString(this.font, sellTxt, leftPos + 10 + COL_W - 40, rowY, COLOR_TEXT_GREEN, false);

            // buy column
            ex.drawString(this.font, label, leftPos + 12 + COL_W + 8, rowY, COLOR_TEXT_WHITE, false);
            String buyTxt = "\u00a7c-\u00a7e" + r.buy();
            ex.drawString(this.font, buyTxt, leftPos + 10 + COL_W + 8 + COL_W - 40, rowY, COLOR_TEXT_RED, false);
        }

        if (rows.isEmpty()) {
            ex.drawString(this.font, "\u00a77No prices loaded \u2014 check data/casino/prices/",
                    leftPos + 12, y, 0xFF888888, false);
        }

        // --- the spread, stated plainly ---
        int footY = topPos + bgHeight - 58;
        ex.drawString(this.font, "\u00a77Sell at \u00a7a" + fmtPercent(sellMultiplier)
                        + "\u00a77  \u00b7  Buy at \u00a7c" + fmtPercent(buyMultiplier),
                leftPos + 12, footY, COLOR_TEXT_WHITE, false);
        ex.drawString(this.font, "\u00a77The difference is the house edge \u2014 that is what makes it a game.",
                leftPos + 12, footY + 10, 0xFF999999, false);
    }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        String path = i >= 0 ? id.substring(i + 1) : id;
        path = path.replace('_', ' ');
        if (path.length() > 16) {
            path = path.substring(0, 15) + "\u2026";
        }
        return path;
    }

    private static String fmtPercent(double d) {
        return Math.round(d * 100) + "%";
    }
}
