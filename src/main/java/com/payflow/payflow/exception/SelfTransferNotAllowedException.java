package com.payflow.payflow.exception;

public class SelfTransferNotAllowedException extends RuntimeException{

    public SelfTransferNotAllowedException(){
        super("Self transfer not allowed");
    }
}
