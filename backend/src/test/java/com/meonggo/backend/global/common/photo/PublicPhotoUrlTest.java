package com.meonggo.backend.global.common.photo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicPhotoUrlTest {

    @Test
    void cleartextUrlsAreRejectedBecauseAndroidBlocksThem() {
        // targetSdk 28+ 는 평문을 차단한다 — 내려보내면 앱에서 빈 이미지가 된다.
        assertThat(PublicPhotoUrl.sanitize("http://openapi.animal.go.kr/img/1.jpg")).isNull();
        assertThat(PublicPhotoUrl.isSafe("http://openapi.animal.go.kr/img/1.jpg")).isFalse();
    }

    @Test
    void nonAsciiUrlsArePercentEncodedInsteadOfLeakingRawBytes() {
        // java.net.URI 는 한글 경로를 예외 없이 받아들인다. 그대로 실으면 응답에 비-ASCII 가 섞여
        // 클라이언트마다 인코딩 시점이 달라진다. 서버가 한 형태로 고정한다.
        assertThat(PublicPhotoUrl.sanitize("https://h/img/한글.jpg"))
                .isEqualTo("https://h/img/%ED%95%9C%EA%B8%80.jpg");
        // 이미 인코딩된 값은 다시 인코딩하지 않는다 (%25 로 이중 인코딩되면 사진이 깨진다).
        assertThat(PublicPhotoUrl.sanitize("https://h/img/%ED%95%9C.jpg"))
                .isEqualTo("https://h/img/%ED%95%9C.jpg");
    }

    @Test
    void bracketsInPublicFileNamesAreEncodedInsteadOfDroppingTheThumbnail() {
        // 공공 API 파일명의 ~9% 가 `…518[1].jpg` 다. 예전 코드는 URISyntaxException → null → 썸네일 없음.
        assertThat(
                        PublicPhotoUrl.sanitize(
                                "https://openapi.animal.go.kr/files/shelter/2026/09/202609111309518[1].jpg"))
                .isEqualTo(
                        "https://openapi.animal.go.kr/files/shelter/2026/09/202609111309518%5B1%5D.jpg");
        // 적재기가 이미 인코딩해 저장한 값은 그대로 통과한다.
        assertThat(PublicPhotoUrl.sanitize("https://h/a%5B1%5D.jpg"))
                .isEqualTo("https://h/a%5B1%5D.jpg");
    }

    @Test
    void httpsUrlsPassThroughUnchanged() {
        String url = "https://openapi.animal.go.kr/img/1.jpg?seq=3";
        assertThat(PublicPhotoUrl.sanitize(url)).isEqualTo(url);
        assertThat(PublicPhotoUrl.isSafe(url)).isTrue();
        assertThat(PublicPhotoUrl.sanitize("HTTPS://h/a.jpg")).isEqualTo("HTTPS://h/a.jpg");
        assertThat(PublicPhotoUrl.sanitize("https://h:8443/a.jpg"))
                .isEqualTo("https://h:8443/a.jpg");
    }

    @Test
    void malformedOrDangerousValuesBecomeNull() {
        for (String value :
                new String[] {
                    null,
                    "",
                    "   ",
                    "a.jpg", // 스킴 없음
                    "https:///a.jpg", // 호스트 없음
                    "https://user:pw@h/a.jpg", // userinfo — 자격증명이 응답에 섞인다
                    "https://h:0/a.jpg", // 포트 범위 밖
                    "https://h:99999/a.jpg",
                    "file:///etc/passwd",
                    "javascript:alert(1)",
                    "/data/user/images/1/2.jpg", // 사용자 사진 내부 경로는 노출 금지
                    "https://h/ a.jpg" // 공백 — URI 파싱 실패
                }) {
            assertThat(PublicPhotoUrl.sanitize(value)).as("value=%s", value).isNull();
            assertThat(PublicPhotoUrl.isSafe(value)).as("value=%s", value).isFalse();
        }
    }
}
