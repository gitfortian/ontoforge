package io.ontoforge.boot.controller;

import io.ontoforge.platform.identity.B0AuthorizationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B0: expose only the server-derived identity and authorized workspace/package view. */
@RestController
@RequestMapping("/api")
public class IdentityController {
    private final B0AuthorizationService authorization;

    public IdentityController(B0AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/me")
    public B0AuthorizationService.Identity me(Authentication authentication) {
        return authorization.identity(authentication);
    }
}
