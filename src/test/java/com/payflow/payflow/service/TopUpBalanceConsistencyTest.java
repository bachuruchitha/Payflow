package com.payflow.payflow.service;

import com.payflow.payflow.entity.User;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a top-up leave the two balance representations in agreement?
 *
 * PayFlow holds a balance twice: the stored wallets.balance column, and the balance
 * derivable by summing the immutable ledger entries. Only one can be the source of truth
 * (see DECISIONS.md) -- the stored column is, and the ledger is the independent audit
 * trail reconciliation checks it against. Both must still describe the same money after
 * every write, or reconciliation is meaningless.
 */
@SpringBootTest
class TopUpBalanceConsistencyTest {

    @Autowired WalletService walletService;
    @Autowired UserService userService;
    @Autowired WalletRepository walletRepository;
    @Autowired ReconciliationService reconciliationService;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void topUpKeepsStoredBalanceLedgerAndCacheInAgreement() {
        User user = userService.register("topup-" + UUID.randomUUID() + "@test.com", "password123");
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();

        // Warm the cache first, so we are testing eviction and not just a cold read.
        walletService.getDerivedBalance(wallet.getId());
        assertThat(redisTemplate.hasKey("wallet:balance:" + wallet.getId())).isTrue();

        walletService.topUp(user.getId(), new BigDecimal("1000.00"));

        // 1. The cached derived balance must be gone -- evicted after commit.
        assertThat(redisTemplate.hasKey("wallet:balance:" + wallet.getId())).isFalse();

        // 2. Stored column and ledger-derived balance must agree on the new amount.
        Wallet stored = walletRepository.findById(wallet.getId()).orElseThrow();
        BigDecimal derived = walletService.getDerivedBalance(wallet.getId());

        assertThat(stored.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(derived).isEqualByComparingTo(stored.getBalance());

        // 3. Reconciliation -- the Day 12 check -- must report no drift.
        List<UUID> mismatches = reconciliationService.reconcile(List.of(stored));
        assertThat(mismatches).isEmpty();
    }

    @Test
    void repeatedTopUpsStayReconciled() {
        User user = userService.register("topup-" + UUID.randomUUID() + "@test.com", "password123");
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();

        for (int i = 0; i < 5; i++) {
            walletService.topUp(user.getId(), new BigDecimal("13.37"));
        }

        Wallet stored = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(stored.getBalance()).isEqualByComparingTo("66.85");
        assertThat(walletService.getDerivedBalance(wallet.getId())).isEqualByComparingTo(stored.getBalance());
        assertThat(reconciliationService.reconcile(List.of(stored))).isEmpty();
    }
}
