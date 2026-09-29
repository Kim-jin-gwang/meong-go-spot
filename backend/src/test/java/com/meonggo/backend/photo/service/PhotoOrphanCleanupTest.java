package com.meonggo.backend.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meonggo.backend.photo.storage.PhotoStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PhotoOrphanCleanupTest {
    @Test
    void deletesOnlyExpiredUnreferencedValidatedFilesAndRetriesFailures() {
        PhotoStorage storage = mock(PhotoStorage.class);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        String root = "/data/user/images";
        String staging = root + "/.staging";
        String request = staging + "/123e4567-e89b-12d3-a456-426614174000";
        when(storage.list(staging)).thenReturn(List.of(new PhotoStorage.Entry(request, true, now)));
        when(storage.list(request))
                .thenReturn(
                        List.of(
                                new PhotoStorage.Entry(
                                        request + "/1.jpg", false, now.minusSeconds(3601)),
                                new PhotoStorage.Entry(
                                        request + "/2.jpg", false, now.minusSeconds(3600))));
        when(storage.list(root))
                .thenReturn(
                        List.of(
                                new PhotoStorage.Entry(root + "/1", true, now),
                                new PhotoStorage.Entry("/data/shelter/images", true, now)));
        when(storage.list(root + "/1"))
                .thenReturn(
                        List.of(
                                new PhotoStorage.Entry(
                                        root + "/1/1.jpg", false, now.minusSeconds(86401)),
                                new PhotoStorage.Entry(
                                        root + "/1/2.jpg", false, now.minusSeconds(86401)),
                                new PhotoStorage.Entry(
                                        root + "/1/3.jpg", false, now.minusSeconds(86400)),
                                new PhotoStorage.Entry(
                                        "/data/shelter/1.jpg", false, Instant.EPOCH)));
        doThrow(new IllegalStateException("private"))
                .doNothing()
                .when(storage)
                .delete(root + "/1/1.jpg");
        PhotoOrphanCleanup cleanup =
                new PhotoOrphanCleanup(
                        storage,
                        path -> path.equals(root + "/1/2.jpg"),
                        Clock.fixed(now, ZoneOffset.UTC));
        cleanup.run();
        cleanup.run();
        verify(storage, times(2)).delete(root + "/1/1.jpg");
        verify(storage, times(2)).delete(request + "/1.jpg");
        verify(storage, never()).delete(root + "/1/2.jpg");
        verify(storage, never()).delete(root + "/1/3.jpg");
        verify(storage, never()).delete(request + "/2.jpg");
        verify(storage, never()).list("/data/shelter/images");
        verify(storage, never()).delete("/data/shelter/1.jpg");
    }

    @Test
    void rechecksReferenceImmediatelyBeforeDelete() {
        PhotoStorage storage = mock(PhotoStorage.class);
        String root = "/data/user/images";
        when(storage.list(root))
                .thenReturn(List.of(new PhotoStorage.Entry(root + "/1", true, Instant.EPOCH)));
        when(storage.list(root + "/1"))
                .thenReturn(
                        List.of(new PhotoStorage.Entry(root + "/1/1.jpg", false, Instant.EPOCH)));
        AtomicInteger checks = new AtomicInteger();
        new PhotoOrphanCleanup(storage, path -> checks.incrementAndGet() > 1, Clock.systemUTC())
                .run();
        assertThat(checks.get()).isEqualTo(2);
        verify(storage, never()).delete(anyString());
    }
}
