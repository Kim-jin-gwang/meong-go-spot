package com.meonggo.backend.photo.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;

/** 별도 JVM에서 전체 테스트 러너의 힙 사용과 분리해 디코더의 자원 경계를 검증한다. */
public final class PhotoNormalizerResourceProbe {
    private PhotoNormalizerResourceProbe() {}

    public static void main(String[] arguments) throws Exception {
        byte[] input = arguments[0].equals("palette") ? palette() : large();
        var result =
                new PhotoNormalizer()
                        .normalize(
                                List.of(
                                        new MockMultipartFile(
                                                "photos", "ignored", "image/png", input)))
                        .getFirst();
        if (arguments[0].equals("palette")) {
            if (result.width() != 512 || result.height() != 512) {
                throw new AssertionError("Palette size");
            }
        } else if (result.width() != 2560 || result.height() != 4096) {
            throw new AssertionError("Large size");
        }
        System.out.println("PASS");
    }

    private static byte[] palette() throws Exception {
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_BYTE_INDEXED);
        var original = new ByteArrayOutputStream();
        ImageIO.write(image, "png", original);
        var text = new ByteArrayOutputStream();
        text.write(new byte[] {'x', 0, 0});
        try (var zip = new DeflaterOutputStream(text)) {
            zip.write(new byte[262144]);
        }
        byte[] chunk = chunk("zTXt", text.toByteArray());
        var output = new ByteArrayOutputStream();
        byte[] png = original.toByteArray();
        output.write(png, 0, 33);
        for (int index = 0; index < 1000; index++) {
            output.write(chunk);
        }
        output.write(png, 33, png.length - 33);
        return output.toByteArray();
    }

    private static byte[] large() throws Exception {
        int width = 8000;
        int height = 5000;
        var compressed = new ByteArrayOutputStream();
        byte[] row = new byte[1 + width * 8];
        for (int pixel = 0; pixel < width; pixel++) {
            row[1 + pixel * 8 + 6] = (byte) 255;
            row[1 + pixel * 8 + 7] = (byte) 255;
        }
        try (var zip = new DeflaterOutputStream(compressed)) {
            for (int y = 0; y < height; y++) {
                zip.write(row);
            }
        }
        var output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        output.write(
                chunk(
                        "IHDR",
                        ByteBuffer.allocate(13)
                                .putInt(width)
                                .putInt(height)
                                .put((byte) 16)
                                .put((byte) 6)
                                .put(new byte[3])
                                .array()));
        output.write(
                chunk(
                        "eXIf",
                        new byte[] {
                            'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0,
                            0, 0, 0, 0, 0
                        }));
        output.write(chunk("IDAT", compressed.toByteArray()));
        output.write(chunk("IEND", new byte[0]));
        return output.toByteArray();
    }

    private static byte[] chunk(String type, byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        var writer = new DataOutputStream(output);
        writer.writeInt(content.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writer.write(typeBytes);
        writer.write(content);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(content);
        writer.writeInt((int) crc.getValue());
        return output.toByteArray();
    }
}
