package com.meonggo.backend.global.common.photo;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 공공 API 사진(`storage_type = 'PUBLIC_URL'`)의 출처 URL 을 앱에 그대로 넘겨도 되는지 판정한다.
 *
 * <p>https 만 허용한다. Android 는 targetSdk 28 부터 평문(cleartext) 통신이 기본 차단이라 `http://` 썸네일은 앱에서 반드시 깨진다 —
 * 내려보내도 빈 이미지가 될 뿐이다. 공공 API 이미지 호스트는 같은 경로를 https 로도 제공하므로 적재
 * 쪽(`data/collector/public_ingestion.py`)에서 https 로 저장하고, 여기서는 남아 있는 평문 URL 을 걸러 낸다.
 *
 * <p>호스트·userinfo·포트 검증은 응답에 들어가는 값이 외부 입력(공공 API)에서 왔기 때문에 필요하다.
 */
public final class PublicPhotoUrl {

    private PublicPhotoUrl() {}

    /**
     * 앱에 넘길 수 있으면 percent-encoding 된 ASCII 형태로, 아니면 null 을 반환한다.
     *
     * <p>원본을 그대로 돌려주지 않는 이유: 공공 API 가 한글 파일명을 주면 {@code java.net.URI} 는 예외 없이 받아들이지만 비-ASCII 가 그대로
     * 응답에 실린다. 클라이언트마다 인코딩 시점이 달라지므로 서버가 한 형태로 고정한다. 이미 인코딩된 URL 은 {@code toASCIIString()} 이 다시
     * 인코딩하지 않는다.
     */
    public static String sanitize(String value) {
        if (value == null) return null;
        try {
            // 공공 API 파일명의 약 9% 가 `…518[1].jpg` 처럼 대괄호를 담는다. URI 경로에서 허용되지 않는 문자라
            // 그대로 파싱하면 예외 → null → 썸네일 없음(2026-09-14, ACTIVE 452건). 적재기가 %5B%5D 로 저장하지만
            // 이전에 들어온 값도 있으므로 여기서도 인코딩한다. 파일 서버는 두 형태 모두 같은 파일을 준다.
            URI uri = new URI(value.replace("[", "%5B").replace("]", "%5D"));
            return "https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getRawUserInfo() == null
                            && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    ? uri.toASCIIString()
                    : null;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    /** 노출 여부만 판단할 때 쓴다. 값을 응답에 실을 때는 {@link #sanitize(String)} 의 반환값을 쓴다. */
    public static boolean isSafe(String value) {
        return sanitize(value) != null;
    }
}
