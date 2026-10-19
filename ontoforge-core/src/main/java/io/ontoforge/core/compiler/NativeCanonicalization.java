package io.ontoforge.core.compiler;

import io.ontoforge.common.ContentDigests;
import java.io.IOException;
import org.erdtman.jcs.JsonCanonicalizer;

/** The existing RFC 8785 byte and digest contract, independent of persistence and HTTP. */
public final class NativeCanonicalization {
    public static final String PROFILE = "jcs-rfc8785-v1";
    private NativeCanonicalization() {}
    public static byte[] canonicalize(String raw) throws IOException {
        return new JsonCanonicalizer(raw).getEncodedUTF8();
    }
    public static String digest(byte[] canonicalBytes) {
        return ContentDigests.sha256(canonicalBytes);
    }
}
