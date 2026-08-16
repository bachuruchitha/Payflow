package com.payflow.payflow.exception;

public class InvalidCredentialsException extends RuntimeException{

    public InvalidCredentialsException(){
        super("Incorrect email or password");
    }
}
