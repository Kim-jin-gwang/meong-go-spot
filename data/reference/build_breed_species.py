"""보호동물 API 품종 사전 → 품종→축종 CSV (`infra/reference/breed-species.csv`).

원본: 공공데이터포털 `abandonmentPublicService_v2/kind_v2?up_kind_cd=417000|422400` (개·고양이 품종 이름).
분실동물 API(`lossInfoService`)는 축종 코드 없이 품종 이름만 주므로, 이 사전으로 축종을 푼다
(`data/collector/breed_species.py`). 기타축종(429900)은 "기타축종" 하나뿐이라 넣지 않는다 — 해석기가 이름으로 안다.

출력:
    version,breedName,species
    kind-20260915,골든 리트리버,DOG

사용법:
    DATA_GO_KR_SERVICE_KEY=... python build_breed_species.py --version kind-20260915 --out ../../infra/reference/breed-species.csv
    python build_breed_species.py --from-json dogs.json cats.json --version ... --out ...   # 저장해 둔 응답으로
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

KIND_URL = "https://apis.data.go.kr/1543061/abandonmentPublicService_v2/kind_v2"
# 개·고양이 사전 양쪽에 "기타" 가 있다 — 축종을 말해 주지 않는 이름이라 사전에서 뺀다. 해석기가 OTHER 로 처리한다.
NON_BREED_NAMES = {"기타", "기타축종"}
UP_KIND = {"417000": "DOG", "422400": "CAT"}
VERSION = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


def fetch_kinds(service_key: str, up_kind_cd: str) -> list[str]:
    query = urllib.parse.urlencode({"serviceKey": service_key, "up_kind_cd": up_kind_cd, "numOfRows": "500", "_type": "json"})
    with urllib.request.urlopen(f"{KIND_URL}?{query}", timeout=30) as response:
        payload = json.load(response)
    return kinds_from_payload(payload)


def kinds_from_payload(payload: dict) -> list[str]:
    header = (payload.get("response") or {}).get("header") or {}
    if header.get("resultCode") != "00":
        raise SystemExit(f"resultCode={header.get('resultCode')} {header.get('resultMsg')}")
    items = ((payload.get("response") or {}).get("body") or {}).get("items") or {}
    rows = items.get("item") or []
    if isinstance(rows, dict):
        rows = [rows]
    names = [" ".join(str(r.get("kindNm", "")).split()) for r in rows]
    return [n for n in names if n]


def build_rows(version: str, kinds: dict[str, list[str]]) -> list[tuple[str, str, str]]:
    """{species: [이름…]} → 정렬된 (version, breedName, species) 행. 같은 이름이 두 축종에 있으면 실패한다."""
    seen: dict[str, str] = {}
    for species, names in kinds.items():
        for name in names:
            if name in NON_BREED_NAMES:
                continue
            if seen.get(name, species) != species:
                raise SystemExit(f"'{name}' 이 {seen[name]}·{species} 양쪽에 있다 — 사전으로 쓸 수 없다")
            seen[name] = species
    return [(version, name, species) for name, species in sorted(seen.items(), key=lambda kv: (kv[1], kv[0]))]


def write_csv(rows: list[tuple[str, str, str]], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write("version,breedName,species\n")
        for version, name, species in rows:
            if "," in name or '"' in name:
                raise SystemExit(f"품종 이름에 쉼표·따옴표가 있다: {name}")
            fp.write(f"{version},{name},{species}\n")


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--from-json", nargs=2, metavar=("DOGS_JSON", "CATS_JSON"), help="저장해 둔 kind_v2 응답 두 개")
    args = ap.parse_args()
    if not VERSION.match(args.version):
        raise SystemExit("--version 은 영숫자·.·_·- 1~100자")
    if args.from_json:
        kinds = {"DOG": kinds_from_payload(json.loads(Path(args.from_json[0]).read_text(encoding="utf-8"))),
                 "CAT": kinds_from_payload(json.loads(Path(args.from_json[1]).read_text(encoding="utf-8")))}
    else:
        key = (os.environ.get("DATA_GO_KR_SERVICE_KEY") or "").strip()
        if not key:
            raise SystemExit("DATA_GO_KR_SERVICE_KEY 가 비어 있다 (--from-json 으로 대신할 수 있다)")
        kinds = {species: fetch_kinds(key, code) for code, species in UP_KIND.items()}
    rows = build_rows(args.version, kinds)
    write_csv(rows, Path(args.out))
    print(f"{args.out}: {len(rows)}행 (개 {sum(1 for r in rows if r[2] == 'DOG')} · 고양이 {sum(1 for r in rows if r[2] == 'CAT')})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
