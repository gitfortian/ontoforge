package io.ontoforge.platform.identity;

/** A user-visible conflict or missing account; the HTTP adapter owns status mapping. */
public final class AccountChangeException extends RuntimeException {
    private final String code;
    public AccountChangeException(String code,String message) { super(message);this.code=code; }
    public String code() { return code; }
}
