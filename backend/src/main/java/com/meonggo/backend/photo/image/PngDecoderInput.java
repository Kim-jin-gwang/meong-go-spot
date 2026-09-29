package com.meonggo.backend.photo.image;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** 원본 CRC 검증 뒤 픽셀 청크만 디코더에 전달하고 색 프로필은 별도 제한 안에서 처리한다. */
final class PngDecoderInput {
    private static final int MAX_PROFILE_BYTES = 1048576;
    private final byte[] bytes;
    private final ColorSpace colorSpace;

    private PngDecoderInput(byte[] bytes, ColorSpace colorSpace) {
        this.bytes = bytes;
        this.colorSpace = colorSpace;
    }

    static PngDecoderInput prepare(byte[] original) {
        int width = ByteBuffer.wrap(original, 16, 4).getInt();
        int height = ByteBuffer.wrap(original, 20, 4).getInt();
        PhotoNormalizer.dimensions(width, height);
        int depth = original[24] & 255;
        int type = original[25] & 255;
        int channels =
                switch (type) {
                    case 0, 3 -> 1;
                    case 2 -> 3;
                    case 4 -> 2;
                    case 6 -> 4;
                    default -> throw invalid();
                };
        require(
                switch (type) {
                    case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
                    case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
                    default -> depth == 8 || depth == 16;
                });
        require(original[26] == 0 && original[27] == 0 && (original[28] == 0 || original[28] == 1));
        long expandedSize = scanlineBytes(width, height, channels * depth, original[28] == 1);
        var sanitized = new ByteArrayOutputStream();
        sanitized.write(original, 0, 8);
        var compressedPixels = new ByteArrayOutputStream();
        byte[] profile = null;
        double gamma = 0;
        double[] chromaticities = null;
        boolean srgb = false;
        Set<String> singletons = new HashSet<>();
        boolean sawPixels = false;
        boolean pixelsEnded = false;
        int offset = 8;
        while (offset < original.length) {
            int length = ByteBuffer.wrap(original, offset, 4).getInt();
            String chunk = new String(original, offset + 4, 4, StandardCharsets.US_ASCII);
            if (!chunk.equals("IDAT") && sawPixels) {
                pixelsEnded = true;
            }
            if (Set.of("IHDR", "PLTE", "tRNS", "iCCP", "gAMA", "cHRM", "sRGB").contains(chunk)) {
                require(singletons.add(chunk) && !sawPixels);
            }
            switch (chunk) {
                case "IHDR", "PLTE", "tRNS", "IEND" ->
                        sanitized.write(original, offset, length + 12);
                case "IDAT" -> {
                    require(!pixelsEnded);
                    sawPixels = true;
                    compressedPixels.write(original, offset + 8, length);
                    sanitized.write(original, offset, length + 12);
                }
                case "iCCP" -> profile = profile(original, offset + 8, length);
                case "gAMA" -> {
                    require(length == 4);
                    gamma =
                            Integer.toUnsignedLong(
                                            ByteBuffer.wrap(original, offset + 8, 4).getInt())
                                    / 100000.0;
                    require(gamma > 0);
                }
                case "cHRM" -> {
                    require(length == 32);
                    chromaticities = new double[8];
                    for (int index = 0; index < 8; index++) {
                        chromaticities[index] =
                                Integer.toUnsignedLong(
                                                ByteBuffer.wrap(original, offset + 8 + 4 * index, 4)
                                                        .getInt())
                                        / 100000.0;
                    }
                }
                case "sRGB" -> {
                    require(length == 1 && (original[offset + 8] & 255) <= 3);
                    srgb = true;
                }
                default -> require((original[offset + 4] & 32) != 0);
            }
            offset += length + 12;
        }
        inflate(compressedPixels.toByteArray(), expandedSize, false);
        ColorSpace space;
        boolean gray = type == 0 || type == 4;
        if (profile != null) {
            require(!srgb);
            space = new ICC_ColorSpace(ICC_Profile.getInstance(profile));
            require(space.getType() == (gray ? ColorSpace.TYPE_GRAY : ColorSpace.TYPE_RGB));
        } else if (!srgb && (gamma != 0 || chromaticities != null)) {
            space = new PngColorSpace(gray, gamma, chromaticities);
        } else {
            // 태그 없는 RGB는 sRGB로, 태그 없는 회색은 동일한 sRGB 채널값으로 해석한다.
            space =
                    gray
                            ? new PngColorSpace(true, 0, null)
                            : ColorSpace.getInstance(ColorSpace.CS_sRGB);
        }
        return new PngDecoderInput(sanitized.toByteArray(), space);
    }

