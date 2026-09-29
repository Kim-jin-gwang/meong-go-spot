#!/usr/bin/env python3
"""운영 기동에 필요한 키 자료를 서버에서 생성한다.

backend 는 기동 시 아래 값을 fail-closed 로 요구한다 (`required()`):
SessionTokenConfiguration, SignupConfiguration, PostLocationConfiguration.
값이 없으면 빈 생성에 실패하고 컨테이너가 뜨지 않는다.

이 스크립트가 만드는 것 — 우리가 생성할 수 있는 암호 자료 전부:
  jwt-private.pem            RSA 2048 PKCS8    AUTH_JWT_PRIVATE_KEY_PATH
  jwt-public-jwks.json       JWKS (RS256)      AUTH_JWT_PUBLIC_JWKS_PATH
  phone-keyring.json         {currentKid,keys} PHONE_DATA_ENCRYPTION_KEYRING_PATH
  location-keyring.json      {currentKid,keys} LOCATION_DATA_ENCRYPTION_KEYRING_PATH
  fcm-token-keyring.json     {currentKid,keys} FCM_TOKEN_ENCRYPTION_KEYRING_PATH (v1.4.0~)
  redis.conf                 requirepass       redis 컨테이너가 읽는다
  prod.yml                   나머지 키·식별자  SPRING_CONFIG_ADDITIONAL_LOCATION

redis 비밀번호는 prod.yml 의 SPRING_DATA_REDIS_URL 안에 넣는다. Spring Boot 는
spring.data.redis.url 이 있으면 host·port·password 를 전부 URL 에서 읽고 별도
spring.data.redis.password 를 무시한다. application.yml 이 url 에 기본값을 주므로
url 은 항상 설정된 상태다 — 그래서 REDIS_CREDENTIALS_PATH 로는 비밀번호가 전달되지
않는다. URL 을 비밀 파일에 두면 compose 나 docker inspect 에 비밀번호가 남지 않는다.

이 스크립트가 만들 수 없는 것 — 외부에서 받아야 한다:
  region-codes.csv           승인된 공식 행정구역 데이터. 지어내지 않는다
                             (docs/superpowers/plans/2026-09-09-post-creation.md)
  solapi.properties          SOLAPI 계정의 apiKey·apiSecret·senderNumber

값은 출력하지 않는다. 기존 디렉터리가 있으면 아무것도 덮어쓰지 않고 멈춘다 —
키를 교체하면 그 키로 암호화한 전화번호·위치를 읽을 수 없게 된다.

소유자를 컨테이너 실행 uid 로 맞춘다. backend 이미지는 uid 999(`app`)로 돌고
(backend/Dockerfile.prod) 이 디렉터리는 0700 이므로, 소유자가 다르면 컨테이너가
비밀 파일을 열지 못해 "Config data resource does not exist" 로 기동에 실패한다.
호스트 쪽을 0644 로 열지 않는 이유는 서버 1 에 gitlab-runner 와 Jenkins 가 함께
돌기 때문이다 — 호스트 경로를 읽을 수 있는 CI 잡에 개인키를 노출하지 않는다.

사용법 (root 가 필요하다 — 소유자를 바꿔야 한다):
    sudo python3 setup-prod-secrets.py [--dir /home/ubuntu/.secrets/prod]
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

KEY_BYTES = 32
JWKS_KID_BYTES = 8
# backend/Dockerfile.prod 의 `useradd --system --uid 999 app` 와 같아야 한다.
CONTAINER_UID = 999


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def b64url_uint(value: int) -> str:
    raw = value.to_bytes((value.bit_length() + 7) // 8, "big")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def write(path: Path, text: str) -> None:
    """0600 으로 새로 만든다. 이미 있으면 예외 — 덮어쓰지 않는다."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fp:
        fp.write(text)


def distinct_keys(count: int) -> list[bytes]:
    """서로 다른 키를 만든다. PostLocationConfiguration 이 위치 키와 로그인 키의
    일치를 거부하고 PhoneProtection.requireDistinctKeys 도 같은 검사를 한다."""
    keys: list[bytes] = []
    while len(keys) < count:
        candidate = secrets.token_bytes(KEY_BYTES)
        if candidate not in keys:
            keys.append(candidate)
    return keys


