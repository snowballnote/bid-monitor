package com.comhu.bidmonitor.bid.source.registration;

public class DuplicateBidSourceCodeBindingException extends RuntimeException {

    public DuplicateBidSourceCodeBindingException() {
        super("The collector sourceCode is already bound to another registration.");
    }
}