    byte[] bytes() {
        return bytes;
    }

    BufferedImage applyColor(BufferedImage image) {
        var model = image.getColorModel();
        if (model instanceof IndexColorModel indexed) {
            int[] colors = new int[indexed.getMapSize()];
            indexed.getRGBs(colors);
            for (int index = 0; index < colors.length; index++) {
                int color = colors[index];
                float[] rgb =
                        colorSpace.toRGB(
                                new float[] {
                                    ((color >> 16) & 255) / 255f,
                                    ((color >> 8) & 255) / 255f,
                                    (color & 255) / 255f
                                });
                colors[index] =
                        (color & 0xff000000)
                                | (channel(rgb[0]) << 16)
                                | (channel(rgb[1]) << 8)
                                | channel(rgb[2]);
            }
            var converted =
                    new IndexColorModel(
                            indexed.getPixelSize(),
                            colors.length,
                            colors,
                            0,
                            indexed.hasAlpha(),
                            indexed.getTransparentPixel(),
                            indexed.getTransferType());
            return new BufferedImage(converted, image.getRaster(), false, null);
        }
        require(
                model instanceof ComponentColorModel
                        && model.getNumColorComponents() == colorSpace.getNumComponents());
        var interpreted =
                new ComponentColorModel(
                        colorSpace,
                        model.getComponentSize(),
                        model.hasAlpha(),
                        false,
                        model.getTransparency(),
                        model.getTransferType());
        return new BufferedImage(interpreted, image.getRaster(), false, null);
    }

    private static int channel(float value) {
        return Math.round(Math.clamp(value, 0f, 1f) * 255);
    }

    private static byte[] profile(byte[] bytes, int offset, int length) {
        int nameLength = 0;
        while (nameLength < length && bytes[offset + nameLength] != 0) {
            nameLength++;
        }
        require(
                nameLength >= 1
                        && nameLength <= 79
                        && nameLength + 2 < length
                        && bytes[offset + nameLength + 1] == 0);
        return inflate(
                Arrays.copyOfRange(bytes, offset + nameLength + 2, offset + length),
                MAX_PROFILE_BYTES,
                true);
    }

    private static byte[] inflate(byte[] compressed, long limit, boolean retain) {
        var inflater = new Inflater();
        var expanded = retain ? new ByteArrayOutputStream() : null;
        try {
            inflater.setInput(compressed);
            byte[] block = new byte[8192];
            long total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(block);
                total += count;
                require(total <= limit);
                if (retain) {
                    expanded.write(block, 0, count);
                }
                require(count != 0 || inflater.finished());
            }
            require(inflater.getRemaining() == 0 && (retain || total == limit));
            return retain ? expanded.toByteArray() : new byte[0];
        } catch (DataFormatException exception) {
            throw invalid();
        } finally {
            inflater.end();
        }
    }

    private static long scanlineBytes(int width, int height, int bits, boolean interlaced) {
        if (!interlaced) {
            return (1L + ((long) width * bits + 7) / 8) * height;
        }
        int[] startsX = {0, 4, 0, 2, 0, 1, 0};
        int[] startsY = {0, 0, 4, 0, 2, 0, 1};
        int[] stepsX = {8, 8, 4, 4, 2, 2, 1};
        int[] stepsY = {8, 8, 8, 4, 4, 2, 2};
        long total = 0;
        for (int pass = 0; pass < 7; pass++) {
            int columns = Math.max(0, (width - startsX[pass] + stepsX[pass] - 1) / stepsX[pass]);
            int rows = Math.max(0, (height - startsY[pass] + stepsY[pass] - 1) / stepsY[pass]);
            if (columns > 0) {
                total += (1L + ((long) columns * bits + 7) / 8) * rows;
            }
        }
        return total;
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
