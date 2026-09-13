package com.payflow.payflow.service;

import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.entity.TransactionStatus;
import com.payflow.payflow.entity.User;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.exception.TransferConflictException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.TransactionRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the same contention scenario against both locking strategies and prints the numbers
 * used in the README's locking comparison: 50 threads each send 100 from a wallet that can
 * fund exactly 10 of them.
 *
 * Optimistic retries are counted from OptimisticTransferService's per-conflict debug log line.
 */
@Testcontainers
@SpringBootTest(properties = {
        "logging.level.com.payflow.payflow.service.OptimisticTransferService=DEBUG",
        // SQL logging writes to a synchronized stdout and would serialize both strategies.
        "spring.jpa.show-sql=false"
})
@ExtendWith(OutputCaptureExtension.class)
class LockingStrategyComparisonTest {

    static {
        // Same Windows timezone-alias workaround as ConcurrentTransferTest.
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

    private static final int THREADS = 50;
    private static final int AFFORDABLE = 10;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final BigDecimal STARTING_BALANCE = AMOUNT.multiply(BigDecimal.valueOf(AFFORDABLE));
    private static final int MEASURED_ROUNDS = 5;
    private static final String CONFLICT_LOG_LINE = "Optimistic conflict on attempt";

    enum Strategy { PESSIMISTIC, OPTIMISTIC }

    record RoundResult(Strategy strategy, int succeeded, int insufficient, int exhausted, long conflicts, long elapsedMs) {
        double requestsPerSecond() {
            return THREADS * 1000.0 / Math.max(elapsedMs, 1);
        }
    }

    @Autowired UserService userService;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired TransferService transferService;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TransactionRepository transactionRepository;

    @Test
    void compareStrategiesWith50ThreadsOnOneWallet(CapturedOutput output) throws Exception {
        // Warm-up round per strategy (JIT, connection pool, Hibernate metadata), not recorded.
        runRound(Strategy.PESSIMISTIC, output);
        runRound(Strategy.OPTIMISTIC, output);

        List<RoundResult> results = new ArrayList<>();
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            // Alternate which strategy runs first so neither always gets the warmer JVM.
            Strategy first = round % 2 == 0 ? Strategy.PESSIMISTIC : Strategy.OPTIMISTIC;
            Strategy second = first == Strategy.PESSIMISTIC ? Strategy.OPTIMISTIC : Strategy.PESSIMISTIC;
            results.add(runRound(first, output));
            results.add(runRound(second, output));
        }

        System.out.println("COMPARE strategy     succeeded insufficient exhausted retries  elapsedMs  req/s");
        for (RoundResult r : results) {
            System.out.printf("COMPARE %-12s %9d %12d %9d %7d %10d %6.0f%n",
                    r.strategy(), r.succeeded(), r.insufficient(), r.exhausted(), r.conflicts(), r.elapsedMs(), r.requestsPerSecond());
        }
    }

    private RoundResult runRound(Strategy strategy, CapturedOutput output) throws Exception {
        User sender = userService.register("sender-" + UUID.randomUUID() + "@test.com", "password123");
        walletService.topUp(sender.getId(), STARTING_BALANCE);
        Wallet senderWallet = walletRepository.findByUserId(sender.getId()).orElseThrow();
        User receiver = userService.register("receiver-" + UUID.randomUUID() + "@test.com", "password123");
        Wallet receiverWallet = walletRepository.findByUserId(receiver.getId()).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        Set<String> keys = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            // Unique per thread: a shared key would turn 49 threads into idempotent replays.
            String idempotencyKey = UUID.randomUUID().toString();
            keys.add(idempotencyKey);
            futures.add(pool.submit(() -> {
                try {
                    startLine.await();
                    TransferResponse response = strategy == Strategy.PESSIMISTIC
                            ? transferService.transfer(idempotencyKey, sender.getId(), receiverWallet.getId(), AMOUNT)
                            : transferService.transferOptimistic(idempotencyKey, sender.getId(), receiverWallet.getId(), AMOUNT);
                    if (response.status() == TransactionStatus.COMPLETED) {
                        succeeded.incrementAndGet();
                    }
                } catch (InsufficientBalanceException e) {
                    insufficient.incrementAndGet();
                } catch (TransferConflictException e) {
                    exhausted.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            }));
        }

        long conflictsBefore = countConflictLines(output);
        long start = System.nanoTime();
        startLine.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        pool.shutdown();
        long conflicts = countConflictLines(output) - conflictsBefore;

        // Validity of the round, for both strategies.
        assertThat(keys).as("every thread had its own idempotency key").hasSize(THREADS);
        assertThat(unexpected).as("no unexpected exceptions: %s", unexpected).isEmpty();
        assertThat(succeeded.get() + insufficient.get() + exhausted.get()).isEqualTo(THREADS);
        assertThat(succeeded.get()).isLessThanOrEqualTo(AFFORDABLE);
        assertThat(transactionRepository.findByFromWalletIdOrToWalletId(senderWallet.getId(), senderWallet.getId(), Pageable.unpaged()).getTotalElements())
                .as("each success wrote its own transaction row (no idempotent replays)")
                .isEqualTo(succeeded.get());

        BigDecimal moved = AMOUNT.multiply(BigDecimal.valueOf(succeeded.get()));
        BigDecimal senderBalance = walletRepository.findById(senderWallet.getId()).orElseThrow().getBalance();
        BigDecimal receiverBalance = walletRepository.findById(receiverWallet.getId()).orElseThrow().getBalance();
        assertThat(senderBalance.signum()).as("never overdrawn").isGreaterThanOrEqualTo(0);
        assertThat(senderBalance).isEqualByComparingTo(STARTING_BALANCE.subtract(moved));
        assertThat(receiverBalance).isEqualByComparingTo(moved);
        assertThat(ledgerEntryRepository.computeBalanceFromLedger(senderWallet.getId())).isEqualByComparingTo(senderBalance);
        assertThat(ledgerEntryRepository.computeBalanceFromLedger(receiverWallet.getId())).isEqualByComparingTo(receiverBalance);

        if (strategy == Strategy.PESSIMISTIC) {
            assertThat(succeeded.get()).isEqualTo(AFFORDABLE);
            assertThat(insufficient.get()).isEqualTo(THREADS - AFFORDABLE);
            assertThat(exhausted.get()).isZero();
            assertThat(conflicts).isZero();
        }

        return new RoundResult(strategy, succeeded.get(), insufficient.get(), exhausted.get(), conflicts, elapsedMs);
    }

    private static long countConflictLines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains(CONFLICT_LOG_LINE)).count();
    }
}
