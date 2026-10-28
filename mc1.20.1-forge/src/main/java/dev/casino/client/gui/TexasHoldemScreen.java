package dev.casino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.casino.game.TexasHoldemTable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class TexasHoldemScreen extends BaseCasinoScreen {
    private String phase = "WAITING_FOR_PLAYERS";
    private long pot = 0;
    private long currentHighestBet = 0;
    private int turnSeat = -1;
    private String summary = "";
    private boolean autoFillBots = true;
    private boolean isOwner = false;
    private final List<String> communityCards = new ArrayList<>();

    public static class ClientSeat {
        public int index;
        public boolean occupied;
        public String name = "";
        public long stack = 0;
        public long currentBet = 0;
        public boolean isBot = false;
        public boolean folded = false;
        public boolean allIn = false;
        public String lastAction = "";
        public final List<String> cards = new ArrayList<>();
    }

    private final ClientSeat[] clientSeats = new ClientSeat[TexasHoldemTable.MAX_SEATS];

    public TexasHoldemScreen(BlockPos pos, String initialStateJson, long playerBalance) {
        super(Component.literal("Texas Hold'em Poker Floor (Multiplayer)"), pos, initialStateJson, playerBalance);
        this.bgWidth = 380;
        this.bgHeight = 228;
        for (int i = 0; i < TexasHoldemTable.MAX_SEATS; i++) {
            clientSeats[i] = new ClientSeat();
            clientSeats[i].index = i;
        }
    }

    @Override
    protected void initGameWidgets() {
        onStateUpdated();

        // Player Table Actions (Fold / Check / Call / Raise)
        int actY = contentY() + 118;
        addRenderableWidget(SpriteButton.of(contentX(), actY, 52, 20, "Fold", b -> sendAction("FOLD", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 56, actY, 56, 20, "Check", b -> sendAction("CHECK", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 112, actY, 52, 20, "Call", b -> sendAction("CALL", "{}")));
        addRenderableWidget(SpriteButton.of(contentX() + 168, actY, 64, 20, "Raise +50", b -> {
            JsonObject json = new JsonObject();
            json.addProperty("raise", 50);
            sendAction("RAISE", GSON.toJson(json));
        }));

        // Join / Leave seat buttons
        addRenderableWidget(SpriteButton.primary(contentX() + 236, actY, 96, 20, "Sit (500)", b -> {
            for (ClientSeat s : clientSeats) {
                if (!s.occupied) {
                    JsonObject json = new JsonObject();
                    json.addProperty("seat", s.index);
                    json.addProperty("buyIn", 500);
                    sendAction("JOIN", GSON.toJson(json));
                    break;
                }
            }
        }));

        addRenderableWidget(SpriteButton.of(contentX() + 60, actY - 22, 56, 18, "Leave", b -> sendAction("LEAVE", "{}")));

        // Table Owner Host controls: Toggle bots
        if (isOwner) {
            String botLabel = autoFillBots ? "§aBots: ON" : "§cBots: OFF";
            addRenderableWidget(SpriteButton.of(contentX() + 260, topPos + 6, 75, 16, botLabel, b -> sendAction("TOGGLE_BOTS", "{}")));
        }
    }

    @Override
    protected void onStateUpdated() {
        try {
            JsonObject obj = GSON.fromJson(lastStateJson, JsonObject.class);
            if (obj != null) {
                if (obj.has("phase")) this.phase = obj.get("phase").getAsString();
                if (obj.has("pot")) this.pot = obj.get("pot").getAsLong();
                if (obj.has("currentHighestBet")) this.currentHighestBet = obj.get("currentHighestBet").getAsLong();
                if (obj.has("turnSeat")) this.turnSeat = obj.get("turnSeat").getAsInt();
                if (obj.has("summary")) this.summary = obj.get("summary").getAsString();
                if (obj.has("autoFillBots")) this.autoFillBots = obj.get("autoFillBots").getAsBoolean();
                if (obj.has("isOwner")) this.isOwner = obj.get("isOwner").getAsBoolean();

                communityCards.clear();
                if (obj.has("communityCards")) {
                    for (var el : obj.getAsJsonArray("communityCards")) communityCards.add(el.getAsString());
                }

                if (obj.has("seats")) {
                    JsonArray seatsArr = obj.getAsJsonArray("seats");
                    for (int i = 0; i < seatsArr.size() && i < TexasHoldemTable.MAX_SEATS; i++) {
                        JsonObject so = seatsArr.get(i).getAsJsonObject();
                        ClientSeat cs = clientSeats[i];
                        cs.occupied = so.get("occupied").getAsBoolean();
                        if (cs.occupied) {
                            cs.name = so.get("name").getAsString();
                            cs.stack = so.get("stack").getAsLong();
                            cs.currentBet = so.get("currentBet").getAsLong();
                            cs.isBot = so.get("isBot").getAsBoolean();
                            cs.folded = so.get("folded").getAsBoolean();
                            cs.allIn = so.get("allIn").getAsBoolean();
                            cs.lastAction = so.get("lastAction").getAsString();

                            cs.cards.clear();
                            if (so.has("cards")) {
                                for (var c : so.getAsJsonArray("cards")) cs.cards.add(c.getAsString());
                            }
                        } else {
                            cs.name = "";
                            cs.stack = 0;
                            cs.currentBet = 0;
                            cs.cards.clear();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void renderGameContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Table center info: Pot & Phase
        int centerX = leftPos + bgWidth / 2;
        int centerY = contentY() + 69;

        // Poker table oval felt
        graphics.fill(contentX() + 30, contentY() + 9, leftPos + bgWidth - 40, contentY() + 129, 0xFF0D421E);

        graphics.drawString(this.font, "ALL IN OR FOLD", contentX(), contentY() + 30, COLOR_HOLDEM, false);
        graphics.drawCenteredString(this.font, "POT: §e" + pot + " Chips §7(Call: " + currentHighestBet + ")", centerX, contentY() + 16, COLOR_TEXT_GOLD);
        graphics.drawCenteredString(this.font, "§7[§f" + phase + "§7]", centerX, contentY() + 28, COLOR_TEXT_WHITE);

        // Community cards in center
        int ccX = centerX - (communityCards.size() * 13);
        for (int i = 0; i < communityCards.size(); i++) {
            drawCard(graphics, ccX + (i * 26), contentY() + 40, communityCards.get(i));
        }

        // Render 6 seats around the table
        // 0: Top-Left, 1: Top-Center, 2: Top-Right, 3: Bot-Right, 4: Bot-Center, 5: Bot-Left
        int[][] seatPositions = {
                {contentX() + 2, contentY() + 4},
                {centerX - 35, topPos + 34},
                {leftPos + bgWidth - 85, contentY() + 4},
                {leftPos + bgWidth - 85, contentY() + 94},
                {centerX - 35, contentY() + 104},
                {contentX() + 2, contentY() + 94}
        };

        for (int i = 0; i < TexasHoldemTable.MAX_SEATS; i++) {
            ClientSeat s = clientSeats[i];
            int sx = seatPositions[i][0];
            int sy = seatPositions[i][1];

            // Highlight active turn seat
            int borderColor = (i == turnSeat) ? 0xFFFFFF00 : 0xFF333333;
            graphics.fill(sx - 1, sy - 1, sx + 76, sy + 36, borderColor);
            graphics.fill(sx, sy, sx + 75, sy + 35, 0xFF181C18);

            if (s.occupied) {
                String prefix = s.isBot ? "§7[BOT] " : "§a";
                graphics.drawString(this.font, prefix + s.name, sx + 2, sy + 2, COLOR_TEXT_WHITE, false);
                graphics.drawString(this.font, "§e" + s.stack + " Chips", sx + 2, sy + 13, COLOR_TEXT_GOLD, false);

                if (s.folded) {
                    graphics.drawString(this.font, "§7Folded", sx + 2, sy + 24, 0xFFAAAAAA, false);
                } else if (s.allIn) {
                    graphics.drawString(this.font, "§cALL-IN", sx + 2, sy + 24, COLOR_TEXT_RED, false);
                } else if (!s.lastAction.isEmpty()) {
                    graphics.drawString(this.font, "§f" + s.lastAction, sx + 2, sy + 24, COLOR_TEXT_WHITE, false);
                }

                // Render hole cards next to seat
                for (int c = 0; c < s.cards.size(); c++) {
                    drawCard(graphics, sx + 44 + (c * 14), sy + 5, s.cards.get(c));
                }
            } else {
                graphics.drawString(this.font, "§8[Empty Seat]", sx + 4, sy + 12, 0xFF888888, false);
            }
        }

        // Summary banner at bottom
        if (!summary.isEmpty()) {
            graphics.drawCenteredString(this.font, "§e" + fitSummary(summary, contentW() - 10), centerX, contentY() + 136, COLOR_TEXT_GOLD);
        }
    }
    private String fitSummary(String text, int maxW) {
        if (text == null || text.isEmpty()) return "";
        int approxCharW = 6;
        if (text.length() * approxCharW <= maxW) return text;
        String cut = text;
        while (cut.length() > 1 && cut.length() * approxCharW > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\u2026";
    }
}
