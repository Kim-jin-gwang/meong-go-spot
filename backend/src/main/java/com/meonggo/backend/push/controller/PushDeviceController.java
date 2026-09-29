package com.meonggo.backend.push.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.post.web.PostJsonReader;
import com.meonggo.backend.push.service.PushDeviceService;
import com.meonggo.backend.push.web.PushDeviceInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/me/push-devices")
public class PushDeviceController {
    private final PostJsonReader reader;
    private final PushDeviceService devices;

    public PushDeviceController(PostJsonReader reader, PushDeviceService devices) {
        this.reader = reader;
        this.devices = devices;
    }

    @PutMapping(value = "/{installationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @PathVariable UUID installationId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        devices.register(
                principal.sessionId(),
                principal.memberId(),
                installationId,
                PushDeviceInput.from(reader.read(request)));
    }

    @DeleteMapping("/{installationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(
            @PathVariable UUID installationId, @AuthenticationPrincipal AuthPrincipal principal) {
        devices.unregister(principal.sessionId(), principal.memberId(), installationId);
    }
}