def main() -> int:
    ap = argparse.ArgumentParser(description="운영 키 자료 생성 (값 출력 없음)")
    ap.add_argument("--dir", default="/home/ubuntu/.secrets/prod")
    ap.add_argument("--owner-uid", type=int, default=CONTAINER_UID,
                    help="컨테이너 실행 uid. backend/Dockerfile.prod 와 같아야 한다")
    ap.add_argument("--skip-chown", action="store_true",
                    help="소유자 변경을 건너뛴다 (root 없이 돌리는 시험 용도)")
    args = ap.parse_args()

    if not args.skip_chown and os.geteuid() != 0:
        print("소유자를 컨테이너 uid 로 바꿔야 하므로 root 로 실행하십시오 (sudo).", file=sys.stderr)
        print("시험 목적이면 --skip-chown 을 주십시오.", file=sys.stderr)
        return 1

    target = Path(args.dir)
    if target.exists():
        print(f"이미 {target} 가 있습니다. 기존 키를 덮어쓰지 않았습니다.", file=sys.stderr)
        print("교체가 정말 필요하면 사람이 직접 옮기고 재암호화 계획을 세우십시오.", file=sys.stderr)
        return 1
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    target.mkdir(mode=0o700)

    # JWT — 개인키와 JWKS 의 활성 kid 모듈러스가 일치해야 기동한다.
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = private.public_key().public_numbers()
    kid = "prod-" + secrets.token_hex(JWKS_KID_BYTES)
    write(target / "jwt-private.pem", private.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii"))
    write(target / "jwt-public-jwks.json", json.dumps({"keys": [{
        "kty": "RSA", "n": b64url_uint(public.n), "e": b64url_uint(public.e),
        "kid": kid, "alg": "RS256", "use": "sig",
    }]}, indent=1) + "\n")

    # 대칭키 5개 — 전화 키링 1, 위치 키링 1, HMAC 3 (로그인 ID·전화 조회·OTP·IP 중
    # 로그인 ID 는 위치 키와도 달라야 한다). 전부 서로 다르게 만든다.
    phone_key, location_key, login_hmac, lookup_hmac, otp_hmac, ip_hmac, fcm_key, fcm_lookup_hmac = distinct_keys(8)

    # 두 키링은 속성이 정확히 2개여야 한다 (currentKid, keys) — 그 외는 거부된다.
    for name, key in (
        ("phone-keyring.json", phone_key),
        ("location-keyring.json", location_key),
        # 채팅 푸시 토큰(V10, v1.4.0~) — PushDeviceConfiguration 이 FCM_CHAT_ENABLED 와 무관하게 요구한다.
        ("fcm-token-keyring.json", fcm_key),
    ):
        write(target / name, json.dumps({"currentKid": "V1", "keys": {"V1": b64(key)}}) + "\n")

    # redis — OTP·가입 증명·로그인 제한이 Lua 원자 연산으로 쓴다. 외부에 열지 않고
    # 비밀번호를 요구하며 noeviction 이어야 한다 (backend/docs/member-signup-guide.md).
    # URL 에 넣을 수 있도록 URL 안전 문자만 쓴다.
    redis_password = secrets.token_urlsafe(32)
    write(target / "redis.conf", "".join(line + chr(10) for line in (
        "# infra/scripts/setup-prod-secrets.py 가 생성했다. 손으로 고치지 않는다.",
        "# 비밀번호를 command 인자로 주지 않는 이유는 docker inspect 와 ps 에 남기 때문이다.",
        f"requirepass {redis_password}",
        "maxmemory-policy noeviction",
        # 짧은 TTL 인증 상태만 담는다. 재기동 시 진행 중이던 OTP 와 제한 카운터가
        # 사라지는 것은 감수한다 — 영속화하면 비밀 데이터가 디스크에 남는다.
        "save \"\"",
        "appendonly no",
    )))

    # 나머지는 설정 파일 하나로 넣는다 (개발의 setup-*-dev.mjs 와 같은 방식).
    # 경로는 컨테이너 안에서의 마운트 경로다.
    mount = "/run/secrets"
    write(target / "prod.yml", json.dumps({
        "AUTH_JWT_ACTIVE_KID": kid,
        "AUTH_JWT_PRIVATE_KEY_PATH": f"{mount}/jwt-private.pem",
        "AUTH_JWT_PUBLIC_JWKS_PATH": f"{mount}/jwt-public-jwks.json",
        "AUTH_LOGIN_ID_HMAC_KEY_V1": b64(login_hmac),
        "PHONE_DATA_ENCRYPTION_KEYRING_PATH": f"{mount}/phone-keyring.json",
        "PHONE_LOOKUP_HMAC_KEY_V1": b64(lookup_hmac),
        "PHONE_OTP_HMAC_KEY_V1": b64(otp_hmac),
        "AUTH_IP_HMAC_KEY_V1": b64(ip_hmac),
        "LOCATION_DATA_ENCRYPTION_KEYRING_PATH": f"{mount}/location-keyring.json",
        "FCM_TOKEN_ENCRYPTION_KEYRING_PATH": f"{mount}/fcm-token-keyring.json",
        "FCM_TOKEN_LOOKUP_HMAC_KEY_V1": b64(fcm_lookup_hmac),
        "SOLAPI_CREDENTIALS_PATH": f"{mount}/solapi.properties",
        "REGION_CODE_DATA_PATH": f"{mount}/region-codes.csv",
        # 비밀번호를 URL 에 넣는다 — 위 헤더 주석의 Spring Boot 동작 참조.
        "SPRING_DATA_REDIS_URL": f"redis://:{redis_password}@redis:6379",
    }, indent=1, ensure_ascii=False) + "\n")

    if not args.skip_chown:
        os.chown(target, args.owner_uid, args.owner_uid)
        for path in target.iterdir():
            os.chown(path, args.owner_uid, args.owner_uid)

    print(f"생성 완료: {target} (값은 출력하지 않았습니다)")
    for path in sorted(target.iterdir()):
        stat = path.stat()
        print(f"  {path.name}  {stat.st_size}B  {oct(stat.st_mode)[-3:]}  uid={stat.st_uid}")
    print()
    print("아직 없는 것 — 이 두 개가 없으면 운영 기동은 계속 실패한다:")
    print(f"  {target}/region-codes.csv     승인된 공식 행정구역 CSV")
    print("      REGION_CODE_DATA_VERSION 과 REGION_CODE_DATA_SHA256 도 함께 정해야 한다")
    print(f"  {target}/solapi.properties    apiKey / apiSecret / senderNumber")
    print("      senderNumber 는 하이픈 없는 숫자 8~15자리여야 한다")
    print()
    print("그 두 파일을 넣은 뒤 반드시 소유자와 권한을 맞추십시오. 안 맞으면 컨테이너가")
    print("읽지 못해 기동에 실패합니다:")
    print(f"  sudo chown {args.owner_uid}:{args.owner_uid} {target}/region-codes.csv {target}/solapi.properties")
    print(f"  sudo chmod 600 {target}/region-codes.csv {target}/solapi.properties")
    print("그리고 REGION_CODE_DATA_VERSION 과 REGION_CODE_DATA_SHA256 을 prod.yml 에 추가하십시오.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
