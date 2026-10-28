package dev.casino.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the wallet: atomic operations, batch persistence, concurrency safety,
 * and the invariant that money can never be created or destroyed except through
 * explicit deposits and withdrawals.
 *
 * <p>Each test uses fresh UUIDs so there is no shared static state between tests.
 * {@link Wallet#clearForTesting()} is package-private and may not be visible to
 * all loader classpaths; tests avoid it entirely.
 */
class WalletTest {

    @Test
    @DisplayName("new player starts with zero balance")
    void newPlayerStartsAtZero() {
        UUID alice = UUID.randomUUID();
        assertEquals(0L, Wallet.getBalance(alice));
        assertFalse(Wallet.hasAccount(alice));
    }

    @Test
    @DisplayName("createAccount creates the account and cannot be re-created")
    void createAccountSemantics() {
        UUID alice = UUID.randomUUID();
        assertTrue(Wallet.createAccount(alice));
        assertFalse(Wallet.createAccount(alice));
        assertTrue(Wallet.hasAccount(alice));
        assertEquals(0L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("createAccount with starting balance")
    void createAccountWithStarting() {
        UUID bob = UUID.randomUUID();
        assertTrue(Wallet.createAccount(bob, 500L));
        assertEquals(500L, Wallet.getBalance(bob));
    }

    @Test
    @DisplayName("negative starting balance clamps to zero")
    void negativeStartClamps() {
        UUID alice = UUID.randomUUID();
        assertTrue(Wallet.createAccount(alice, -100L));
        assertEquals(0L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("deposit increases balance atomically")
    void depositIncreasesBalance() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        Wallet.deposit(alice, 50L);
        assertEquals(150L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("deposit of zero or negative is ignored")
    void nonPositiveDepositIgnored() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        Wallet.deposit(alice, 0L);
        Wallet.deposit(alice, -50L);
        assertEquals(100L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("withdraw succeeds when balance covers it")
    void withdrawSucceeds() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        assertTrue(Wallet.withdraw(alice, 30L));
        assertEquals(70L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("withdraw fails when balance is insufficient and changes nothing")
    void withdrawFailsWhenInsufficient() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 50L);
        assertFalse(Wallet.withdraw(alice, 100L));
        assertEquals(50L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("withdraw of zero or negative is a no-op")
    void nonPositiveWithdrawNoOp() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        assertTrue(Wallet.withdraw(alice, 0L));
        assertTrue(Wallet.withdraw(alice, -10L));
        assertEquals(100L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("setBalance clamps negatives to zero")
    void setBalanceClamps() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        Wallet.setBalance(alice, -50L);
        assertEquals(0L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("transfer moves money atomically")
    void transferAtomic() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Wallet.createAccount(alice, 200L);
        Wallet.createAccount(bob, 50L);
        assertTrue(Wallet.transfer(alice, bob, 75L));
        assertEquals(125L, Wallet.getBalance(alice));
        assertEquals(125L, Wallet.getBalance(bob));
    }

    @Test
    @DisplayName("transfer fails when source has insufficient funds")
    void transferFailsWhenInsufficient() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Wallet.createAccount(alice, 50L);
        Wallet.createAccount(bob, 10L);
        assertFalse(Wallet.transfer(alice, bob, 100L));
        assertEquals(50L, Wallet.getBalance(alice));
        assertEquals(10L, Wallet.getBalance(bob));
    }

    @Test
    @DisplayName("transfer to self is rejected")
    void transferToSelfRejected() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 100L);
        assertFalse(Wallet.transfer(alice, alice, 50L));
        assertEquals(100L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("total money is conserved across deposits and withdrawals")
    void totalMoneyConserved() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Wallet.createAccount(alice, 1000L);
        Wallet.createAccount(bob, 500L);
        long totalBefore = Wallet.getBalance(alice) + Wallet.getBalance(bob);

        Wallet.transfer(alice, bob, 250L);
        assertEquals(totalBefore, Wallet.getBalance(alice) + Wallet.getBalance(bob));

        Wallet.deposit(alice, 100L);
        totalBefore += 100L;
        assertEquals(totalBefore, Wallet.getBalance(alice) + Wallet.getBalance(bob));

        Wallet.withdraw(bob, 50L);
        totalBefore -= 50L;
        assertEquals(totalBefore, Wallet.getBalance(alice) + Wallet.getBalance(bob));
    }

    @Test
    @DisplayName("concurrent deposits do not lose updates")
    void concurrentDepositsDoNotLoseUpdates() throws InterruptedException {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 0L);
        int threads = 16;
        int depositsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < depositsPerThread; i++) {
                        Wallet.deposit(alice, 1L);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();

        assertEquals(0, errors.get());
        assertEquals((long) threads * depositsPerThread, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("concurrent withdrawals do not overdraw")
    void concurrentWithdrawalsDoNotOverdraw() throws InterruptedException {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 10000L);
        int threads = 16;
        int withdrawalsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successfulWithdrawals = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < withdrawalsPerThread; i++) {
                        if (Wallet.withdraw(alice, 10L)) {
                            successfulWithdrawals.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();

        long expectedSuccessful = 10000L / 10L; // 10000 / 10 = 1000 successful withdrawals max
        assertTrue(successfulWithdrawals.get() <= expectedSuccessful,
                "too many successful withdrawals: " + successfulWithdrawals.get());
        assertTrue(Wallet.getBalance(alice) >= 0, "balance went negative: " + Wallet.getBalance(alice));
        assertEquals(10000L - successfulWithdrawals.get() * 10L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("clear sets balance to zero")
    void clearSetsToZero() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 999L);
        Wallet.clear(alice);
        assertEquals(0L, Wallet.getBalance(alice));
    }

    @Test
    @DisplayName("has returns true when balance covers amount")
    void hasSemantics() {
        UUID alice = UUID.randomUUID();
        Wallet.createAccount(alice, 50L);
        assertTrue(Wallet.has(alice, 0L));
        assertTrue(Wallet.has(alice, 50L));
        assertFalse(Wallet.has(alice, 51L));
        assertTrue(Wallet.has(alice, -10L)); // non-positive is always covered
    }

    @Test
    @DisplayName("hasAccount tracks creation")
    void hasAccountTracksCreation() {
        UUID alice = UUID.randomUUID();
        assertFalse(Wallet.hasAccount(alice));
        Wallet.createAccount(alice);
        assertTrue(Wallet.hasAccount(alice));
    }
}
