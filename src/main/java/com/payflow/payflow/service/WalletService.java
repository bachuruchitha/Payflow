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
import java.time.Instant;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
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

        return wallet;
    }
}
