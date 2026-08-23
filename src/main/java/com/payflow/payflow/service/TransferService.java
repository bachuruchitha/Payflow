package com.payflow.payflow.service;

import com.payflow.payflow.dto.Direction;
import com.payflow.payflow.dto.TransactionHistoryResponse;
import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.entity.*;
import com.payflow.payflow.exception.InsufficientBalanceException;
import com.payflow.payflow.exception.SelfTransferNotAllowedException;
import com.payflow.payflow.exception.WalletNotFoundException;
import com.payflow.payflow.repository.LedgerEntryRepository;
import com.payflow.payflow.repository.TransactionRepository;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;

    public TransferService(WalletRepository walletRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransferResponse transfer(UUID senderId, UUID receiverWalletId, BigDecimal transferAmount) {
        // STEP 1: validate balance (inside the txn)
        Wallet senderWallet = walletRepository.findByUserId(senderId)
                .orElseThrow(WalletNotFoundException::new);
        Wallet receiverWallet = walletRepository.findById(receiverWalletId)
                .orElseThrow(WalletNotFoundException::new);

        if (senderWallet.getId().equals(receiverWalletId)) {
            throw new SelfTransferNotAllowedException();
        }

        BigDecimal currentBalance = senderWallet.getBalance();
        if (currentBalance.compareTo(transferAmount) < 0) {
            throw new InsufficientBalanceException();
        }
        UUID senderWalletId = senderWallet.getId();

        // STEP 2: create transactions row, starting PENDING
        Transaction transaction = transactionRepository.save(new Transaction(
                UUID.randomUUID(), senderWalletId, receiverWalletId,
                transferAmount, TransactionStatus.PENDING));

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


        return new TransferResponse(
                transaction.getTransactionId(),
                TransactionStatus.COMPLETED,
                transferAmount);
    }

    public Page<TransactionHistoryResponse> getTransactions(UUID userId, Pageable pageable) {
        UUID myWalletId = walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new).getId();
        Page<Transaction> transactions = transactionRepository.findByFromWalletIdOrToWalletId(myWalletId, myWalletId, pageable);
        return transactions.map(transaction -> mapToDto(transaction, myWalletId));
    }

    @Transactional(readOnly = true)
    private TransactionHistoryResponse mapToDto(Transaction transaction, UUID myWalletId) {
        UUID counterPartyWalletId = transaction.getFromWalletId();
        Direction direction = Direction.INCOMING;
        if (transaction.getFromWalletId().equals(myWalletId)) {
            counterPartyWalletId = transaction.getToWalletId();
            direction = Direction.OUTGOING;
        }
        return new TransactionHistoryResponse(transaction.getTransactionId(),
                direction, counterPartyWalletId,
                transaction.getAmount(), transaction.getStatus(),
                transaction.getCreatedAt());
    }
}
