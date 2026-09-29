"""품종 이름 → 축종(DOG/CAT/OTHER) 해석 — 분실동물 API 에는 축종 코드가 없다 (#145).

`lossInfoService` 레코드는 `kindCd` 에 "믹스견"·"코리안 숏헤어"·"푸들" 같은 **품종 이름**만 준다.
보호동물 API 의 품종 사전(`abandonmentPublicService_v2/kind_v2`, 개 206·고양이 38)을
`infra/reference/breed-species.csv` 로 두고(`data/reference/build_breed_species.py`), 여기서 읽어 푼다.

규칙 (2026-09-15 실측 163건 → 161건 결정):
    1. 공백을 정리한 이름이 사전에 그대로 있으면 그 축종
    2. 없으면 사전 이름이 입력을 **포함**하는 것들을 모아, 축종이 하나로 모이면 그 축종
       ("푸들" ⊂ "토이 푸들"·"미니어쳐 푸들"… → DOG). 여러 축종에 걸치면 결정하지 않는다
    3. "기타축종"·"기타" 는 OTHER — 서비스 범위 밖(개·고양이만, 2026-09-14 결정)
    4. 그 밖은 None — 호출자가 적재에서 빼고 실패 사유를 남긴다
"""

from __future__ import annotations

import csv
from pathlib import Path

OTHER_NAMES = {"기타축종", "기타"}


def _clean(name: str) -> str:
    return " ".join(str(name).split())


def load_table(path: Path) -> dict[str, str]:
    """breed-species.csv → {품종 이름: 축종}. 헤더는 `version,breedName,species` 여야 한다."""
    with path.open(encoding="utf-8", newline="") as fp:
        reader = csv.DictReader(fp)
        if reader.fieldnames != ["version", "breedName", "species"]:
            raise ValueError(f"breed-species.csv 헤더가 다르다: {reader.fieldnames}")
        table: dict[str, str] = {}
        for row in reader:
            name, species = _clean(row["breedName"]), row["species"].strip()
            if species not in ("DOG", "CAT"):
                raise ValueError(f"축종 값이 이상하다: {row}")
            if name in table and table[name] != species:
                raise ValueError(f"같은 품종 이름이 두 축종에 있다: {name}")
            table[name] = species
    if not table:
        raise ValueError("품종 사전이 비어 있다")
    return table


def species_of(breed: str | None, table: dict[str, str]) -> str | None:
    name = _clean(breed or "")
    if not name:
        return None
    if name in OTHER_NAMES:
        return "OTHER"
    if name in table:
        return table[name]
    kinds = {species for known, species in table.items() if name in known}
    return kinds.pop() if len(kinds) == 1 else None
