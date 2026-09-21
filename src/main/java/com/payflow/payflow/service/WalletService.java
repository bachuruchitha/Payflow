package com.payflow.payflow.service;

import com.payflow.payflow.entity.EntryType;
import com.payflow.payflow.entity.LedgerEntry;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.exception.WalletNotFoundException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    private final LedgerEntryRepository ledgerEntryRepository;

    private final BalanceCache balanceCache;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository, BalanceCache balanceCache) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.balanceCache = balanceCache;
    }

    /**
     * The balance recomputed from the ledger, cached in Redis for 5s.
     *
     * NOT authoritative and must not be used to authorise a payment: the stored
     * wallets.balance column is the source of truth, because the overdraft check has to be
     * serialised against concurrent transfers under a row lock, which a cached SUM cannot
     * be (see DECISIONS.md). This is a display/reporting read -- for checking the ledger
     * and the stored column still agree, use ReconciliationService.
     */
    public BigDecimal getDerivedBalance(UUID walletId) {
        BigDecimal cached = balanceCache.get(walletId);
        if (cached != null) {
            return cached;
        }
        BigDecimal amount = ledgerEntryRepository.computeBalanceFromLedger(walletId);
        balanceCache.put(walletId, amount);
        return amount;
    }

    public Wallet getWallet(UUID userId){
        return walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new);
    }

    @Transactional
    public Wallet topUp(UUID userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new);
        LedgerEntry ledgerEntry = new LedgerEntry(UUID.randomUUID(), null, wallet.getId(), EntryType.CREDIT, amount);
        ledgerEntryRepository.save(ledgerEntry);
        BigDecimal currentBalance = wallet.getBalance();
        BigDecimal newBalance = currentBalance.add(amount);

        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        balanceCache.evictAfterCommit(wallet.getId());

        return wallet;
    }
}
