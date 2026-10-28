package dev.macasino.loan;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.macasino.economy.Wallet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player loan system: borrow chips from the house, pay back with interest.
 * <p>
 * A player can take out a loan (default 10,000 chips at 5% interest per day).
 * The loan plus accrued interest must be repaid; the house deducts repayments
 * automatically from wagers and deposits. A player cannot have more than one
 * active loan at a time, and cannot borrow more than their level-based limit.
 * <p>
 * Everything is server-authoritative. Clients can request a loan or repayment,
 * but the server validates eligibility and computes interest.
 */
public final class LoanManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CasinoLoan");
    private static final Gson GSON = new Gson();

    public static final long DEFAULT_LOAN = 10_000L;
    public static final double DAILY_INTEREST_RATE = 0.05; // 5% per day

    private static Path file = null;
    private static final ConcurrentHashMap<UUID, ActiveLoan> loans = new ConcurrentHashMap<>();

    private LoanManager() {}

    public static void init(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("macasino");
            Files.createDirectories(dir);
            file = dir.resolve("loans.json");
            load();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise loan system", e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            for (var e : loans.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.getKey().toString());
                o.addProperty("principal", e.getValue().principal);
                o.addProperty("interestRate", e.getValue().interestRate);
                o.addProperty("issuedAt", e.getValue().issuedAt);
                o.addProperty("lastInterestTick", e.getValue().lastInterestTick);
                arr.add(o);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(arr, w);
            }
            if (Files.exists(file)) Files.move(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write loans.json", e);
        }
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return;
            for (var el : arr) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    UUID id = UUID.fromString(o.get("uuid").getAsString());
                    ActiveLoan loan = new ActiveLoan();
                    loan.principal = o.get("principal").getAsLong();
                    loan.interestRate = o.get("interestRate").getAsDouble();
                    loan.issuedAt = o.get("issuedAt").getAsLong();
                    loan.lastInterestTick = o.get("lastInterestTick").getAsLong();
                    loans.put(id, loan);
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            LOGGER.error("Could not read loans.json", ex);
        }
    }

    public static ActiveLoan get(UUID id) {
        return loans.get(id);
    }

    public static boolean hasActiveLoan(UUID id) {
        return loans.containsKey(id);
    }

    /**
     * Compute the total owed (principal + accrued interest) as of now.
     * Interest compounds daily from issuance.
     */
    public static long totalOwed(ActiveLoan loan) {
        long now = System.currentTimeMillis();
        long days = (now - loan.issuedAt) / (24 * 60 * 60 * 1000L);
        if (days < 0) days = 0;
        // Simple interest: principal * rate * days
        long interest = (long) (loan.principal * loan.interestRate * days);
        return loan.principal + interest;
    }

    /**
     * Issue a new loan to a player. Returns the loan, or null if ineligible.
     * Max loan scales with player level (from PlayerStats).
     */
    public static ActiveLoan issueLoan(UUID id, long amount) {
        if (loans.containsKey(id)) return null; // one loan at a time
        if (amount <= 0) return null;

        int level = dev.macasino.stats.PlayerStats.level(dev.macasino.stats.PlayerStats.get(id));
        long maxLoan = DEFAULT_LOAN * (level + 1); // grows with level
        if (amount > maxLoan) amount = maxLoan;

        ActiveLoan loan = new ActiveLoan();
        loan.principal = amount;
        loan.interestRate = DAILY_INTEREST_RATE;
        loan.issuedAt = System.currentTimeMillis();
        loan.lastInterestTick = loan.issuedAt;
        loans.put(id, loan);

        // Credit the player
        Wallet.deposit(id, amount);
        save();

        LOGGER.info("Loan issued: {} borrowed {} Chips (level {})", id, amount, level);
        return loan;
    }

    /**
     * Repay part or all of a loan. Returns amount actually repaid.
     * The player must have sufficient balance.
     */
    public static long repay(UUID id, long amount) {
        ActiveLoan loan = loans.get(id);
        if (loan == null || amount <= 0) return 0;

        long owed = totalOwed(loan);
        long toRepay = Math.min(amount, owed);
        if (!Wallet.withdraw(id, toRepay)) return 0; // insufficient funds

        loan.principal = Math.max(0, loan.principal - toRepay);
        // Recompute interest from issuance; remaining principal is what's left
        // If principal is fully paid, close the loan
        if (loan.principal <= 0) {
            loans.remove(id);
            save();
            LOGGER.info("Loan repaid in full: {} paid off their loan", id);
            return toRepay;
        }

        save();
        LOGGER.info("Loan repayment: {} paid {} Chips, {} remaining", id, toRepay, loan.principal);
        return toRepay;
    }

    public static List<ActiveLoan> getAllLoans() {
        return new ArrayList<>(loans.values());
    }

    public static class ActiveLoan {
        public long principal;
        public double interestRate;
        public long issuedAt;
        public long lastInterestTick;

        public long totalOwed() { return LoanManager.totalOwed(this); }
    }
}
