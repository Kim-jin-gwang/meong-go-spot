package com.meonggo.backend.privacy;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, extension-free address for the user-facing privacy policy document. */
@RestController
public class PrivacyPolicyController {

    private static final Resource PRIVACY_POLICY =
            new ClassPathResource("privacy/privacy-policy.html");

    @GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> privacyPolicy() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(PRIVACY_POLICY);
    }
}
