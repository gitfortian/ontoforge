package io.ontoforge.business.agent;

/** Stable public failure. Never put prompts, credentials, source values or upstream bodies here. */
public final class AgentException extends RuntimeException {
    private final int http;
    private final String code;
    public AgentException(int http, String code) {
        super(code);
        this.http = http;
        this.code = code;
    }
    public int http() { return http; }
    public String code() { return code; }
}
