"""법정동코드 전체자료 → 운영 행정구역 CSV (시군구 단위).

원본: 행정표준코드관리시스템 (https://www.code.go.kr/stdcode/regCodeL.do)
      "법정동 코드 전체자료" 다운로드. CP949, 탭 구분, 컬럼 3개:
      법정동코드(10자리) / 법정동명 / 폐지여부(존재|폐지)

법정동코드는 시도(2) + 시군구(3) + 읍면동(3) + 리(2) 구조다. 그래서 앱이 요구하는
5자리 regionCode 는 시군구 레벨 코드의 앞 5자리와 그대로 일치한다.

출력은 `backend/docs/post-creation-guide.md` 의 스키마다.

    version,regionCode,emdCode,publicLocation,active

읍면동 행은 만들지 않는다. `emdCode` 는 선택이고(`docs/api-spec.md`) RegionCodeCatalog 는
읍면동 행이 0개여도 검증을 통과한다. 전국 읍면동 4만 행을 검수 대상에 올릴 이유가 없다.

폐지된 시군구도 넣지 않는다. RegionCodeCatalog 는 폐지 코드와 미등록 코드에 **같은**
거부 응답을 내므로(둘 다 "지원하는 시·군·구를 선택해 주세요") 넣어도 동작이 같고
검수 대상만 243행 늘어난다.

대신 폐지 행은 **별칭 CSV** 의 재료가 된다(`--aliases-out`). 공공 API 의 보호소 주소는
개편 전 이름("강원도 화천군", "전라북도 임실군", "광주광역시 북구")을 그대로 쓰는 경우가 많아
적재기(data/collector/public_ingestion.py)가 옛 이름도 현재 코드로 풀어야 한다. 규칙은 기계적이다:
폐지 시군구의 구·군 이름이 현재 존재하는 시군구 **하나** 와만 같으면 그 시군구의 별칭으로 삼는다.
"중구"·"서구" 처럼 여러 시도에 있는 이름은 넣지 않는다 — 추측이 되기 때문이다.
이 CSV 는 백엔드가 읽지 않는다(RegionCodeCatalog 의 헤더 검사가 다른 열을 거부한다).

사용법:
    python build_region_codes.py "법정동코드 전체자료.txt" --version kldc-20260911-sgg \
        --out ../../infra/reference/region-codes.csv
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

SOURCE_ENCODING = "cp949"
HEADER = "version,regionCode,emdCode,publicLocation,active"
ALIVE = "존재"


def read_source(path: Path) -> list[tuple[str, str, str]]:
    lines = path.read_bytes().decode(SOURCE_ENCODING).splitlines()
    if not lines or lines[0].split("\t")[:1] != ["법정동코드"]:
        raise SystemExit("원본 형식이 예상과 다릅니다. 첫 열이 법정동코드여야 합니다.")
    rows = []
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            raise SystemExit(f"열이 3개 미만인 행: {line[:40]!r}")
        rows.append((fields[0].strip(), fields[1].strip(), fields[2].strip()))
    return rows


def is_district(code: str) -> bool:
    """시군구 레벨 — 읍면동·리 자리가 모두 0이고 시군구 자리가 0이 아니다."""
    return len(code) == 10 and code.isdigit() and code[5:] == "00000" and code[2:5] != "000"


def valid_display(text: str) -> bool:
    """RegionCodeCatalog.validDisplay 와 같은 검사."""
    if not text or text != text.strip():
        return False
    if len(text) > 200:
        return False
    return all(
        not (ord(ch) < 32 or ord(ch) == 127) and ch != '"' and not (0x200B <= ord(ch) <= 0x200F)
        for ch in text
    )


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="법정동코드 전체자료 → 시군구 CSV")
    ap.add_argument("source")
    ap.add_argument("--version", required=True, help="영숫자·.·_·- 1~100자")
    ap.add_argument("--out", required=True)
    ap.add_argument("--include-abolished", action="store_true",
                    help="폐지 시군구도 active=false 로 넣는다 (기본은 제외)")
    ap.add_argument("--aliases-out", help="폐지 시군구 이름 → 현재 시군구 별칭 CSV (적재기용)")
    args = ap.parse_args()

    rows = read_source(Path(args.source))
    districts = [(c, n, a) for c, n, a in rows if is_district(c)]
    selected = districts if args.include_abolished else [r for r in districts if r[2] == ALIVE]

    seen: dict[str, str] = {}
    out_rows = []
    for code, name, alive in selected:
        region = code[:5]
        if region in seen:
            raise SystemExit(f"regionCode 중복 {region}: {seen[region]!r} vs {name!r}")
        if not valid_display(name):
            raise SystemExit(f"publicLocation 이 규칙을 위반합니다: {name!r}")
        if "," in name:
            raise SystemExit(f"쉼표를 포함한 이름은 이 CSV 형식이 지원하지 않습니다: {name!r}")
        seen[region] = name
        out_rows.append((region, name, "true" if alive == ALIVE else "false"))

    out_rows.sort(key=lambda r: r[0])
    body = HEADER + "\n" + "".join(
        f"{args.version},{region},,{name},{active}\n" for region, name, active in out_rows)
    # BOM 을 넣지 않는다. 개행은 LF 로 고정한다.
    data = body.encode("utf-8")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as fp:
        fp.write(data)

    print(f"원본 {len(rows):,}행 → 시군구 {len(districts)}행 → 출력 {len(out_rows)}행")
    print(f"파일: {out}  {len(data):,}바이트")
    print(f"REGION_CODE_DATA_VERSION={args.version}")
    print(f"REGION_CODE_DATA_SHA256={hashlib.sha256(data).hexdigest()}")

    if args.aliases_out:
        alive_names = {name for _, name, active in out_rows if active == "true"}
        aliases = build_aliases(districts, alive_names)
        alias_body = ALIAS_HEADER + "\n" + "".join(
            f"{args.version},{alias},{canonical}\n" for alias, canonical in sorted(aliases.items()))
        alias_data = alias_body.encode("utf-8")
        alias_out = Path(args.aliases_out)
        alias_out.parent.mkdir(parents=True, exist_ok=True)
        with open(alias_out, "wb") as fp:
            fp.write(alias_data)
        print(f"별칭: 폐지 시군구 중 {len(aliases)}개 → {alias_out}  {len(alias_data):,}바이트")
        print(f"REGION_ALIAS_DATA_SHA256={hashlib.sha256(alias_data).hexdigest()}")
    return 0


ALIAS_HEADER = "version,alias,publicLocation"

# 시도 자체가 이름을 바꾼(또는 합쳐진) 경우만 별칭 대상이다. 시군구가 다른 시도로 옮겨간 경우
# (강화군 경기→인천, 울진군 강원→경북, 금산군 전북→충남)는 넣지 않는다 — 그 주소가 지금 공공
# 데이터에 나올 일이 없고, "같은 이름이 하나뿐"이라는 기계 규칙은 우연을 잡는다:
# 폐지된 "전라남도 광주시"(옛 광주)를 "경기도 광주시"로 묶는 식이다(2026-09-14 실제 발생).
RENAMED_PROVINCES = {
    "강원도": "강원특별자치도",
    "전라북도": "전북특별자치도",
    "전라남도": "전남광주통합특별시",
    "광주광역시": "전남광주통합특별시",
    "광주직할시": "전남광주통합특별시",
    "제주도": "제주특별자치도",
    "부산직할시": "부산광역시",
    "대구직할시": "대구광역시",
    "인천직할시": "인천광역시",
    "대전직할시": "대전광역시",
}


def build_aliases(districts: list[tuple[str, str, str]], alive_names: set[str]) -> dict[str, str]:
    """폐지 시군구 이름 → 현재 시군구 표시명.

    옛 시도명이 RENAMED_PROVINCES 에 있고, 새 시도명 아래에 같은 구·군 이름이 존재할 때만 넣는다.
    """
    aliases: dict[str, str] = {}
    for _, name, active in districts:
        parts = name.split()
        if active == ALIVE or len(parts) != 2 or name in alive_names:
            continue
        new_province = RENAMED_PROVINCES.get(parts[0])
        if not new_province:
            continue
        canonical = f"{new_province} {parts[1]}"
        if canonical in alive_names and valid_display(name) and "," not in name:
            aliases[name] = canonical
    return aliases


if __name__ == "__main__":
    sys.exit(main())
