package com.aryanyeole.wmp.common.web;

/** A 400: the request itself is invalid — e.g. an unsupported file content type or an oversized upload. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
