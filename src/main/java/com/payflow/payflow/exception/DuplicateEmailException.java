package com.payflow.payflow.exception;

public class DuplicateEmailException extends RuntimeException{
    public DuplicateEmailException(){
        super("Email already exists");
    }
}
