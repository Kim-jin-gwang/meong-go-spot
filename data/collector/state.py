"""증분 수집 상태 관리 — desertionNo → updTm 맵으로 신규·변경분을 가려낸다."""

import json
import os
from pathlib import Path


def load(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, state: dict[str, str]) -> None:
    # 임시 파일에 쓴 뒤 원자적으로 교체 — 쓰기 도중 중단돼도 기존 상태가 살아남는다
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(path.name + ".tmp")
    tmp_path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp_path, path)


def diff(records: list[dict], state: dict[str, str]) -> tuple[list[dict], list[dict]]:
    """(신규 레코드, 변경 레코드)를 반환한다. 판단 기준은 updTm 변화."""
    new_records: list[dict] = []
    changed_records: list[dict] = []
    for record in records:
        key = str(record.get("desertionNo", ""))
        if not key:
            continue
        upd_tm = str(record.get("updTm") or "")
        if key not in state:
            new_records.append(record)
        elif state[key] != upd_tm:
            changed_records.append(record)
    return new_records, changed_records


def apply(records: list[dict], state: dict[str, str]) -> dict[str, str]:
    """수집 결과를 상태에 반영한 새 상태를 반환한다 (원본 불변)."""
    updated = dict(state)
    for record in records:
        key = str(record.get("desertionNo", ""))
        if key:
            updated[key] = str(record.get("updTm") or "")
    return updated
