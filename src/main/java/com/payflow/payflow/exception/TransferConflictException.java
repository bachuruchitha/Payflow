package com.payflow.payflow.exception;

// Every optimistic attempt lost to a concurrent change and all of them were rolled back,
// so nothing was committed: the client can safely retry with the same Idempotency-Key.
public class TransferConflictException extends RuntimeException {

    public TransferConflictException(int attempts, Throwable lastConflict) {
        super("Wallet is busy with other transfers, please retry (gave up after " + attempts + " attempts)", lastConflict);
    }
}
