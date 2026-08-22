package com.payflow.payflow.controller;

import com.payflow.payflow.dto.TopUpRequest;
import com.payflow.payflow.dto.WalletResponse;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {


    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }


    @GetMapping("/me")
    public WalletResponse getMyWallet(@AuthenticationPrincipal UUID userId) {
        Wallet wallet = walletService.getWallet(userId);
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getCurrency(), wallet.getCreatedAt(), wallet.getBalance());

    }

    @PostMapping("/me/topup")
    public ResponseEntity<WalletResponse> topUp(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TopUpRequest request) {
        Wallet wallet = walletService.topUp(userId, request.amount());
        WalletResponse walletResponse = new WalletResponse(wallet.getId(), userId, wallet.getCurrency(), wallet.getCreatedAt(), wallet.getBalance());
        return ResponseEntity.ok(walletResponse);
    }
}