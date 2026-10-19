package io.ontoforge.platform.identity.yak;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Adapted from Yak Ops DefaultPasswordEncoder (Apache-2.0), commit
 * 85338827bdbb745eaa6a017031640674a298a4f9.
 * Removed Yak utility/SPI dependencies; BCrypt behavior and invalid-hash rejection retained.
 */
public final class LocalPasswordEncoder {
    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(10);
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.toString().isBlank())
            throw new IllegalArgumentException("Password must not be blank");
        return delegate.encode(rawPassword);
    }
    public boolean matches(CharSequence rawPassword,String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) return false;
        try { return delegate.matches(rawPassword,encodedPassword); }
        catch (IllegalArgumentException invalidHash) { return false; }
    }
}
