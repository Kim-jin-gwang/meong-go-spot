#!/usr/bin/env python3
"""노출된 대칭키만 교체한다 — JWT 키쌍과 키링은 건드리지 않는다.

setup-prod-secrets.py 는 처음 한 번 전부를 만들고 기존 디렉터리를 덮어쓰지 않는다.
값 하나가 새었을 때 전부를 다시 만들면 JWT kid 와 암호화 키링까지 바뀌어
발급된 토큰과 암호화된 개인정보가 전부 무효가 된다. 그래서 교체 대상을 좁힌다.

교체하는 것:
  AUTH_LOGIN_ID_HMAC_KEY_V1 · PHONE_LOOKUP_HMAC_KEY_V1
  PHONE_OTP_HMAC_KEY_V1 · AUTH_IP_HMAC_KEY_V1   (prod.yml)
  redis 비밀번호                                  (redis.conf + SPRING_DATA_REDIS_URL)

그대로 두는 것:
  JWT 개인키·JWKS·활성 kid, 전화/위치 암호화 키링, SOLAPI·행정구역 설정

교체 후 영향:
  - 조회 HMAC 2종은 member 의 login_id_hash·phone_lookup_hash 를 만드는 키다.
    **회원이 이미 있으면 그 회원들은 로그인할 수 없게 된다.** 아래 확인을 강제한다.
  - OTP·IP HMAC 은 redis 의 짧은 TTL 상태에만 쓰이므로 진행 중이던 인증만 무효다.
  - redis 비밀번호는 redis 와 backend 를 함께 재기동해야 맞물린다.

사용법 (서버 1):
  sudo python3 rotate-exposed-secrets.py --dir /home/ubuntu/.secrets/prod --confirm
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import sys
from pathlib import Path

ROTATED_HMAC = (
    "AUTH_LOGIN_ID_HMAC_KEY_V1",
    "PHONE_LOOKUP_HMAC_KEY_V1",
    "PHONE_OTP_HMAC_KEY_V1",
    "AUTH_IP_HMAC_KEY_V1",
)
REDIS_URL_KEY = "SPRING_DATA_REDIS_URL"


def distinct_keys(count: int) -> list[str]:
    """서로 다른 키를 보장한다 — 우연히 같은 값이 나오면 한 키의 유출이 다른 키까지 깬다."""
    keys: list[bytes] = []
    while len(keys) < count:
        candidate = secrets.token_bytes(32)
        if candidate not in keys:
            keys.append(candidate)
    return [base64.b64encode(k).decode() for k in keys]


def replace_preserving(path: Path, text: str) -> None:
    """소유자·권한을 유지한 채 원자적으로 바꾼다. 임시 파일은 같은 디렉터리에 만든다."""
    stat = path.stat()
    temp = path.with_name(path.name + ".new")
    with open(temp, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    os.chown(temp, stat.st_uid, stat.st_gid)
    os.chmod(temp, stat.st_mode & 0o777)
    os.replace(temp, path)


def main() -> int:
    parser = argparse.ArgumentParser(description="노출된 대칭키 교체")
    parser.add_argument("--dir", required=True, type=Path)
    parser.add_argument("--confirm", action="store_true",
                        help="회원 데이터가 없음을 확인했다는 표시. 없으면 아무것도 하지 않는다")
    args = parser.parse_args()

    prod = args.dir / "prod.yml"
    redis_conf = args.dir / "redis.conf"
    for path in (prod, redis_conf):
        if not path.is_file():
            print(f"없는 파일: {path}", file=sys.stderr)
            return 2

    config = json.loads(prod.read_text(encoding="utf-8"))
    missing = [name for name in (*ROTATED_HMAC, REDIS_URL_KEY) if name not in config]
    if missing:
        print(f"prod.yml 에 없는 키: {', '.join(missing)}", file=sys.stderr)
        return 2

    conf_text = redis_conf.read_text(encoding="utf-8")
    if not re.search(r"^requirepass .+$", conf_text, flags=re.MULTILINE):
        print("redis.conf 에 requirepass 줄이 없습니다", file=sys.stderr)
        return 2

    if not args.confirm:
        print("교체 대상:")
        for name in ROTATED_HMAC:
            print(f"  {name}")
        print(f"  {REDIS_URL_KEY} 안의 redis 비밀번호 (+ redis.conf)")
        print()
        print("조회 HMAC 을 바꾸면 기존 회원은 로그인할 수 없습니다.")
        print("member 가 0행인지 확인한 뒤 --confirm 을 붙여 다시 실행하십시오.")
        return 1

    new_hmac = distinct_keys(len(ROTATED_HMAC))
    for name, value in zip(ROTATED_HMAC, new_hmac):
        config[name] = value

    # URL 안전 문자만 쓴다 — 비밀번호가 URL 한가운데 들어가므로 인코딩이 필요하면 깨진다.
    redis_password = secrets.token_urlsafe(32)
    config[REDIS_URL_KEY] = f"redis://:{redis_password}@redis:6379"

    replace_preserving(redis_conf, re.sub(
        r"^requirepass .+$", f"requirepass {redis_password}", conf_text, count=1, flags=re.MULTILINE))
    replace_preserving(prod, json.dumps(config, ensure_ascii=False, indent=1) + "\n")

    print("교체 완료 (값은 출력하지 않습니다)")
    print(f"  prod.yml     {len(ROTATED_HMAC)}개 HMAC + redis URL")
    print("  redis.conf   requirepass")
    print()
    print("이어서 실행하십시오 — redis 를 먼저 올려야 backend 가 붙습니다:")
    print("  docker restart meong-go-spot-redis-1 && sleep 5 && docker restart meong-go-spot-backend-1")
    return 0


if __name__ == "__main__":
    sys.exit(main())
