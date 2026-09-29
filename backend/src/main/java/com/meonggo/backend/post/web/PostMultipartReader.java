package com.meonggo.backend.post.web;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Servlet은 전체 전송량을, 이 경계는 JSON 파트의 크기를 역직렬화 전에 제한한다. */
@Component
public class PostMultipartReader {
    private static final int MAX_METADATA = 65536;
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    public Input read(HttpServletRequest request) {
        try {
            Part metadata = null;
            List<MultipartFile> photos = new ArrayList<>();
            for (Part part : request.getParts()) {
                if ("payload".equals(part.getName())) {
                    if (metadata != null) throw invalid();
                    metadata = part;
                } else if ("photos".equals(part.getName())) {
                    photos.add(new PhotoPart(part));
                } else throw invalid();
            }
            if (metadata == null || metadata.getSize() > MAX_METADATA) throw invalid();
            if (metadata.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(
                            MediaType.parseMediaType(metadata.getContentType())))
                throw new BusinessException(CommonErrorCode.MEDIA_TYPE_NOT_SUPPORTED);
            byte[] bytes;
            try (var input = metadata.getInputStream()) {
                bytes = input.readNBytes(MAX_METADATA + 1);
            }
            if (bytes.length > MAX_METADATA) throw invalid();
            JsonNode root;
            try {
                root = mapper.readTree(bytes);
            } catch (RuntimeException ex) {
                throw new BusinessException(CommonErrorCode.MALFORMED_JSON);
            }
            return new Input(root, List.copyOf(photos));
        } catch (BusinessException | InputValidationException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw new BusinessException(PhotoErrorCode.TOO_LARGE);
        } catch (IOException | ServletException | IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private InputValidationException invalid() {
        return new InputValidationException("payload", "64 KiB 이하의 JSON payload 하나가 필요합니다.");
    }

    public record Input(JsonNode metadata, List<MultipartFile> photos) {
        @Override
        public String toString() {
            return "PostMultipartInput[redacted]";
        }
    }

    private record PhotoPart(Part part) implements MultipartFile {
        @Override
        public String getName() {
            return "photos";
        }

        @Override
        public String getOriginalFilename() {
            return "upload";
        }

        @Override
        public String getContentType() {
            return part.getContentType();
        }

        @Override
        public boolean isEmpty() {
            return part.getSize() == 0;
        }

        @Override
        public long getSize() {
            return part.getSize();
        }

        @Override
        public byte[] getBytes() throws IOException {
            try (var input = getInputStream()) {
                return input.readNBytes(10485761);
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return part.getInputStream();
        }

        @Override
        public void transferTo(File destination) throws IOException {
            try (var input = getInputStream()) {
                Files.copy(input, destination.toPath());
            }
        }

        @Override
        public String toString() {
            return "PhotoPart[redacted]";
        }
    }
}
