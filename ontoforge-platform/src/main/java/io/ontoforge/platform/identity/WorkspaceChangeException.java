package io.ontoforge.platform.identity;

public final class WorkspaceChangeException extends RuntimeException {
    private final String code;
    public WorkspaceChangeException(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
}
