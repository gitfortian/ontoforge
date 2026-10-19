package io.ontoforge.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable byte digests shared by model snapshots and source evidence. */
public final class ContentDigests {
    private ContentDigests() {}
    public static String sha256(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 missing", e); }
    }
}
