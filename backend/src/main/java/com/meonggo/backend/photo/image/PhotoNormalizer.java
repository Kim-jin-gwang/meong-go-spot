package com.meonggo.backend.photo.image;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PhotoNormalizer {
    private static final int MAX_BYTES = 10485760;
    private static final byte[] PNG_MAGIC = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public List<NormalizedPhoto> normalize(List<MultipartFile> photos) {
        if (photos == null || photos.isEmpty() || photos.size() > 10) {
            throw new BusinessException(PhotoErrorCode.INVALID_COUNT);
        }
        List<NormalizedPhoto> results = new ArrayList<>(photos.size());
        for (MultipartFile photo : photos) {
            results.add(normalizePhoto(photo));
        }
        return List.copyOf(results);
    }

    private NormalizedPhoto normalizePhoto(MultipartFile photo) {
        if (photo == null) {
            throw new BusinessException(PhotoErrorCode.INVALID_IMAGE);
        }
        if (photo.getSize() > MAX_BYTES) {
            throw new BusinessException(PhotoErrorCode.TOO_LARGE);
        }
        try {
            byte[] bytes;
            try (var input = photo.getInputStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new BusinessException(PhotoErrorCode.TOO_LARGE);
            }
            boolean jpeg =
                    bytes.length >= 3
                            && bytes[0] == (byte) 255
                            && bytes[1] == (byte) 216
                            && bytes[2] == (byte) 255;
            boolean png = bytes.length >= 8 && Arrays.equals(PNG_MAGIC, Arrays.copyOf(bytes, 8));
            if ((!jpeg && !png)
                    || !(jpeg ? "image/jpeg" : "image/png").equals(photo.getContentType())) {
                throw new BusinessException(PhotoErrorCode.UNSUPPORTED_FORMAT);
            }
            int orientation = ImageStructure.validate(bytes, jpeg);
            PngDecoderInput pngInput = jpeg ? null : PngDecoderInput.prepare(bytes);
            BufferedImage decoded = decode(jpeg ? bytes : pngInput.bytes(), jpeg);
            if (pngInput != null) {
                decoded = pngInput.applyColor(decoded);
            }
            BufferedImage stored = render(decoded, orientation);
            byte[] encoded = encode(stored);
            return new NormalizedPhoto(
                    encoded,
                    stored.getWidth(),
                    stored.getHeight(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // 디코더 원문에는 입력 메타데이터가 포함될 수 있어 예외 원인을 외부로 전달하지 않는다.
            throw new BusinessException(PhotoErrorCode.INVALID_IMAGE);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private BufferedImage decode(byte[] bytes, boolean jpeg) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(PhotoErrorCode.INVALID_IMAGE);
            }
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                // JPEG 는 EOI 뒤에 두 번째 이미지(HDR 게인맵·MPF)가 붙어 있을 수 있어 개수를 1로 못 박지 않는다 —
                // 첫 이미지만 읽어 다시 인코딩한다. PNG 는 ImageStructure 가 IEND 로 끝남을 이미 요구했다.
                if (!(jpeg ? "JPEG" : "PNG").equalsIgnoreCase(reader.getFormatName())
                        || reader.getNumImages(true) < 1) {
                    throw new BusinessException(PhotoErrorCode.INVALID_IMAGE);
                }
                dimensions(reader.getWidth(0), reader.getHeight(0));
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                BufferedImage result = reader.read(0);
                if (result == null || warning[0]) {
                    throw new BusinessException(PhotoErrorCode.INVALID_IMAGE);
                }
                dimensions(result.getWidth(), result.getHeight());
                return result;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 최소 변은 64px — 사진으로서 성립하는지만 본다 (아이콘·추적 픽셀 차단). 메신저로 받아 줄어든 사진도 등록할 수 있어야 한다는 QA
     * 결정(2026-09-21)으로 512px 하한을 내렸다. 작은 사진은 매칭 정확도가 떨어질 수 있지만 등록을 막지는 않는다.
     */
    static void dimensions(int width, int height) {
        if (width < 64
                || height < 64
                || width > 10000
                || height > 10000
                || (long) width * height > 40000000) {
            throw new BusinessException(PhotoErrorCode.INVALID_DIMENSIONS);
        }
    }

    private BufferedImage render(BufferedImage source, int orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        int orientedWidth = orientation >= 5 ? height : width;
        int orientedHeight = orientation >= 5 ? width : height;
        double ratio = Math.min(1.0, 4096.0 / Math.max(width, height));
        int storedWidth = (int) Math.round(orientedWidth * ratio);
        int storedHeight = (int) Math.round(orientedHeight * ratio);
        BufferedImage result =
                new BufferedImage(storedWidth, storedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, storedWidth, storedHeight);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.scale(
                    (double) storedWidth / orientedWidth, (double) storedHeight / orientedHeight);
            AffineTransform transform =
                    switch (orientation) {
                        case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
                        case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
                        case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
                        case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                        case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
                        case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
                        case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
                        default -> new AffineTransform();
                    };
            graphics.transform(transform);
            if (source.getColorModel().getColorSpace().isCS_sRGB()) {
                graphics.drawImage(source, 0, 0, null);
            } else {
                // 색 변환은 작은 행 묶음에서 먼저 수행하여 원본 크기의 RGB 복사본을 만들지 않는다.
                ColorConvertOp converter = new ColorConvertOp(null);
                for (int row = 0; row < height; row += 64) {
                    int rows = Math.min(64, height - row);
                    BufferedImage band =
                            new BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB);
                    converter.filter(source.getSubimage(0, row, width, rows), band);
                    graphics.drawImage(band, 0, row, null);
                }
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private byte[] encode(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
