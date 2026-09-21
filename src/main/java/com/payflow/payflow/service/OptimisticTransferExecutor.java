package com.payflow.payflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payflow.dto.TransferEventPayload;
import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.entity.*;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.exception.SelfTransferNotAllowedException;
import com.payflow.payflow.exception.WalletNotFoundException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.OutboxEventRepository;
import com.payflow.payflow.repository.TransactionRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One optimistic transfer attempt: no SELECT ... FOR UPDATE, and no retry.
 *
 * Concurrent changes are detected by Wallet's @Version column. At commit, Hibernate
 * writes each wallet with "UPDATE ... WHERE id = ? AND version = ?". If another
 * transaction committed a change to that wallet after we read it, the UPDATE matches
 * 0 rows, the whole attempt rolls back, and the caller gets
 * ObjectOptimisticLockingFailureException.
 *
 * That exception is raised by the transactional proxy while it commits, AFTER this
 * method has returned, so this method cannot catch it. Retrying belongs to the caller:
 * a separate, non-@Transactional bean that calls back in through the proxy, so every
 * attempt gets a new transaction and a new persistence context with fresh reads.
 */
@Service
public class OptimisticTransferExecutor {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;
    private final BalanceCache balanceCache;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    public OptimisticTransferExecutor(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository, TransactionRepository transactionRepository, BalanceCache balanceCache, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
        this.balanceCache = balanceCache;
        this.objectMapper = objectMapper;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public TransferResponse executeTransfer(String idempotencyKey, UUID senderId, UUID receiverWalletId, BigDecimal transferAmount) {

        UUID senderWalletId = walletRepository.findWalletIdByUserId(senderId)
                .orElseThrow(WalletNotFoundException::new);

        if (senderWalletId.equals(receiverWalletId)) {
            throw new SelfTransferNotAllowedException();
        }

        // Plain findById: no FOR UPDATE, so these reads take no row locks and never
        // wait on anyone. That also means they can't be part of a deadlock.
        //
        // Wallets are still loaded in ascending-id order, for a different reason than in
        // TransferExecutor. The UPDATEs Hibernate sends when it writes our changes (at
        // commit) DO lock rows until commit, and Hibernate sends them in the order the
        // entities were loaded. Loading by id order means A->B and B->A update the two
        // rows in the same order, so their writes can't deadlock either.
        UUID firstId = senderWalletId.compareTo(receiverWalletId) < 0 ? senderWalletId : receiverWalletId;
        UUID secondId = senderWalletId.compareTo(receiverWalletId) < 0 ? receiverWalletId : senderWalletId;

        Wallet first = walletRepository.findById(firstId).orElseThrow(WalletNotFoundException::new);
        Wallet second = walletRepository.findById(secondId).orElseThrow(WalletNotFoundException::new);

        Wallet senderWallet = senderWalletId.equals(firstId) ? first : second;
        Wallet receiverWallet = senderWalletId.equals(firstId) ? second : first;

        // This balance may already be stale. That's safe: if anyone commits a change to
        // this wallet before we commit, the version check fails and the retry re-reads it.
        BigDecimal currentBalance = senderWallet.getBalance();
        if (currentBalance.compareTo(transferAmount) < 0) {
            throw new InsufficientBalanceException();
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

        // STEP 4: update BOTH balances. Nothing extra is needed for the version guard:
        // Hibernate adds "AND version = ?" to both UPDATEs and bumps the version itself.
        senderWallet.setBalance(currentBalance.subtract(transferAmount));
        receiverWallet.setBalance(receiverWallet.getBalance().add(transferAmount));

        // STEP 5: mark COMPLETED
        transaction.markCompleted();           // dirty-checked, flushes at commit

        try {
            TransferEventPayload payload = new TransferEventPayload(transaction.getTransactionId(), transaction.getAmount(), senderWalletId, receiverWalletId);

            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(),transaction.getTransactionId(), json));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to record outbound event", e);
        }

        // Evict BOTH wallets' cached balances, but only AFTER commit (see BalanceCache).
        // Matters more here than in TransferExecutor: this attempt can still fail the
        // version check at commit, and an early evict would repopulate the cache from a
        // transfer that never happened.
        balanceCache.evictAfterCommit(senderWalletId, receiverWalletId);

        return new TransferResponse(
                transaction.getTransactionId(),
                TransactionStatus.COMPLETED,
                transferAmount);
    }

}
