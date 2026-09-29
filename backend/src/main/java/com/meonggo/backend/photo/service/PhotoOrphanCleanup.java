package com.meonggo.backend.photo.service;

import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.photo.storage.PhotoStoragePaths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class PhotoOrphanCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(PhotoOrphanCleanup.class);
    private final PhotoStorage storage;
    private final Predicate<String> referenced;
    private final Clock clock;

    public PhotoOrphanCleanup(PhotoStorage storage, Predicate<String> referenced, Clock clock) {
        this.storage = storage;
        this.referenced = referenced;
        this.clock = clock;
    }

    @Scheduled(cron = "${PHOTO_CLEANUP_CRON:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        cleanRoot(PhotoStoragePaths.STAGING, Duration.ofHours(1));
        cleanRoot(PhotoStoragePaths.ROOT, Duration.ofHours(24));
    }

    private void cleanRoot(String root, Duration grace) {
        for (PhotoStorage.Entry directory : list(root)) {
            if (!directory.directory()
                    || !PhotoStoragePaths.leafDirectory(directory.path())
                    || !parent(directory.path()).equals(root)) continue;
            for (PhotoStorage.Entry entry : list(directory.path())) {
                if (entry.directory()
                        || !PhotoStoragePaths.file(entry.path())
                        || !parent(entry.path()).equals(directory.path())
                        || entry.modifiedAt() == null
                        || !entry.modifiedAt().isBefore(clock.instant().minus(grace))) continue;
                try {
                    if (!referenced.test(entry.path()) && !referenced.test(entry.path()))
                        storage.delete(entry.path());
                } catch (RuntimeException exception) {
                    LOG.warn("Photo cleanup deferred: code=PHOTO-006 count=1");
                }
            }
        }
    }

    private List<PhotoStorage.Entry> list(String path) {
        try {
            return storage.list(path);
        } catch (RuntimeException exception) {
            LOG.warn("Photo cleanup listing deferred: code=PHOTO-006 count=1");
            return List.of();
        }
    }

    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }
}
