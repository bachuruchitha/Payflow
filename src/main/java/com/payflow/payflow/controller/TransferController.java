package com.payflow.payflow.controller;

import com.payflow.payflow.dto.TransactionHistoryResponse;
import com.payflow.payflow.dto.TransferRequest;
import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.exception.TooManyRequestsException;
import com.payflow.payflow.service.RateLimitResult;
import com.payflow.payflow.service.RateLimiter;
import com.payflow.payflow.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService transferService;

    private final RateLimiter rateLimiter;

    public TransferController(TransferService transferService, RateLimiter rateLimiter) {
        this.transferService = transferService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/transfers")
    ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String idempotencyKey, @AuthenticationPrincipal UUID senderId, @Valid @RequestBody TransferRequest request){
        // First thing in the request, before any transfer logic: an over-limit caller must
        // be turned away without opening a transaction or taking a row lock. The bucket is
        // keyed on the authenticated principal, never on anything in the body -- a caller
        // who could name their own bucket could simply pick a fresh one per request.
        RateLimitResult rateLimit = rateLimiter.check(senderId.toString());
        if (!rateLimit.allowed()) {
            throw new TooManyRequestsException(rateLimiter.retryAfterSeconds(rateLimit));
        }

        // The optimistic path, not the pessimistic one: it is the only executor that writes
        // the OutboxEvent, so this is what gets a transfer onto Kafka and into a Notification.
        // Idempotency replay still sits outside the retry loop, in TransferService.
        TransferResponse transferResponse=transferService.transferOptimistic(idempotencyKey, senderId,request.toWalletId(),request.amount());
        return ResponseEntity.ok(transferResponse);
    }

    @GetMapping("/api/transactions")
    ResponseEntity<Page<TransactionHistoryResponse>> transactions(@AuthenticationPrincipal UUID userId, Pageable pageable) {
        Page<TransactionHistoryResponse> pages = transferService.getTransactions(userId, pageable);
        return ResponseEntity.ok(pages);
    }
}
