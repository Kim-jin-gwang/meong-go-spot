package com.meonggo.backend.photo.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class PhotoNormalizerTest {
    private final PhotoNormalizer normalizer = new PhotoNormalizer();

    @Test
    void rejectsEmptyAndExcessiveCount() {
        rejects(List.of(), PhotoErrorCode.INVALID_COUNT);
        rejects(Collections.nCopies(11, file("png", new byte[0])), PhotoErrorCode.INVALID_COUNT);
    }

    @Test
    void normalizesJpegAndTransparentPngInRequestOrder() throws Exception {
        var photos =
                normalizer.normalize(
                        List.of(
                                file("jpeg", picture("jpeg", 600, 512)),
                                file("png", picture("png", 512, 600))));
        assertThat(photos).hasSize(2);
        assertThat(photos.get(0).width()).isEqualTo(600);
        assertThat(photos.get(1).height()).isEqualTo(600);
        var decoded = ImageIO.read(new ByteArrayInputStream(photos.get(1).bytes()));
        assertThat(new Color(decoded.getRGB(0, 0))).isEqualTo(Color.WHITE);
        assertThat(photos.get(0).checksum())
                .isEqualTo(
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(photos.get(0).bytes())));
        byte[] copy = photos.get(0).bytes();
        copy[0] = 0;
        assertThat(photos.get(0).bytes()[0]).isEqualTo((byte) 0xff);
        assertThat(photos.get(0).toString()).doesNotContain(photos.get(0).checksum());
    }

    @Test
    void acceptsTenPhotos() throws Exception {
        assertThat(
                        normalizer.normalize(
                                Collections.nCopies(10, file("png", picture("png", 512, 512)))))
                .hasSize(10);
    }

    @Test
    void rejectsMimeSpoofingAndUnknownFormat() throws Exception {
        rejects(List.of(file("jpeg", picture("png", 512, 512))), PhotoErrorCode.UNSUPPORTED_FORMAT);
        rejects(
                List.of(file("webp", new byte[] {'R', 'I', 'F', 'F'})),
                PhotoErrorCode.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsTruncatedImages() throws Exception {
        for (String format : List.of("jpeg", "png")) {
            byte[] valid = picture(format, 512, 512);
            rejects(
                    List.of(file(format, Arrays.copyOf(valid, valid.length - 5))),
                    PhotoErrorCode.INVALID_IMAGE);
        }
    }

    @Test
    void rejectsConcatenatedPng() throws Exception {
        byte[] valid = picture("png", 512, 512);
        var concatenated = new ByteArrayOutputStream();
        concatenated.write(valid);
        concatenated.write(valid);
        rejects(List.of(file("png", concatenated.toByteArray())), PhotoErrorCode.INVALID_IMAGE);
    }

    /**
     * 요즘 폰 카메라 JPEG 는 EOI 뒤에 울트라 HDR 게인맵·MPF 두 번째 이미지·모션 포토 영상을 덧붙인다. 첫 이미지만 쓰고 뒤는 버린다 (2026-09-23
     * QA — 갤러리 사진 4장이 전부 거부됐다).
     */
    @Test
    void acceptsJpegWithTrailingDataAfterEoiAndKeepsOnlyTheFirstImage() throws Exception {
        byte[] first = picture("jpeg", 640, 480);
        byte[] second = picture("jpeg", 200, 100);
        var trailer = new ByteArrayOutputStream();
        trailer.write(first);
        trailer.write(second); // MPF 처럼 두 번째 JPEG
        var garbage = new ByteArrayOutputStream();
        garbage.write(first);
        garbage.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}); // 임의의 꼬리 바이트

        for (byte[] bytes : List.of(trailer.toByteArray(), garbage.toByteArray())) {
            var normalized = normalizer.normalize(List.of(file("jpeg", bytes)));
            assertThat(normalized).hasSize(1);
            assertThat(normalized.getFirst().width()).isEqualTo(640);
            assertThat(normalized.getFirst().height()).isEqualTo(480);
            assertThat(normalized.getFirst().bytes().length).isLessThan(bytes.length);
        }
    }

    @Test
    void rejectsOversizedFilesAndDimensions() throws Exception {
        rejects(List.of(file("png", new byte[10485761])), PhotoErrorCode.TOO_LARGE);
        rejects(List.of(file("png", picture("png", 63, 64))), PhotoErrorCode.INVALID_DIMENSIONS);
        rejects(List.of(file("png", picture("png", 64, 63))), PhotoErrorCode.INVALID_DIMENSIONS);
        rejects(
                List.of(file("png", picture("png", 10001, 512))),
                PhotoErrorCode.INVALID_DIMENSIONS);
        rejects(
                List.of(file("png", picture("png", 6400, 6251))),
                PhotoErrorCode.INVALID_DIMENSIONS);
    }

    @Test
    void scalesLongEdgeWithoutUpscaling() throws Exception {
        var result =
                normalizer.normalize(List.of(file("png", picture("png", 5000, 1000)))).getFirst();
        assertThat(result.width()).isEqualTo(4096);
        assertThat(result.height()).isEqualTo(819);
    }

    @Test
    void appliesExifOrientationAndStripsMetadata() throws Exception {
        byte[] jpeg = picture("jpeg", 600, 512);
        byte[] exif = {
            'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0,
            6, 0, 0, 0, 0, 0, 0, 0
        };
        var input = new ByteArrayOutputStream();
        input.write(jpeg, 0, 2);
        input.write(new byte[] {(byte) 0xff, (byte) 0xe1, 0, (byte) (exif.length + 2)});
        input.write(exif);
        input.write(jpeg, 2, jpeg.length - 2);
        var result = normalizer.normalize(List.of(file("jpeg", input.toByteArray()))).getFirst();
        assertThat(result.width()).isEqualTo(512);
        assertThat(result.height()).isEqualTo(600);
        assertThat(new String(result.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("Exif");
    }

    @Test
    void rejectsAnimatedPngAndCorruptChunkCrc() throws Exception {
        byte[] png = picture("png", 512, 512);
        byte[] animation = chunk("acTL", new byte[] {0, 0, 0, 2, 0, 0, 0, 0});
        rejects(List.of(file("png", insert(png, 33, animation))), PhotoErrorCode.INVALID_IMAGE);
        png[png.length - 1] ^= 1;
        rejects(List.of(file("png", png)), PhotoErrorCode.INVALID_IMAGE);
    }

    @Test
    void acceptsExactFileLimitAndRejectsOneByteOver() throws Exception {
        byte[] png = picture("png", 512, 512);
        for (int size : List.of(10485759, 10485760)) {
            byte[] padded = insert(png, 33, chunk("tEXt", new byte[size - png.length - 12]));
            assertThat(normalizer.normalize(List.of(file("png", padded)))).hasSize(1);
        }
        rejects(List.of(file("png", new byte[10485761])), PhotoErrorCode.TOO_LARGE);
    }

    @Test
    void mapsAllExifOrientationsToCorrectPixels() throws Exception {
        BufferedImage source = new BufferedImage(600, 512, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 300, 256);
        graphics.dispose();
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(source, "png", encoded);
        int[][] redCoordinates = {
            {50, 50}, {549, 50}, {549, 461}, {50, 461}, {50, 50}, {461, 50}, {461, 549}, {50, 549}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] tiff = {
                'I',
                'I',
                42,
                0,
                8,
                0,
                0,
                0,
                1,
                0,
                0x12,
                1,
                3,
                0,
                1,
                0,
                0,
                0,
                (byte) orientation,
                0,
                0,
                0,
                0,
                0,
                0,
                0
            };
            byte[] png = insert(encoded.toByteArray(), 33, chunk("eXIf", tiff));
            var result = normalizer.normalize(List.of(file("png", png))).getFirst();
            var actual = ImageIO.read(new ByteArrayInputStream(result.bytes()));
            int[] point = redCoordinates[orientation - 1];
            Color pixel = new Color(actual.getRGB(point[0], point[1]));
            assertThat(pixel.getRed()).isGreaterThan(240);
            assertThat(pixel.getGreen()).isLessThan(10);
            assertThat(actual.getColorModel().getColorSpace().isCS_sRGB()).isTrue();
        }
    }

    @Test
    void rejectsMalformedExifWithoutLeakingMetadata() throws Exception {
        byte[] png =
                insert(
                        picture("png", 512, 512),
                        33,
                        chunk("eXIf", new byte[] {'I', 'I', 42, 0, 127, 127, 127, 127}));
        rejects(List.of(file("png", png)), PhotoErrorCode.INVALID_IMAGE);
    }

    @Test
    void acceptsMinimumEdgeWithoutUpscaling() throws Exception {
        NormalizedPhoto result =
                normalizer.normalize(List.of(file("png", picture("png", 64, 64)))).getFirst();
        assertThat(result.width()).isEqualTo(64);
        assertThat(result.height()).isEqualTo(64);
    }

    @Test
    void checksMinimumEdgePerAxis() throws Exception {
        // 한 변만 최소인 사진도 통과해야 한다 — 가로·세로를 따로 본다.
        List<NormalizedPhoto> results =
                normalizer.normalize(
                        List.of(
                                file("png", picture("png", 64, 512)),
                                file("png", picture("png", 512, 64))));
        assertThat(results.get(0).width()).isEqualTo(64);
        assertThat(results.get(0).height()).isEqualTo(512);
        assertThat(results.get(1).width()).isEqualTo(512);
        assertThat(results.get(1).height()).isEqualTo(64);
    }

    @Test
    void acceptsMaximumEdge() throws Exception {
        var result =
                normalizer.normalize(List.of(file("png", picture("png", 10000, 512)))).getFirst();
        assertThat(result.width()).isEqualTo(4096);
    }

    @Test
    void convertsLinearPngIccAndGammaSamplesToSrgb() throws Exception {
        byte[] png = grayPng();
        var profile = new ByteArrayOutputStream();
        profile.write(new byte[] {'p', 0, 0});
        profile.write(
                zip(
                        java.awt.color.ICC_Profile.getInstance(
                                        java.awt.color.ColorSpace.CS_LINEAR_RGB)
                                .getData()));
        for (byte[] metadata :
                List.of(
                        chunk("iCCP", profile.toByteArray()),
                        chunk("gAMA", java.nio.ByteBuffer.allocate(4).putInt(100000).array()))) {
            var result =
                    normalizer
                            .normalize(List.of(file("png", insert(png, 33, metadata))))
                            .getFirst();
            var pixel =
                    new Color(
                            ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(20, 20));
            assertThat(pixel.getRed()).isBetween(186, 190);
        }
    }

    @Test
    void rejectsMissingOrCorruptIdatZlibTrailer() throws Exception {
        byte[] png = grayPng();
        for (boolean truncate : List.of(true, false)) {
            var output = new ByteArrayOutputStream();
            output.write(png, 0, 8);
            int offset = 8;
            while (offset < png.length) {
                int length = java.nio.ByteBuffer.wrap(png, offset, 4).getInt();
                String type =
                        new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                byte[] content = Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
                if (type.equals("IDAT")) {
                    if (truncate) {
                        content = Arrays.copyOf(content, content.length - 4);
                    } else {
                        content[content.length - 1] ^= 1;
                    }
                }
                output.write(chunk(type, content));
                offset += length + 12;
            }
            rejects(List.of(file("png", output.toByteArray())), PhotoErrorCode.INVALID_IMAGE);
        }
    }

    @Test
    void discardsPaletteCompressedTextBeforeDecoding() throws Exception {
        // Invalid compressed text is irrelevant to pixels and must never reach the decoder.
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_BYTE_INDEXED);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] text = chunk("zTXt", new byte[] {'x', 0, 0, 1, 2, 3});
        assertThat(
                        normalizer.normalize(
                                List.of(file("png", insert(output.toByteArray(), 33, text)))))
                .hasSize(1);
    }

    @Test
    void boundsProfileExpansionAndSanitizesInvalidProfile() throws Exception {
        var profile = new ByteArrayOutputStream();
        profile.write(new byte[] {'p', 0, 0});
        profile.write(zip(new byte[1048577]));
        rejects(
                List.of(file("png", insert(grayPng(), 33, chunk("iCCP", profile.toByteArray())))),
                PhotoErrorCode.INVALID_IMAGE);
    }

    @Test
    void acceptsAdam7AndPreservesPaletteTransparency() throws Exception {
        byte[] red = {(byte) 255, 0};
        byte[] green = {0, 0};
        byte[] blue = {0, 0};
        byte[] alpha = {0, (byte) 255};
        var model = new java.awt.image.IndexColorModel(8, 2, red, green, blue, alpha);
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_BYTE_INDEXED, model);
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        var result = normalizer.normalize(List.of(file("png", bytes.toByteArray()))).getFirst();
        assertThat(new Color(ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(20, 20)))
                .isEqualTo(Color.WHITE);
    }

    @Test
    void convertsCustomChromaticities() throws Exception {
        // Linear RGB with red/green primary coordinates swapped: red samples must become green.
        int[] points = {31270, 32900, 30000, 60000, 64000, 33000, 15000, 6000};
        var values = java.nio.ByteBuffer.allocate(32);
        for (int point : points) {
            values.putInt(point);
        }
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 512, 512);
        graphics.dispose();
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        byte[] png = insert(encoded.toByteArray(), 33, chunk("cHRM", values.array()));
        png =
                insert(
                        png,
                        33,
                        chunk("gAMA", java.nio.ByteBuffer.allocate(4).putInt(100000).array()));
        var result = normalizer.normalize(List.of(file("png", png))).getFirst();
        var pixel =
                new Color(ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(20, 20));
        assertThat(pixel.getGreen()).isGreaterThan(245);
        assertThat(pixel.getRed()).isLessThan(10);
    }

    @Test
    void normalizesCompressedPaletteTextWithin64MiBHeap() throws Exception {
        resourceProbe("64m", "palette");
    }

    @Test
    void normalizesFortyMillion16BitRgbaPixelsWithin512MiBHeap() throws Exception {
        resourceProbe("512m", "large");
    }

    @Test
    void converts16BitLinearColorBeforeAlphaComposite() throws Exception {
        byte[] row = new byte[1 + 512 * 8];
        var samples = java.nio.ByteBuffer.wrap(row);
        samples.put((byte) 0);
        for (int pixel = 0; pixel < 512; pixel++) {
            for (int channel = 0; channel < 4; channel++) {
                samples.putShort((short) 32768);
            }
        }
        var raw = new ByteArrayOutputStream();
        for (int y = 0; y < 512; y++) {
            raw.write(row);
        }
        var png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        png.write(
                chunk(
                        "IHDR",
                        java.nio.ByteBuffer.allocate(13)
                                .putInt(512)
                                .putInt(512)
                                .put((byte) 16)
                                .put((byte) 6)
                                .put(new byte[3])
                                .array()));
        png.write(chunk("gAMA", java.nio.ByteBuffer.allocate(4).putInt(100000).array()));
        png.write(chunk("IDAT", zip(raw.toByteArray())));
        png.write(chunk("IEND", new byte[0]));
        var result = normalizer.normalize(List.of(file("png", png.toByteArray()))).getFirst();
        int red =
                new Color(ImageIO.read(new ByteArrayInputStream(result.bytes())).getRGB(20, 20))
                        .getRed();
        assertThat(red).isBetween(219, 224);
    }

    private void resourceProbe(String heap, String fixture) throws Exception {
        String classpath =
                java.util.stream.Stream.of(
                                PhotoNormalizerResourceProbe.class,
                                PhotoNormalizer.class,
                                MockMultipartFile.class,
                                MultipartFile.class,
                                org.springframework.http.HttpStatus.class,
                                org.springframework.util.Assert.class)
                        .map(
                                type -> {
                                    try {
                                        return java.nio.file.Path.of(
                                                        type.getProtectionDomain()
                                                                .getCodeSource()
                                                                .getLocation()
                                                                .toURI())
                                                .toString();
                                    } catch (java.net.URISyntaxException exception) {
                                        throw new IllegalStateException(exception);
                                    }
                                })
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var process =
                new ProcessBuilder(
                                java.nio.file.Path.of(
                                                System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-Djava.awt.headless=true",
                                "-Xmx" + heap,
                                "-cp",
                                classpath,
                                PhotoNormalizerResourceProbe.class.getName(),
                                fixture)
                        .redirectErrorStream(true)
                        .start();
        boolean finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).isTrue();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("PASS");
    }

    private byte[] grayPng() throws Exception {
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(128, 128, 128));
        graphics.fillRect(0, 0, 512, 512);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] zip(byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var compressed = new java.util.zip.DeflaterOutputStream(output)) {
            compressed.write(content);
        }
        return output.toByteArray();
    }

    private byte[] chunk(String type, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        var writer = new java.io.DataOutputStream(output);
        writer.writeInt(content.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        writer.write(typeBytes);
        writer.write(content);
        var crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(content);
        writer.writeInt((int) crc.getValue());
        return output.toByteArray();
    }

    private byte[] insert(byte[] original, int offset, byte[] addition) throws Exception {
        var output = new ByteArrayOutputStream();
        output.write(original, 0, offset);
        output.write(addition);
        output.write(original, offset, original.length - offset);
        return output.toByteArray();
    }

    private void rejects(List<MultipartFile> files, PhotoErrorCode code) {
        assertThatThrownBy(() -> normalizer.normalize(files))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }

    private MockMultipartFile file(String format, byte[] bytes) {
        return new MockMultipartFile("photos", "untrusted.exe", "image/" + format, bytes);
    }

    private byte[] picture(String format, int width, int height) throws Exception {
        var image =
                new BufferedImage(
                        width,
                        height,
                        format.equals("png")
                                ? BufferedImage.TYPE_INT_ARGB
                                : BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
