package com.meonggo.backend.photo.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.repository.PhotoAccessRepository;
import com.meonggo.backend.photo.storage.PhotoStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PhotoReadService {
    private final PhotoAccessRepository photos;
    private final PhotoStorage storage;

    public PhotoReadService(PhotoAccessRepository photos, PhotoStorage storage) {
        this.photos = photos;
        this.storage = storage;
    }

    public PhotoContent open(long photoId, Long viewerId) {
        if (photoId <= 0) throw new BusinessException(PhotoErrorCode.NOT_FOUND);
        try {
            var photo =
                    photos.findReadable(photoId, viewerId)
                            .orElseThrow(() -> new BusinessException(PhotoErrorCode.NOT_FOUND));
            return new PhotoContent(storage.open(photo.path()), photo.byteSize(), photo.checksum());
        } catch (DataAccessException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public static final class PhotoContent implements AutoCloseable {
        private final InputStream stream;
        private final long byteSize;
        private final String checksum;

        private PhotoContent(InputStream stream, long byteSize, String checksum) {
            this.stream = stream;
            this.byteSize = byteSize;
            this.checksum = checksum;
        }

        public long byteSize() {
            return byteSize;
        }

        public String checksum() {
            return checksum;
        }

        public void writeTo(OutputStream output) {
            try {
                long copied = stream.transferTo(output);
                if (copied != byteSize)
                    throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
            } catch (IOException ex) {
                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
            }
        }

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ex) {
                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
            }
        }
    }
}
