#!/usr/bin/env python3
"""이미 있는 운영 비밀 디렉터리에 FCM 토큰 보호 키 자료를 추가한다 (2026-09-23, v1.4.0).

채팅 푸시(V10)가 들어온 뒤 backend 는 기동 시 `PushDeviceConfiguration` 이 아래 둘을
fail-closed 로 요구한다 — `FCM_CHAT_ENABLED` 가 false 여도 요구한다(토큰 등록 API 가 항상
켜져 있기 때문). 없으면 v1.4.0 이미지는 뜨지 않는다.

  fcm-token-keyring.json           {currentKid,keys}   FCM_TOKEN_ENCRYPTION_KEYRING_PATH
  FCM_TOKEN_LOOKUP_HMAC_KEY_V1     Base64 32바이트     prod.yml

`setup-prod-secrets.py` 는 디렉터리가 있으면 멈추므로(키 교체 방지) 추가만 하는 스크립트를
따로 둔다. 이미 두 키가 있으면 아무것도 바꾸지 않는다. 값은 출력하지 않는다.

코드 제약(`PushTokenProtection`): 암호화 키 정확히 32바이트, 조회 HMAC 32바이트 이상,
둘이 서로 달라야 한다. 키링 속성은 `currentKid`·`keys` 둘뿐.

사용:  sudo python3 infra/scripts/add-fcm-token-secrets.py --dir /home/ubuntu/.secrets/prod
그 뒤 backend 컨테이너를 재기동(또는 릴리즈 배포)하면 반영된다.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import sys
from pathlib import Path

MOUNT = "/run/secrets"
KEYRING_NAME = "fcm-token-keyring.json"
PATH_KEY = "FCM_TOKEN_ENCRYPTION_KEYRING_PATH"
HMAC_KEY = "FCM_TOKEN_LOOKUP_HMAC_KEY_V1"


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def write_like(reference: Path, target: Path, text: str) -> None:
    """reference 와 같은 소유자·권한으로 target 을 원자적으로 쓴다."""
    stat = reference.stat()
    temp = target.with_suffix(target.suffix + ".tmp")
    temp.write_text(text, encoding="utf-8")
    if hasattr(os, "chown"):  # 운영(Linux)에서는 소유자를 맞춘다. Windows 개발 PC 시험용 분기.
        os.chown(temp, stat.st_uid, stat.st_gid)
    os.chmod(temp, stat.st_mode & 0o777)
    os.replace(temp, target)


def main() -> int:
    parser = argparse.ArgumentParser(description="FCM 토큰 보호 키 자료 추가")
    parser.add_argument("--dir", required=True, type=Path, help="운영 비밀 디렉터리 (prod.yml 이 있는 곳)")
    args = parser.parse_args()

    prod = args.dir / "prod.yml"
    if not prod.is_file():
        print(f"prod.yml 이 없습니다: {prod}", file=sys.stderr)
        return 1
    config = json.loads(prod.read_text(encoding="utf-8"))
    keyring = args.dir / KEYRING_NAME

    if PATH_KEY in config and HMAC_KEY in config and keyring.is_file():
        print("이미 설정되어 있습니다. 바꾸지 않았습니다.")
        return 0
    if PATH_KEY in config or HMAC_KEY in config or keyring.is_file():
        print("일부만 있습니다. 손으로 확인한 뒤 셋을 함께 지우고 다시 실행하십시오.", file=sys.stderr)
        return 1

    encryption_key = secrets.token_bytes(32)
    lookup_key = secrets.token_bytes(32)
    while lookup_key == encryption_key:  # 제약: 두 키는 달라야 한다
        lookup_key = secrets.token_bytes(32)

    write_like(prod, keyring, json.dumps({"currentKid": "V1", "keys": {"V1": b64(encryption_key)}}) + "\n")
    config[PATH_KEY] = f"{MOUNT}/{KEYRING_NAME}"
    config[HMAC_KEY] = b64(lookup_key)
    write_like(prod, prod, json.dumps(config, ensure_ascii=False, indent=1) + "\n")

    print(f"추가 완료: {keyring.name}, prod.yml 의 {PATH_KEY}·{HMAC_KEY} (값은 출력하지 않았습니다)")
    print("backend 컨테이너를 재기동하면 반영됩니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
