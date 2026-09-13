package com.payflow.payflow.service;

import com.payflow.payflow.entity.User;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LedgerReconciliationTest {

    @Autowired TransferService transferService;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    UserService userService;

    @Autowired
    WalletService walletService;
    // @Autowired whatever service/method performs a top-up (writes CREDIT + updates balance atomically)

    @Test
    void ledgerReconcilesAfter100RandomTransfers() {

        // ---- STEP 1: SEED ~5 wallets, funded so ledger AND balance agree ----
        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User user=userService.register("user"+i+"@test.com", "password123");
            Wallet w = walletRepository.findByUserId(user.getId()).orElseThrow();                    // your existing wallet-creation path
            walletService.topUp(w.getId(), new BigDecimal("1000.00"));  // Day 5 path: writes CREDIT ledger entry + updates balance
            wallets.add(walletRepository.findById(w.getId()).orElseThrow());
        }

        // ---- STEP 2: SNAPSHOT totalBefore from the LEDGER (source of truth) ----
        BigDecimal totalBefore = BigDecimal.ZERO;
        for (Wallet w : wallets) {
            totalBefore = totalBefore.add(ledgerEntryRepository.computeBalanceFromLedger(w.getId()));
        }

        // ---- STEP 3: 100 RANDOM TRANSFERS (sequential) ----
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            int senderIdx = random.nextInt(wallets.size());
            int receiverIdx = random.nextInt(wallets.size());
            if (senderIdx == receiverIdx) {
                continue;                                 // skip self-transfer; Day 11 would reject it anyway
            }
            Wallet sender = wallets.get(senderIdx);
            Wallet receiver = wallets.get(receiverIdx);
            BigDecimal amount = new BigDecimal(1 + random.nextInt(500));  // 1..500

            try {
                transferService.transfer("123", sender.getUserId(), receiver.getId(), amount);
            } catch (InsufficientBalanceException e) {
                // expected sometimes — sender randomly too poor; rolled back, DB clean, skip
            }
        }

        // ---- STEP 4: ASSERT conservation (totalAfter from ledger == totalBefore) ----
        BigDecimal totalAfter = BigDecimal.ZERO;
        for (Wallet w : wallets) {
            totalAfter = totalAfter.add(ledgerEntryRepository.computeBalanceFromLedger(w.getId()));
        }
        assertThat(totalBefore.compareTo(totalAfter)).isZero();   // compareTo, NOT equals

        // ---- STEP 5: ASSERT reconciliation (every wallet's stored balance == ledger) ----
        List<Wallet> fresh = wallets.stream()
                .map(w -> walletRepository.findById(w.getId()).orElseThrow())  // reload fresh stored balances
                .toList();
        List<UUID> mismatches = reconciliationService.reconcile(fresh);
        assertThat(mismatches).isEmpty();
    }
}
