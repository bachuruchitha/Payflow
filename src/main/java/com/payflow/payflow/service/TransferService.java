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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final TransferExecutor transferExecutor;
    private final OptimisticTransferService optimisticTransferService;

    public TransferService(WalletRepository walletRepository,
                           TransactionRepository transactionRepository, TransferExecutor transferExecutor,
                           OptimisticTransferService optimisticTransferService) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.transferExecutor = transferExecutor;
        this.optimisticTransferService = optimisticTransferService;
    }

    public TransferResponse transfer(String idempotencyKey, UUID senderId, UUID receiverWalletId, BigDecimal transferAmount) {
        return withIdempotency(idempotencyKey,
                () -> transferExecutor.executeTransfer(idempotencyKey, senderId, receiverWalletId, transferAmount));
    }

    // Idempotency sits OUTSIDE the retry loop: a duplicate key is replayed once here,
    // never retried inside OptimisticTransferService.
    public TransferResponse transferOptimistic(String idempotencyKey, UUID senderId, UUID receiverWalletId, BigDecimal amount) {
        return withIdempotency(idempotencyKey,
                () -> optimisticTransferService.transfer(idempotencyKey, senderId, receiverWalletId, amount));
    }

    private TransferResponse withIdempotency(String idempotencyKey, Supplier<TransferResponse> execute) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        try {
            return execute.get();
        } catch (DataIntegrityViolationException e) {
            return toResponse(transactionRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key conflict but no committed transaction found: " + idempotencyKey)));
        }
    }

    public Page<TransactionHistoryResponse> getTransactions(UUID userId, Pageable pageable) {
        UUID myWalletId = walletRepository.findByUserId(userId).orElseThrow(WalletNotFoundException::new).getId();
        Page<Transaction> transactions = transactionRepository.findByFromWalletIdOrToWalletId(myWalletId, myWalletId, pageable);
        return transactions.map(transaction -> mapToDto(transaction, myWalletId));
    }

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

    private TransferResponse toResponse(Transaction transaction) {
        return new TransferResponse(
                transaction.getTransactionId(),
                transaction.getStatus(),
                transaction.getAmount());
    }


}
