package com.meonggo.backend.photo.storage;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.io.InputStream;
import java.util.List;

public final class UnavailablePhotoStorage implements PhotoStorage {
    private BusinessException unavailable() {
        return new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
    }

    @Override
    public void write(String path, byte[] bytes) {
        throw unavailable();
    }

    @Override
    public void move(String source, String destination) {
        throw unavailable();
    }

    @Override
    public InputStream open(String path) {
        throw unavailable();
    }

    @Override
    public void delete(String path) {
        throw unavailable();
    }

    @Override
    public List<Entry> list(String directory) {
        throw unavailable();
    }
}
