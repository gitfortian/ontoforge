package io.ontoforge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Test-classpath-only IdP and explicit grants. Never packaged in the application jar.
 * Runs the real production JwtDecoder against an ephemeral RSA public JWKS; no permitAll profile.
 * Keep serve alive while starting/restarting the actual Boot jar, then provision after Flyway.
 */
public final class B0BrowserFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "http://127.0.0.1:18765";

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !java.util.Set.of("serve", "provision").contains(args[0])) {
            throw new IllegalArgumentException("Usage: B0BrowserFixture serve|provision <private fixture.json>");
        }
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/ontoforge_test_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Fixture requires a dedicated loopback ontoforge_test_* database");
        }
        Path file = Path.of(args[1]);
        if (args[0].equals("provision")) {
            provision(url, file);
            return;
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18765), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var out = exchange.getResponseBody()) { out.write(jwks); }
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        ObjectNode config = JSON.createObjectNode();
        config.put("issuer", ISSUER);
        config.put("jwkSetUri", ISSUER + "/jwks");
        config.put("audience", "ontoforge");
        config.put("workspaceId", UUID.randomUUID().toString());
        for (String role : new String[]{"editor", "viewer", "reviewer", "outsider"}) {
            config.put(role + "Subject", role + "-" + UUID.randomUUID());
            config.put(role + "PrincipalId", UUID.randomUUID().toString());
            config.put(role + "Token", token(key, config.path(role + "Subject").asText()));
        }
        var packages = config.putArray("packages");
        for (int i = 0; i < 64; i++) packages.add("acceptance.p" + UUID.randomUUID().toString().replace("-", ""));
        // TEST-CLASSPATH ONLY: one-time Authorization Code + PKCE simulator for Chromium.
        // It does not authenticate real users; never package/deploy outside isolated CI.
        Map<String, String> challenges = new ConcurrentHashMap<>();
        server.createContext("/authorize", exchange -> {
            var query = parameters(exchange.getRequestURI().getRawQuery());
            String redirect = query.getOrDefault("redirect_uri", "");
            String challenge = query.getOrDefault("code_challenge", "");
            String state = query.getOrDefault("state", "");
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"ontoforge-e2e".equals(query.get("client_id"))
                    || !"code".equals(query.get("response_type"))
                    || !"S256".equals(query.get("code_challenge_method"))
                    || !redirect.equals("http://127.0.0.1:5173/")
                    || !challenge.matches("[a-zA-Z0-9_-]{43}")
                    || !state.matches("[a-zA-Z0-9_-]{32}")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            String code = UUID.randomUUID().toString();
            challenges.put(code, challenge);
            exchange.getResponseHeaders().set("Location",
                    redirect + "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin",
                    "http://127.0.0.1:5173");
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var form = parameters(body);
            String code = form.getOrDefault("code", "");
            String original = challenges.remove(code);
            String verifier = form.getOrDefault("code_verifier", "");
            String digest = pkceChallenge(verifier);
            if (original == null || !original.equals(digest)
                    || !"authorization_code".equals(form.get("grant_type"))
                    || !"ontoforge-e2e".equals(form.get("client_id"))
                    || !"http://127.0.0.1:5173/".equals(form.get("redirect_uri"))) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            ObjectNode answer = JSON.createObjectNode();
            try {
                answer.put("access_token", token(key, config.path("editorSubject").asText()));
                answer.put("token_type", "Bearer");
                answer.put("expires_in", 1800);
                byte[] response = JSON.writeValueAsBytes(answer);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        // These are short-lived fixture credentials. Do not print or upload this file as evidence.
        JSON.writeValue(file.toFile(), config);
        System.out.println("Ephemeral loopback JWKS ready; private fixture written (not logged).");
    }


    private static String pkceChallenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> values = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return values;
        for (String item : query.split("&")) {
            int i = item.indexOf('=');
            if (i > 0) {
                values.put(URLDecoder.decode(item.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(item.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String token(RSAKey key, String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("ontoforge")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(7200)))
                .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void provision(String url, Path file) throws Exception {
        var config = JSON.readTree(Files.readString(file));
        UUID workspace = UUID.fromString(config.path("workspaceId").asText());
        try (var db = DriverManager.getConnection(url, System.getenv("TEST_DB_USERNAME"),
                System.getenv("TEST_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try (var insert = db.prepareStatement("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)")) {
                insert.setObject(1, workspace); insert.setString(2, "e2e-" + workspace);
                insert.setString(3, "Isolated browser fixture"); insert.executeUpdate();
            }
            for (String role : new String[]{"editor", "viewer", "reviewer"}) {
                UUID principal = UUID.fromString(config.path(role + "PrincipalId").asText());
                try (var insert = db.prepareStatement("INSERT INTO b0_principal(principal_id,issuer,subject,display_name) VALUES (?,?,?,?)")) {
                    insert.setObject(1, principal); insert.setString(2, ISSUER);
                    insert.setString(3, config.path(role + "Subject").asText()); insert.setString(4, "E2E " + role);
                    insert.executeUpdate();
                }
                try (var insert = db.prepareStatement("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?)")) {
                    insert.setObject(1, workspace); insert.setObject(2, principal);
                    insert.setString(3, role.toUpperCase(java.util.Locale.ROOT)); insert.executeUpdate();
                }
            }
            for (var packageId : config.path("packages")) {
                try (var insert = db.prepareStatement("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)")) {
                    insert.setObject(1, workspace); insert.setString(2, packageId.asText()); insert.executeUpdate();
                }
            }
            db.commit();
        }
        System.out.println("Explicit editor/viewer/reviewer memberships and 64 isolated package assignments committed.");
    }
}
