# 고정 비밀번호 차단 목록

- 원본: https://github.com/danielmiessler/SecLists/blob/38d4d047c091c7db3724711ede4ba1dd7eec0a5f/Passwords/Common-Credentials/Pwdb_top-100000.txt
- commit: `38d4d047c091c7db3724711ede4ba1dd7eec0a5f`
- SHA-256: `07f876a616f08fb2cc5c3e0ce04e4a6d1123380580472b0997baebc4e8226977`
- 크기: 828,498 bytes, 100,000 lines, UTF-8, LF
- 라이선스: 같은 디렉터리 `LICENSE` (SecLists MIT 원문)

`docs/password-policy.md`에 고정한 공개 데이터 원본이다. 애플리케이션 시작 때 해시·바이트·
줄 수·UTF-8을 검사한다. `.gitattributes`의 `-diff`는 데이터 전체가 코드 리뷰를 덮지 않도록
하는 표시 설정이며 원본 파일은 그대로 추적한다. 목록을 갱신하려면 정책의 원본·해시와
로더 상수를 함께 변경해야 한다.
