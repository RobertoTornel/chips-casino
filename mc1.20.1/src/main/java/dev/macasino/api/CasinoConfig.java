package dev.macasino.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Central configuration for the Chips Casino mod.
 *
 * <p>Settings are loaded from {@code config/casino/casino.json} relative to the working
 * directory on first access. Missing or corrupt values fall back to the documented
 * defaults. The file is rewritten whenever a value is changed, so operators can edit it
 * by hand and see changes take effect without restarting.
 *
 * <p>All values are accessed through {@link #instance()} so that call sites don't need to
 * know where the config lives.
 */
public class CasinoConfig {

    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = java.nio.file.Paths.get("config", "macasino");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("casino.json");

    private static CasinoConfig INSTANCE;

    // ---------------------------------------------------------------- economy
    /** Sell multiplier: fraction of base price paid when selling to the house. */
    public double sellMultiplier = 0.8;
    /** Buy multiplier: multiple of base price charged when buying from the house. */
    public double buyMultiplier = 1.2;

    // ---------------------------------------------------------------- betting
    /** Minimum bet accepted at any table or machine. */
    public long minBet = 10;
    /** Maximum bet accepted at any table or machine. */
    public long maxBet = 10_000;

    // ---------------------------------------------------------------- machine RTPS
    /** Target return-to-player for the slot machine (0.0..1.0). */
    public double slotRtpTarget = 0.89;
    /** Target return-to-player for pinball at perfect timing (0.0..1.0). */
    public double pinballRtpTarget = 0.92;
    /** Target return-to-player for pachinko (0.0..1.0). */
    public double pachinkoRtpTarget = 0.891;

    /** Slot machine payout multiplier applied to all slot payouts. 1.0 = default. */
    public double slotPayoutMultiplier = 1.0;

    // ---------------------------------------------------------------- features
    /** Whether house-player bots are allowed to fill empty seats. */
    public boolean enableBots = true;

    private CasinoConfig() {
    }

    /** Load (or create) the config file and return the singleton instance. */
    public static CasinoConfig instance() {
        if (INSTANCE == null) {
            INSTANCE = new CasinoConfig();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    /** Force a reload from disk, discarding any in-memory changes. */
    public static void reload() {
        if (INSTANCE != null) {
            INSTANCE.load();
        }
    }

    /** Save the current config to disk. */
    public static void save() {
        if (INSTANCE != null) {
            INSTANCE.write();
        }
    }

    private void load() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (Files.exists(CONFIG_FILE)) {
                try (FileReader reader = new FileReader(CONFIG_FILE.toFile())) {
                    JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                    if (obj.has("sell_multiplier")) sellMultiplier = obj.get("sell_multiplier").getAsDouble();
                    if (obj.has("buy_multiplier")) buyMultiplier = obj.get("buy_multiplier").getAsDouble();
                    if (obj.has("min_bet")) minBet = obj.get("min_bet").getAsLong();
                    if (obj.has("max_bet")) maxBet = obj.get("max_bet").getAsLong();
                    if (obj.has("slot_rtp_target")) slotRtpTarget = obj.get("slot_rtp_target").getAsDouble();
                    if (obj.has("pinball_rtp_target")) pinballRtpTarget = obj.get("pinball_rtp_target").getAsDouble();
                    if (obj.has("pachinko_rtp_target")) pachinkoRtpTarget = obj.get("pachinko_rtp_target").getAsDouble();
                    if (obj.has("enable_bots")) enableBots = obj.get("enable_bots").getAsBoolean();
                }
            } else {
                write();
            }
        } catch (Exception e) {
            System.err.println("[Casino] Failed to load config, using defaults: " + e.getMessage());
        }
    }

    private void write() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject obj = new JsonObject();
            obj.addProperty("sell_multiplier", sellMultiplier);
            obj.addProperty("buy_multiplier", buyMultiplier);
            obj.addProperty("min_bet", minBet);
            obj.addProperty("max_bet", maxBet);
            obj.addProperty("slot_rtp_target", slotRtpTarget);
            obj.addProperty("pinball_rtp_target", pinballRtpTarget);
            obj.addProperty("pachinko_rtp_target", pachinkoRtpTarget);
            obj.addProperty("enable_bots", enableBots);
            try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
                GSON.toJson(obj, writer);
            }
        } catch (IOException e) {
            System.err.println("[Casino] Failed to save config: " + e.getMessage());
        }
    }
}
