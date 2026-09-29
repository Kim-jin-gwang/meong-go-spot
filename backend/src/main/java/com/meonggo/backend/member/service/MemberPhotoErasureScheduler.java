package com.meonggo.backend.member.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class MemberPhotoErasureScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(MemberPhotoErasureScheduler.class);
    private final MemberPhotoErasureService service;

    public MemberPhotoErasureScheduler(MemberPhotoErasureService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${MEMBER_PHOTO_ERASURE_POLL_INTERVAL:PT1M}")
    public void run() {
        try {
            service.eraseDuePhotos();
        } catch (RuntimeException exception) {
            LOG.warn("Member photo erasure batch deferred: code=DATABASE_ERROR");
        }
    }
}
