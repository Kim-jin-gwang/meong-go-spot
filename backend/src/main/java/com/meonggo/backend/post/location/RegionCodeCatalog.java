package com.meonggo.backend.post.location;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Immutable, version-pinned district and dong reference data for new location writes. */
public final class RegionCodeCatalog {
    public static final int MAX_DATA_BYTES = 16 * 1024 * 1024;
    private static final String HEADER = "version,regionCode,emdCode,publicLocation,active";
    private final Map<String, Entry> districts;
    private final Map<String, Entry> dongs;

    public RegionCodeCatalog(byte[] csv, String version, String expectedSha256) {
        Map<String, Entry> districtRows = new HashMap<>();
        Map<String, Entry> dongRows = new HashMap<>();
        try {
            if (csv == null
                    || csv.length == 0
                    || csv.length > MAX_DATA_BYTES
                    || version == null
                    || !version.matches("[A-Za-z0-9._-]{1,100}")
                    || expectedSha256 == null
                    || !expectedSha256.matches("[a-fA-F0-9]{64}")
                    || !MessageDigest.isEqual(
                            HexFormat.of().parseHex(expectedSha256),
                            MessageDigest.getInstance("SHA-256").digest(csv))) {
                throw new IllegalArgumentException();
            }
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(csv))
                            .toString();
            String[] lines = text.split("\n", -1);
            if (!withoutCarriageReturn(lines[0]).equals(HEADER)) {
                throw new IllegalArgumentException();
            }
            for (int index = 1; index < lines.length; index++) {
                if (index == lines.length - 1 && lines[index].isEmpty()) {
                    continue;
                }
                String[] fields = withoutCarriageReturn(lines[index]).split(",", -1);
                if (fields.length != 5
                        || !fields[0].equals(version)
                        || !fields[1].matches("[0-9]{5}")
                        || (!fields[2].isEmpty()
                                && (!fields[2].matches("[0-9]{10}")
                                        || !fields[2].startsWith(fields[1])))
                        || !validDisplay(fields[3])
                        || !(fields[4].equals("true") || fields[4].equals("false"))) {
                    throw new IllegalArgumentException();
                }
                Entry entry = new Entry(fields[1], fields[3], Boolean.parseBoolean(fields[4]));
                Map<String, Entry> target = fields[2].isEmpty() ? districtRows : dongRows;
                String code = fields[2].isEmpty() ? fields[1] : fields[2];
                if (target.putIfAbsent(code, entry) != null) {
                    throw new IllegalArgumentException();
                }
            }
            if (districtRows.isEmpty()
                    || dongRows.values().stream()
                            .anyMatch(entry -> !districtRows.containsKey(entry.regionCode()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("지역 기준 데이터 설정을 확인할 수 없습니다.");
        }
        districts = Map.copyOf(districtRows);
        dongs = Map.copyOf(dongRows);
    }

    public String resolve(String regionCode, String emdCode) {
        Entry district = regionCode == null ? null : districts.get(regionCode);
        if (district == null || !district.active()) {
            throw new InputValidationException("regionCode", "지원하는 시·군·구를 선택해 주세요.");
        }
        if (emdCode == null) {
            return district.publicLocation();
        }
        Entry dong = dongs.get(emdCode);
        if (dong == null || !dong.active() || !dong.regionCode().equals(regionCode)) {
            throw new InputValidationException("emdCode", "선택한 시·군·구에 속한 읍·면·동을 선택해 주세요.");
        }
        return dong.publicLocation();
    }

    private static String withoutCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static boolean validDisplay(String display) {
        return !display.isBlank()
                && display.equals(display.strip())
                && display.codePointCount(0, display.length()) <= 200
                && display.codePoints()
                        .noneMatch(
                                codePoint ->
                                        Character.isISOControl(codePoint)
                                                || Character.getType(codePoint) == Character.FORMAT
                                                || codePoint == '"');
    }

    private record Entry(String regionCode, String publicLocation, boolean active) {}
}
