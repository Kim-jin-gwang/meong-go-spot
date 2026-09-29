package com.meonggo.backend.post.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** 출처별 필드가 섞이지 않는 인증 상세 응답. */
public sealed interface PostDetailResponse {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserPostResponse(
            long postId,
            String type,
            String source,
            String status,
            long version,
            String name,
            String species,
            String breedName,
            String sex,
            String color,
            LocalDate eventDate,
            LocalTime eventTime,
            String featureText,
            LocationResponse eventLocation,
            LocationResponse currentLocation,
            List<PhotoResponse> photos,
            AuthorResponse author,
            ChatResponse chat,
            boolean owner,
            Instant createdAt,
            Instant updatedAt)
            implements PostDetailResponse {
        public UserPostResponse {
            photos = List.copyOf(photos);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicPostResponse(
            long postId,
            String type,
            String source,
            String status,
            String name,
            String species,
            String breedName,
            String sex,
            String color,
            LocalDate eventDate,
            LocalTime eventTime,
            String featureText,
            LocationResponse eventLocation,
            LocationResponse currentLocation,
            List<PhotoResponse> photos,
            ShelterResponse shelter,
            Instant createdAt,
            Instant updatedAt)
            implements PostDetailResponse {
        public PublicPostResponse {
            photos = List.copyOf(photos);
        }
    }

    /**
     * 공공 분실 신고(lossInfoService) 상세 — {@code source=PUBLIC_LOST}. 신고자 연락처는 저장하지 않으므로 응답에도 없고, {@code
     * report.orgName}(관할 기관)과 안내 문구만 준다. 채팅·작성자·버전·현재 위치도 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicLostResponse(
            long postId,
            String type,
            String source,
            String status,
            String name,
            String species,
            String breedName,
            String sex,
            String color,
            LocalDate eventDate,
            LocalTime eventTime,
            String featureText,
            LocationResponse eventLocation,
            List<PhotoResponse> photos,
            LostReportResponse report,
            Instant createdAt,
            Instant updatedAt)
            implements PostDetailResponse {
        public PublicLostResponse {
            photos = List.copyOf(photos);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LostReportResponse(
            String orgName,
            String happenPlace,
            String rfidCode,
            LocalDate firstSeenDate,
            LocalDate lastSeenDate,
            String contactNotice,
            String portalUrl) {
        public static final String CONTACT_NOTICE =
                "신고자 연락처는 동물보호관리시스템(animal.go.kr) 분실동물 게시판에서 확인할 수 있습니다.";
        private static final String PORTAL_LIST =
                "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000";

        /**
         * 포털 게시판의 시·도 검색 코드(select {@code searchUprCd}). 행정구역 카탈로그({@code region-codes.csv})의 시·도
         * 표기와 같아 이름으로 맞춘다 (2026-09-21 게시판 옵션에서 확인). 통합·개편으로 이름이 바뀌면 여기와 카탈로그를 함께 고친다.
         */
        public static final Map<String, String> PORTAL_SIDO_CODES =
                Map.ofEntries(
                        Map.entry("서울특별시", "6110000"),
                        Map.entry("부산광역시", "6260000"),
                        Map.entry("대구광역시", "6270000"),
                        Map.entry("인천광역시", "6280000"),
                        Map.entry("세종특별자치시", "5690000"),
                        Map.entry("대전광역시", "6300000"),
                        Map.entry("울산광역시", "6310000"),
                        Map.entry("경기도", "6410000"),
                        Map.entry("강원특별자치도", "6420000"),
                        Map.entry("충청북도", "6430000"),
                        Map.entry("충청남도", "6440000"),
                        Map.entry("전북특별자치도", "6450000"),
                        Map.entry("경상북도", "6470000"),
                        Map.entry("경상남도", "6480000"),
                        Map.entry("제주특별자치도", "6500000"),
                        Map.entry("전남광주통합특별시", "6130000"));

        /**
         * 포털 분실동물 게시판 링크. 원천에 건별 식별자가 없어 상세로 직접 갈 수 없으므로 목록을 좁혀서 연다 — 실종일({@code
         * searchSDate}/{@code searchEDate}, yyyy-MM-dd), 축종({@code searchUpKindCd}, 공공 API 코드),
         * 성별({@code searchSexCd} M/F), 시·도({@code searchUprCd}). QA(2026-09-21)에서 실종일·축종만으로는 같은 날
         * 다른 개가 먼저 보여 "다른 강아지" 로 읽혔다.
         *
         * @param publicLocation 실종 위치의 공개 표기("대전광역시 유성구") — 첫 어절이 시·도. 모르는 이름이면 시·도 조건을 생략한다.
         */
        public static String portalUrl(
                LocalDate eventDate, String species, String sex, String publicLocation) {
            var url = new StringBuilder(PORTAL_LIST);
            if (eventDate != null)
                url.append("&searchSDate=")
                        .append(eventDate)
                        .append("&searchEDate=")
                        .append(eventDate);
            if ("DOG".equals(species)) url.append("&searchUpKindCd=417000");
            else if ("CAT".equals(species)) url.append("&searchUpKindCd=422400");
            if ("MALE".equals(sex)) url.append("&searchSexCd=M");
            else if ("FEMALE".equals(sex)) url.append("&searchSexCd=F");
            String sidoCode = portalSidoCode(publicLocation);
            if (sidoCode != null) url.append("&searchUprCd=").append(sidoCode);
            return url.toString();
        }

        public static String portalSidoCode(String publicLocation) {
            if (publicLocation == null || publicLocation.isBlank()) return null;
            String sido = publicLocation.strip().split("\\s+", 2)[0];
            return PORTAL_SIDO_CODES.get(sido);
        }

        public LostReportResponse withPortal(
                LocalDate eventDate, String species, String sex, String publicLocation) {
            return new LostReportResponse(
                    orgName,
                    happenPlace,
                    rfidCode,
                    firstSeenDate,
                    lastSeenDate,
                    contactNotice,
                    portalUrl(eventDate, species, sex, publicLocation));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LocationResponse(
            String regionCode,
            String emdCode,
            String publicLocation,
            String exactLocation,
            Boolean exactLocationVisible) {}

    record PhotoResponse(long photoId, String url, int sortOrder) {}

    record AuthorResponse(long memberId, String nickname) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatResponse(boolean available, String reason) {}

    record ShelterResponse(
            String name,
            @JsonInclude(JsonInclude.Include.ALWAYS) String phone,
            String address,
            String jurisdiction,
            String noticeNo,
            LocalDate noticeStartDate,
            LocalDate noticeEndDate,
            String processState) {}
}
