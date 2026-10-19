package io.ontoforge.boot.controller;

import io.ontoforge.platform.operations.PlatformStatusService;
import io.ontoforge.platform.operations.PlatformStatusService.PlatformStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the factual, read-only platform status. */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {
    private final PlatformStatusService service;
    public SystemStatusController(PlatformStatusService service) { this.service=service; }
    @GetMapping("/status")
    public PlatformStatus status() { return service.status(); }
}
