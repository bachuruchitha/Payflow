package com.payflow.payflow.controller;

import com.payflow.payflow.dto.WalletResponse;
import com.payflow.payflow.entity.Wallet;
import com.payflow.payflow.repository.WalletRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }


    @GetMapping("/me")
    public WalletResponse getMyWallet(@AuthenticationPrincipal UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId).orElseThrow(() -> new IllegalStateException("Wallet not found"));
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getCurrency(), wallet.getCreatedAt());
    }
}