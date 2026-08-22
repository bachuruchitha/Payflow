package com.payflow.payflow.controller;

import com.payflow.payflow.dto.TransferRequest;
import com.payflow.payflow.dto.TransferResponse;
import com.payflow.payflow.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/api/transfers")
    ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal UUID senderId, @Valid @RequestBody TransferRequest request){
        TransferResponse transferResponse=transferService.transfer(senderId,request.toWalletId(),request.amount());
        return ResponseEntity.ok(transferResponse);
    }
}
