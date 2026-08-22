package com.payflow.payflow.exception;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException() {
        super("No wallet found for the user");
    }
}