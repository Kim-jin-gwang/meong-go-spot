"""행정구역 CSV 검증 — backend RegionCodeCatalog 와 수집기 RegionCatalog 의 검사를 재현한다.

두 소비자가 같은 파일을 읽는다.
  backend/src/main/java/com/meonggo/backend/post/location/RegionCodeCatalog.java
  data/collector/public_ingestion.py (RegionCatalog.from_csv)

사용법:
    python verify_region_codes.py ../../infra/reference/region-codes.csv \
        --version kldc-20260911-sgg --sha256 <64hex> [--android ../../android/...]
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

HEADER = "version,regionCode,emdCode,publicLocation,active"
MAX_BYTES = 16 * 1024 * 1024

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{(' — ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--version", required=True)
    ap.add_argument("--sha256", required=True)
    ap.add_argument("--android", help="RegionSelectionViewModel.kt 경로 — 대조 보고용")
    args = ap.parse_args()

    raw = Path(args.csv).read_bytes()

    print("=== 파일 수준 ===")
    check("BOM 없음", not raw.startswith(b"\xef\xbb\xbf"))
    check(f"16MiB 이하", 0 < len(raw) <= MAX_BYTES, f"{len(raw):,}B")
    check("SHA-256 일치", hashlib.sha256(raw).hexdigest() == args.sha256.lower())
    check("엄격한 UTF-8", True if _decodes(raw) else False)
    text = raw.decode("utf-8")
    check("CR 없음 (LF 고정)", "\r" not in text)

    lines = text.split("\n")
    check("헤더 정확히 일치", lines[0] == HEADER, lines[0][:60])

    print("=== 행 수준 (RegionCodeCatalog 규칙) ===")
    districts: dict[str, tuple[str, bool]] = {}
    dongs: dict[str, str] = {}
    bad = []
    for index, line in enumerate(lines[1:], start=2):
        if not line and index == len(lines):
            continue
        if not line:
            bad.append(f"{index}행 빈 줄"); continue
        fields = line.split(",")
        if len(fields) != 5:
            bad.append(f"{index}행 열 {len(fields)}개"); continue
        version, region, emd, display, active = fields
        if version != args.version:
            bad.append(f"{index}행 version 불일치")
        if not re.fullmatch(r"[0-9]{5}", region):
            bad.append(f"{index}행 regionCode 형식 {region!r}")
        if emd and (not re.fullmatch(r"[0-9]{10}", emd) or not emd.startswith(region)):
            bad.append(f"{index}행 emdCode {emd!r}")
        if active not in ("true", "false"):
            bad.append(f"{index}행 active {active!r}")
        if not _valid_display(display):
            bad.append(f"{index}행 publicLocation {display!r}")
        key, target = (region, districts) if not emd else (emd, dongs)
        if key in target:
            bad.append(f"{index}행 중복 코드 {key}")
        target[key] = (display, active == "true") if not emd else region
    check("형식 위반 없음", not bad, "; ".join(bad[:3]))
    check("시군구 행이 1개 이상", bool(districts), f"{len(districts)}개")
    orphan = [d for d, r in dongs.items() if r not in districts]
    check("상위 없는 읍면동 없음", not orphan, f"{len(orphan)}개")

    print("=== 수집기 규칙 (public_ingestion.RegionCatalog) ===")
    check("version 정규식 통과", bool(re.fullmatch(r"[A-Za-z0-9._-]{1,100}", args.version)))
    check("sha256 정규식 통과", bool(re.fullmatch(r"[0-9a-fA-F]{64}", args.sha256)))
    active_names = [n for n, (d, a) in ((k, v) for k, v in districts.items()) if False] or \
                   [v[0] for v in districts.values() if v[1]]
    check("활성 표시값 중복 없음", len(set(active_names)) == len(active_names),
          f"{len(active_names)}개 중 고유 {len(set(active_names))}개")

    # resolve() 는 표시값이 주소의 부분 문자열인 항목을 파일 순서대로 찾아 첫 번째를 쓴다.
    # 한 표시값이 다른 표시값의 접두사면 주소 하나에 둘 다 걸린다.
    nested = []
    squashed = {n.replace(" ", ""): n for n in active_names}
    for key, name in squashed.items():
        for other_key, other in squashed.items():
            if key != other_key and other_key.startswith(key):
                nested.append((name, other))
    print(f"  INFO  표시값 포함 관계 {len(nested)}쌍 — 수집기 resolve() 의 주소 매핑에 영향")
    for a, b in nested[:5]:
        print(f"          '{a}' ⊂ '{b}'")

    if args.android:
        print("=== Android 하드코딩 목록 대조 ===")
        kt = Path(args.android).read_text(encoding="utf-8")
        blocks = re.split(r'"([가-힣]+(?:시|도))"\s+to', kt)
        android = {}
        # 세 번째 인자(display)는 선택이다 — 없으면 "시도 + name" 이 표시값이라는 뜻이다.
        pattern = r'RegionDistrict\("(\d{5})",\s*"([^"]+)"(?:,\s*"([^"]+)")?\)'
        for i in range(1, len(blocks), 2):
            for code, name, display in re.findall(pattern, blocks[i + 1]):
                android[code] = display or f"{blocks[i]} {name}"
        print(f"  Android {len(android)}개")
        unknown = sorted(c for c in android if c not in districts)
        inactive = sorted(c for c in android if c in districts and not districts[c][1])
        renamed = sorted(c for c in android
                         if c in districts and districts[c][1] and districts[c][0] != android[c])
        check("Android 코드가 모두 CSV 에 있다", not unknown, f"없는 코드 {len(unknown)}개")
        for c in unknown:
            print(f"          {c}  Android='{android[c]}'  → CSV 에 없음")
        check("Android 코드가 모두 활성이다", not inactive, f"비활성 {len(inactive)}개")
        print(f"  INFO  표시값이 다른 코드 {len(renamed)}개")
        for c in renamed:
            print(f"          {c}  Android='{android[c]}'  공식='{districts[c][0]}'")
        print(f"  INFO  CSV 에만 있고 Android 가 제공하지 않는 시군구 {len(districts) - len(android) + len(unknown)}개")

    print()
    print("종합:", "모두 통과" if not failures else f"실패 {len(failures)}건")
    return 0 if not failures else 1


def _decodes(raw: bytes) -> bool:
    try:
        raw.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def _valid_display(text: str) -> bool:
    if not text or text != text.strip() or len(text) > 200:
        return False
    return all(ord(ch) >= 32 and ord(ch) != 127 and ch != '"' and not (0x200B <= ord(ch) <= 0x200F)
               for ch in text)


if __name__ == "__main__":
    sys.exit(main())
