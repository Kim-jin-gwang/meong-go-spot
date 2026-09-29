package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.meonggo.backend.post.dto.PostDetailResponse.LostReportResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 포털 분실동물 게시판 링크 — 원천에 건별 식별자가 없어 실종일·축종·성별·시·도로 좁힌 목록을 연다. */
class LostReportPortalUrlTest {
    private static final String BOARD =
            "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Test
    void narrowsTheBoardToTheEventDateSpeciesSexAndProvince() {
        assertThat(LostReportResponse.portalUrl(DAY, "DOG", "FEMALE", "대전광역시 유성구"))
                .isEqualTo(
                        BOARD
                                + "&searchSDate=2026-09-15&searchEDate=2026-09-15"
                                + "&searchUpKindCd=417000&searchSexCd=F&searchUprCd=6300000");
        assertThat(LostReportResponse.portalUrl(DAY, "CAT", "MALE", "서울특별시 마포구"))
                .endsWith("&searchUpKindCd=422400&searchSexCd=M&searchUprCd=6110000");
    }

    @Test
    void mapsEveryCatalogProvinceToAPortalCode() {
        // 카탈로그(region-codes.csv)의 시·도 16개가 모두 포털 코드를 가진다 — 하나라도 빠지면 그 지역만 목록이 넓어진다
        assertThat(LostReportResponse.PORTAL_SIDO_CODES).hasSize(16);
        assertThat(LostReportResponse.portalSidoCode("전남광주통합특별시 광산구")).isEqualTo("6130000");
        assertThat(LostReportResponse.portalSidoCode("  강원특별자치도 춘천시 ")).isEqualTo("6420000");
        assertThat(LostReportResponse.portalSidoCode("남극 세종기지")).isNull();
        assertThat(LostReportResponse.portalSidoCode(null)).isNull();
        assertThat(LostReportResponse.portalSidoCode("   ")).isNull();
    }

    @Test
    void fallsBackToTheUnfilteredBoardWhenDateOrSpeciesIsMissing() {
        // OTHER 는 포털 축종 코드가 하나로 정해지지 않고, UNKNOWN 성별·모르는 지역은 조건을 생략한다 — 날짜만 좁힌다
        assertThat(LostReportResponse.portalUrl(DAY, "OTHER", "UNKNOWN", "화성 올림푸스"))
                .isEqualTo(BOARD + "&searchSDate=2026-09-15&searchEDate=2026-09-15");
        assertThat(LostReportResponse.portalUrl(null, null, null, null)).isEqualTo(BOARD);
    }

    @Test
    void withPortalKeepsEveryOtherFieldIntact() {
        var report =
                new LostReportResponse(
                        "대전광역시 유성구",
                        "유성고등학교 골목",
                        "410100129",
                        DAY,
                        DAY,
                        LostReportResponse.CONTACT_NOTICE,
                        null);
        var linked = report.withPortal(DAY, "DOG", "FEMALE", "대전광역시 유성구");
        assertThat(linked.orgName()).isEqualTo(report.orgName());
        assertThat(linked.happenPlace()).isEqualTo(report.happenPlace());
        assertThat(linked.rfidCode()).isEqualTo(report.rfidCode());
        assertThat(linked.contactNotice()).isEqualTo(LostReportResponse.CONTACT_NOTICE);
        assertThat(linked.portalUrl()).startsWith(BOARD);
    }
}
