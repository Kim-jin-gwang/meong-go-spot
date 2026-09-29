package com.meonggo.backend.photo.image;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** ImageIO가 관대하게 복구하는 잘림과 APNG를 디코딩 전에 거부한다. */
final class ImageStructure {
    private ImageStructure() {}

    static int validate(byte[] bytes, boolean jpeg) {
        return jpeg ? jpegOrientation(bytes) : pngOrientation(bytes);
    }

    private static int pngOrientation(byte[] bytes) {
        int offset = 8;
        int orientation = 1;
        boolean first = true;
        boolean pixels = false;
        while (offset <= bytes.length - 12) {
            int length = ByteBuffer.wrap(bytes, offset, 4).getInt();
            require(length >= 0 && length <= bytes.length - offset - 12);
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            require(!first || (type.equals("IHDR") && length == 13));
            require(!type.equals("acTL") && !type.equals("fcTL") && !type.equals("fdAT"));
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, length + 4);
            require(
                    (int) crc.getValue()
                            == ByteBuffer.wrap(bytes, offset + 8 + length, 4).getInt());
            if (type.equals("eXIf")) {
                orientation = exifOrientation(bytes, offset + 8, length);
            }
            if (type.equals("IDAT")) {
                pixels = true;
            }
            offset += length + 12;
            if (type.equals("IEND")) {
                require(length == 0 && pixels && offset == bytes.length);
                return orientation;
            }
            first = false;
        }
        throw invalid();
    }

    private static int jpegOrientation(byte[] bytes) {
        int offset = 2;
        int orientation = 1;
        boolean entropy = false;
        boolean pixels = false;
        while (offset < bytes.length) {
            if ((bytes[offset++] & 255) != 255) {
                require(entropy);
                continue;
            }
            while (offset < bytes.length && (bytes[offset] & 255) == 255) {
                offset++;
            }
            require(offset < bytes.length);
            int marker = bytes[offset++] & 255;
            if (marker == 0 || (marker >= 0xd0 && marker <= 0xd7)) {
                require(entropy);
                continue;
            }
            if (marker == 0xd9) {
                // EOI 뒤에 바이트가 더 있어도 거부하지 않는다 (2026-09-23). 요즘 폰 카메라는 본 이미지 뒤에
                // 울트라 HDR 게인맵·MPF 두 번째 이미지·모션 포토 영상을 덧붙이므로, 정확히 EOI 에서 끝나야
                // 한다는 규칙은 일반 사용자 사진 대부분을 막았다. 잘린 파일(EOI 없음)은 여전히 아래에서 거부한다.
                // 덧붙은 데이터는 디코더가 첫 이미지만 읽고 다시 인코딩하므로 저장되지 않는다.
                require(pixels);
                return orientation;
            }
            require(marker != 0xd8 && offset <= bytes.length - 2);
            int length = ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
            require(length >= 2 && length <= bytes.length - offset);
            if (marker == 0xe1
                    && length >= 8
                    && bytes[offset + 2] == 'E'
                    && bytes[offset + 3] == 'x'
                    && bytes[offset + 4] == 'i'
                    && bytes[offset + 5] == 'f'
                    && bytes[offset + 6] == 0
                    && bytes[offset + 7] == 0) {
                orientation = exifOrientation(bytes, offset + 8, length - 8);
            }
            entropy = marker == 0xda;
            pixels |= entropy;
            offset += length;
        }
        throw invalid();
    }

    private static int exifOrientation(byte[] bytes, int offset, int length) {
        require(length >= 8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length).slice();
        short order = buffer.getShort(0);
        require(order == 0x4949 || order == 0x4d4d);
        buffer.order(order == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        require(buffer.getShort(2) == 42);
        long directory = Integer.toUnsignedLong(buffer.getInt(4));
        require(directory >= 8 && directory <= length - 2);
        int start = (int) directory;
        int entries = Short.toUnsignedInt(buffer.getShort(start));
        require((long) start + 2 + (long) entries * 12 + 4 <= length);
        for (int index = 0; index < entries; index++) {
            int entry = start + 2 + index * 12;
            if (Short.toUnsignedInt(buffer.getShort(entry)) == 0x112) {
                require(buffer.getShort(entry + 2) == 3 && buffer.getInt(entry + 4) == 1);
                int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
                require(orientation >= 1 && orientation <= 8);
                return orientation;
            }
        }
        return 1;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(PhotoErrorCode.INVALID_IMAGE);
    }
}
