"""행정구역 CSV → Android RegionCatalog.kt 생성.

앱의 지역 목록을 손으로 관리하면 공식 자료와 어긋난다. 실제로 2026-09-11 검수에서
하드코딩 목록 71개 중 7개가 폐지된 행정구역이었다 (광주광역시·전라남도가
전남광주통합특별시로 통합되고 인천 5개 구가 재편된 것을 반영하지 못했다).
그래서 서버가 쓰는 같은 CSV 하나에서 앱 목록도 만든다.

시도 키는 publicLocation 의 첫 토큰이다. 세종특별자치시처럼 시군구 부분이 없는
곳은 표시 이름과 시도 이름이 같아진다 — 그래서 RegionDistrict 가 드롭다운 라벨
(`name`)과 서버 표시값(`display`)을 따로 갖는다.

사용법:
    python build_region_catalog_kt.py ../../infra/reference/region-codes.csv \
        --out ../../android/app/src/main/java/com/hotdog/meonggocuisine/feature/community/ui/RegionCatalog.kt
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

PACKAGE = "com.hotdog.meonggocuisine.feature.community.ui"
MAX_LINE = 140
INDENT = " " * 20  # listOf( 안쪽 요소의 실제 들여쓰기


def read_active(csv: Path) -> list[tuple[str, str]]:
    lines = csv.read_text(encoding="utf-8").splitlines()
    rows = []
    for line in lines[1:]:
        if not line:
            continue
        _, region, emd, display, active = line.split(",")
        if emd or active != "true":
            continue
        rows.append((region, display))
    return rows


def group(rows: list[tuple[str, str]]) -> dict[str, list[tuple[str, str, str]]]:
    """시도 → [(코드, 드롭다운 라벨, 서버 표시값)]. 입력 순서(코드순)를 유지한다."""
    out: dict[str, list[tuple[str, str, str]]] = {}
    for code, display in rows:
        province, _, rest = display.partition(" ")
        label = rest or display
        out.setdefault(province, []).append((code, label, display))
    return out


def entry(code: str, label: str, display: str) -> str:
    # display 가 "시도 + 라벨" 로 복원되는 흔한 경우는 인자를 두 개만 쓴다.
    return (f'RegionDistrict("{code}", "{label}")' if display.endswith(" " + label)
            else f'RegionDistrict("{code}", "{label}", "{display}")')


def pack(entries: list[str]) -> list[str]:
    """한 줄이 140자를 넘지 않게 묶는다."""
    lines: list[str] = []
    current = ""
    for item in entries:
        candidate = f"{current} {item}," if current else f"{item},"
        if current and len(INDENT) + len(candidate) > MAX_LINE:
            lines.append(INDENT + current)
            current = f"{item},"
        else:
            current = candidate
    if current:
        lines.append(INDENT + current)
    return lines


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="행정구역 CSV → RegionCatalog.kt")
    ap.add_argument("csv")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    csv = Path(args.csv)
    grouped = group(read_active(csv))

    body = [
        f"package {PACKAGE}",
        "",
        "/**",
        " * 행정구역 기준 데이터에서 생성한 목록. 손으로 고치지 않는다.",
        " *",
        f" * 원본: infra/reference/{csv.name} (서버가 쓰는 것과 같은 파일)",
        " * 생성: python data/reference/build_region_catalog_kt.py",
        " *",
        " * 목록을 손으로 관리하면 공식 자료와 어긋난다. 폐지된 행정구역을 계속 제공하면",
        " * 사용자가 그 지역을 골라도 서버가 거부한다.",
        " */",
        "object RegionCatalog {",
        "    val districts =",
        "        linkedMapOf(",
    ]
    for province, items in grouped.items():
        body.append(f'{" " * 12}"{province}" to')
        body.append(f'{" " * 16}listOf(')
        body.extend(pack([entry(*item) for item in items]))
        body.append(f'{" " * 16}),')
    body += [
        "        )",
        "}",
        "",
    ]

    out = Path(args.out)
    with open(out, "w", encoding="utf-8", newline="\n") as fp:
        fp.write("\n".join(body))

    total = sum(len(v) for v in grouped.values())
    longest = max(len(line) for line in body)
    print(f"시도 {len(grouped)}개 / 시군구 {total}개 → {out}")
    print(f"가장 긴 줄 {longest}자 (한도 {MAX_LINE})")
    for province, items in grouped.items():
        print(f"  {province}: {len(items)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
