package com.meonggo.backend.post.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.image.NormalizedPhoto;
import com.meonggo.backend.post.dto.CreatePostRequest;
import com.meonggo.backend.post.dto.CreatePostRequest.LocationInput;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 길이 prefix와 고정 필드 순서로 경계를 보존한다. 서버 생성 값은 hash에서 제외한다. */
final class PostRequestHash {
    private PostRequestHash() {}

    static String calculate(CreatePostRequest request, List<NormalizedPhoto> photos) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                value(out, "post-create:v1");
                value(out, request.type());
                var details = request.details();
                value(out, details.name());
                value(out, details.species());
                value(out, details.breedName());
                value(out, details.sex());
                value(out, details.color());
                value(out, details.eventDate());
                value(out, details.eventTime());
                value(out, details.featureText());
                location(out, request.eventLocation());
                location(out, request.currentLocation());
                out.writeInt(photos.size());
                for (var photo : photos) value(out, photo.checksum());
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private static void location(DataOutputStream out, LocationInput location) throws IOException {
        out.writeBoolean(location != null);
        if (location == null) return;
        value(out, location.regionCode());
        value(out, location.emdCode());
        value(out, location.exactLocation());
        value(out, location.latitude());
        value(out, location.longitude());
        out.writeBoolean(location.exactLocationVisible());
        value(out, location.disclosurePolicyVersion());
    }

    private static void value(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
