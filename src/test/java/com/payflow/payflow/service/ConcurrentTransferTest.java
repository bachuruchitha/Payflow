package com.payflow.payflow.service;

import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.entity.TransactionStatus;
import com.payflow.payflow.entity.User;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many transfers off the same wallet at once to prove the pessimistic
 * lock in TransferExecutor actually serializes them instead of letting them
 * all read the same stale balance.
 */
@Testcontainers
@SpringBootTest
class ConcurrentTransferTest {

    static {
        // On Windows, the JVM can report the platform timezone using a legacy
        // alias (e.g. "Asia/Calcutta" instead of "Asia/Kolkata"). pgjdbc forwards
        // that name verbatim as a startup parameter, and Postgres's own tzdata
        // rejects it ("FATAL: invalid value for parameter TimeZone"), so every
        // connection -- including Flyway's very first one -- fails before any
        // test code runs. Pinning the JVM default to UTC sidesteps the bad alias.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    UserService userService;
    @Autowired
    WalletService walletService;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    TransferService transferService;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Test
    void exactly10Of50ConcurrentTransfersSucceedWhenBalanceCoversOnly10() throws InterruptedException {
        BigDecimal transferAmount = new BigDecimal("100.00");
        int affordableTransfers = 10;
        int attemptedTransfers = 50;
        BigDecimal startingBalance = transferAmount.multiply(BigDecimal.valueOf(affordableTransfers)); // exactly 1000.00

        User sender = userService.register("sender-" + UUID.randomUUID() + "@test.com", "password123");
        walletService.topUp(sender.getId(), startingBalance);
        Wallet senderWallet = walletRepository.findByUserId(sender.getId()).orElseThrow();

        User receiver = userService.register("receiver-" + UUID.randomUUID() + "@test.com", "password123");
        Wallet receiverWallet = walletRepository.findByUserId(receiver.getId()).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(attemptedTransfers);
        CountDownLatch startLine = new CountDownLatch(1);      // holds every thread back until all are ready
        CountDownLatch finishLine = new CountDownLatch(attemptedTransfers);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < attemptedTransfers; i++) {
            // Unique per thread: a shared key would turn 49 threads into idempotent replays and
            // "prove" no-overdraft by accident.
            String idempotencyKey = UUID.randomUUID().toString();
            pool.submit(() -> {
                try {
                    startLine.await();
                    TransferResponse response = transferService.transfer(
                            idempotencyKey, sender.getId(), receiverWallet.getId(), transferAmount);
                    if (response.status() == TransactionStatus.COMPLETED) {
                        succeeded.incrementAndGet();
                    }
                } catch (InsufficientBalanceException e) {
                    insufficientFunds.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedFailures.add(t);
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown(); // release all 50 threads at once
        boolean completedInTime = finishLine.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completedInTime).as("all threads finished within timeout").isTrue();
        assertThat(unexpectedFailures).as("no unexpected exceptions: %s", unexpectedFailures).isEmpty();
        assertThat(succeeded.get()).isEqualTo(affordableTransfers);
        assertThat(insufficientFunds.get()).isEqualTo(attemptedTransfers - affordableTransfers);

        Wallet finalSenderWallet = walletRepository.findById(senderWallet.getId()).orElseThrow();
        assertThat(finalSenderWallet.getBalance().signum())
                .as("balance must never go negative")
                .isGreaterThanOrEqualTo(0);
        assertThat(finalSenderWallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        Wallet finalReceiverWallet = walletRepository.findById(receiverWallet.getId()).orElseThrow();
        assertThat(finalReceiverWallet.getBalance()).isEqualByComparingTo(startingBalance);

        // Ledger (insert-only source of truth) must agree with the stored balances too --
        // if the lock let two transfers race, the ledger sum and the balance column diverge.
        assertThat(ledgerEntryRepository.computeBalanceFromLedger(senderWallet.getId()))
                .isEqualByComparingTo(finalSenderWallet.getBalance());
        assertThat(ledgerEntryRepository.computeBalanceFromLedger(receiverWallet.getId()))
                .isEqualByComparingTo(finalReceiverWallet.getBalance());
    }
}
