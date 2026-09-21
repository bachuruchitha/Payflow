package com.payflow.payflow.service;

import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.entity.*;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.exception.SelfTransferNotAllowedException;
import com.payflow.payflow.exception.WalletNotFoundException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.TransactionRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferExecutor {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceCache balanceCache;

    public TransferExecutor(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository, TransactionRepository transactionRepository, BalanceCache balanceCache) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
        this.balanceCache = balanceCache;
    }


    @Transactional
    public TransferResponse executeTransfer(String idempotencyKey, UUID senderId, UUID receiverWalletId, BigDecimal transferAmount){

        UUID senderWalletId = walletRepository.findWalletIdByUserId(senderId)
                .orElseThrow(WalletNotFoundException::new);

        if (senderWalletId.equals(receiverWalletId)) {
            throw new SelfTransferNotAllowedException();
        }

        // Lock BOTH wallets, in ascending-id order, BEFORE reading either balance.
        //
        // WHY the lock has to sit here, before the balance read/decision below:
        // without it, 50 concurrent transfers off the same wallet would all run
        // "SELECT balance" at roughly the same time, all see the same pre-transfer
        // balance (e.g. 1000), all decide "sufficient funds" for their own transfer,
        // and all proceed to subtract and commit -- overdrawing the wallet far below
        // zero (a classic lost-update / TOCTOU race). PESSIMISTIC_WRITE (SELECT ...
        // FOR UPDATE) makes every other transaction that wants to touch this row
        // block until this one commits or rolls back, so whichever transfer gets the
        // lock next always re-reads the true, post-previous-transfer balance.
        //
        // WHY sorted by id instead of "lock sender, then receiver": two transfers
        // running in opposite directions between the same pair of wallets (A->B and
        // B->A at the same time) would otherwise lock in opposite orders and deadlock
        // -- thread1 holds A waiting on B while thread2 holds B waiting on A. Always
        // locking the smaller wallet id first gives every transaction the same global
        // lock order, so that deadlock can't happen.
        UUID firstId = senderWalletId.compareTo(receiverWalletId) < 0 ? senderWalletId : receiverWalletId;
        UUID secondId = senderWalletId.compareTo(receiverWalletId) < 0 ? receiverWalletId : senderWalletId;

        Wallet first = walletRepository.findByIdForUpdate(firstId).orElseThrow(WalletNotFoundException::new);
        Wallet second = walletRepository.findByIdForUpdate(secondId).orElseThrow(WalletNotFoundException::new);

        Wallet senderWallet = senderWalletId.equals(firstId) ? first : second;
        Wallet receiverWallet = senderWalletId.equals(firstId) ? second : first;

        // Safe to read now: we hold the row lock, so this is the up-to-date committed
        // balance, not a stale value some other in-flight transfer already spent.
        BigDecimal currentBalance = senderWallet.getBalance();
        if (currentBalance.compareTo(transferAmount) < 0) {
            throw new InsufficientBalanceException(); // rolls back the @Transactional method -> releases the lock immediately
        }

        // STEP 2: create transactions row, starting PENDING
        Transaction transaction = transactionRepository.save(new Transaction(
                UUID.randomUUID(), senderWalletId, receiverWalletId,
                transferAmount, TransactionStatus.PENDING, idempotencyKey));

        // STEP 3: write DEBIT + CREDIT ledger entries (the source of truth)
        LedgerEntry debit = new LedgerEntry(
                UUID.randomUUID(), transaction.getTransactionId(),
                senderWalletId, EntryType.DEBIT, transferAmount);
        LedgerEntry credit = new LedgerEntry(
                UUID.randomUUID(), transaction.getTransactionId(),
                receiverWalletId, EntryType.CREDIT, transferAmount);
        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        // STEP 4: update BOTH balances, derived from the entries   // FIX (Bug 1): was missing entirely
        senderWallet.setBalance(currentBalance.subtract(transferAmount));
        receiverWallet.setBalance(receiverWallet.getBalance().add(transferAmount));


        // STEP 5: mark COMPLETED
        transaction.markCompleted();           // dirty-checked, flushes at commit

        // Evict BOTH wallets' cached balances, but only AFTER commit (see BalanceCache).
        balanceCache.evictAfterCommit(senderWalletId, receiverWalletId);

        return new TransferResponse(
                transaction.getTransactionId(),
                TransactionStatus.COMPLETED,
                transferAmount);
    }

}
