package com.meonggo.backend.post.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RegionCodeCatalogTest {
    private static final String HEADER = "version,regionCode,emdCode,publicLocation,active\n";
    private static final String ROWS =
            "test-v1,11680,,테스트 시군구,true\n"
                    + "test-v1,11680,1168010100,테스트 시군구 테스트동,true\n"
                    + "test-v1,11680,1168010200,테스트 폐지동,false\n";

    @Test
    void resolvesExplicitDistrictAndDongAndRejectsUnsupportedInactiveOrWrongParent()
            throws Exception {
        RegionCodeCatalog catalog = catalog(HEADER + ROWS);
        assertThat(catalog.resolve("11680", null)).isEqualTo("테스트 시군구");
        assertThat(catalog.resolve("11680", "1168010100")).isEqualTo("테스트 시군구 테스트동");
        for (String dong :
                new String[] {"1168010200", "1111010100", "1168099999", "", "private-input"}) {
            assertThatThrownBy(() -> catalog.resolve("11680", dong))
                    .isInstanceOf(InputValidationException.class)
                    .hasMessageNotContaining(dong.isEmpty() ? "private-input" : dong);
        }
        assertThatThrownBy(() -> catalog.resolve("99999", null))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void rejectsChecksumVersionMalformedUtf8AndMalformedRows() throws Exception {
        byte[] bytes = (HEADER + ROWS).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new RegionCodeCatalog(bytes, "test-v1", "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        assertThatThrownBy(() -> new RegionCodeCatalog(bytes, "other", checksum(bytes)))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        byte[] invalidUtf8 = {(byte) 0xc3, 0x28};
        assertThatThrownBy(
                        () -> new RegionCodeCatalog(invalidUtf8, "test-v1", checksum(invalidUtf8)))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        for (String rows :
                new String[] {
                    ROWS + ROWS,
                    "",
                    "test-v1,11680,1168010100,고아동,true\n",
                    "test-v1,11680,,구,yes\n",
                    "test-v1,11680,,구,true,extra\n",
                    "test-v1,11680,,구,true\ntest-v1,11680,1111010100,다른구동,true\n",
                    "test-v1,11680,,구,true\ntest-v2,11680,1168010100,동,true\n"
                }) {
            assertThatThrownBy(() -> catalog(HEADER + rows))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();
        }
    }

    @Test
    void inactiveParentPreventsChildWrites() throws Exception {
        RegionCodeCatalog catalog = catalog(HEADER + ROWS.replace("테스트 시군구,true", "테스트 시군구,false"));
        assertThatThrownBy(() -> catalog.resolve("11680", "1168010100"))
                .isInstanceOf(InputValidationException.class);
    }

    private static RegionCodeCatalog catalog(String csv) throws Exception {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return new RegionCodeCatalog(bytes, "test-v1", checksum(bytes));
    }

    private static String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
