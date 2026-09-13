package com.payflow.payflow.service;


import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * For each wallet, compare its stored balance against the balance
     * independently recomputed from the immutable ledger.
     * Returns the ids of wallets whose stored balance has drifted from the ledger.
     * An empty list means every wallet reconciles (healthy).
     */
    @Transactional(readOnly = true)
    public List<UUID> reconcile(List<Wallet> wallets) {
        List<UUID> mismatches = new ArrayList<>();

        for (Wallet wallet : wallets) {
            BigDecimal stored = wallet.getBalance();
            BigDecimal derived = ledgerEntryRepository.computeBalanceFromLedger(wallet.getId());

            if (stored.compareTo(derived) != 0) {   // compareTo, NOT equals (scale-safe)
                mismatches.add(wallet.getId());
            }
        }

        return mismatches;
    }
}