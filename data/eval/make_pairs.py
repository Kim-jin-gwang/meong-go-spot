"""정답쌍 생성 — 같은 개체의 사진 1·2를 (질의, 정답) 쌍으로 묶는다.

공공 API는 2024-06부터 개체당 사진 2장(popfile1·2)을 준다. 실종 사진↔입소 사진 라벨은 세상에 없으므로
"같은 개체의 다른 사진"이 유일한 자연 정답이다. 단 두 사진이 같은 파일인 레코드가 있어(코사인 1.000)
그대로 쓰면 recall이 부푼다 — 벡터가 거의 같은 쌍은 duplicate 로 표시하고 평가에서 기본 제외한다.

    입력: --vectors-dir  vectors-*.tsv + detections-*.jsonl (bulk_embed 출력, 하위 디렉터리 포함)
          --records      records.jsonl 들 (backfill 출력) — 축종·품종·보호소 메타데이터
    출력: --out pairs.tsv (탭 구분, 헤더 포함)
          desertion_no  query_id  target_id  month  up_kind_cd  kind_cd  care_reg_no
          fallback_query  fallback_target  cos_pair  duplicate

모델이 바뀌어도 이 스크립트는 그대로다 — 벡터 디렉터리만 바꿔 다시 만들면 같은 개체 집합으로 비교된다(§4 ⑤).
"""

from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path

import numpy as np

DEDUPE_COS = 0.999  # 같은 파일이면 1.000, 재인코딩 차이는 0.999 이상 — 그 밑은 진짜 다른 사진으로 본다


def iter_vectors(vectors_dir: Path):
    """(사진ID, 월, 벡터) 순회 — 월은 상위 디렉터리 yyyymm=YYYYMM 또는 파일명에서 얻는다."""
    for path in sorted(vectors_dir.rglob("vectors-*.tsv")):
        parts = path.stem.split("-")  # vectors-images-202407-0000 → 월은 세 번째 조각
        month = parts[2] if len(parts) >= 4 and parts[2].isdigit() else ""
        with path.open(encoding="utf-8") as fp:
            for line in fp:
                pid, _, csv = line.rstrip("\n").partition("\t")
                if not csv:
                    continue
                yield pid, month, np.array(csv.split(","), dtype=np.float32)


def load_detections(vectors_dir: Path) -> dict[str, bool]:
    """사진ID → fallback 여부 (error 레코드는 벡터가 없으므로 제외)."""
    flags: dict[str, bool] = {}
    for path in vectors_dir.rglob("detections-*.jsonl"):
        with path.open(encoding="utf-8") as fp:
            for line in fp:
                d = json.loads(line)
                if "error" in d:
                    continue
                flags[d["photo_id"]] = bool(d.get("fallback"))
    return flags


def load_records(patterns: list[str]) -> dict[str, dict]:
    """desertionNo → {up_kind_cd, kind_cd, care_reg_no}. 같은 개체가 여러 월에 나오면 마지막 것."""
    meta: dict[str, dict] = {}
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            with open(path, encoding="utf-8") as fp:
                for line in fp:
                    r = json.loads(line)
                    no = str(r.get("desertionNo") or "")
                    if no:
                        meta[no] = {"up_kind_cd": r.get("upKindCd") or "", "kind_cd": r.get("kindCd") or "",
                                    "care_reg_no": r.get("careRegNo") or ""}
    return meta


def split_id(photo_id: str) -> tuple[str, str] | None:
    no, _, idx = photo_id.rpartition("_")
    return (no, idx) if no and idx in ("1", "2") else None


def build_pairs(vectors_dir: Path, records: dict[str, dict], dedupe_cos: float = DEDUPE_COS) -> tuple[list[dict], dict]:
    fallback = load_detections(vectors_dir)
    pending: dict[str, tuple[str, str, np.ndarray]] = {}  # desertionNo → (photo_id, month, vector) — 짝을 기다리는 쪽
    pairs: list[dict] = []
    stats = {"photos": 0, "animals_with_two": 0, "duplicates": 0, "pairs": 0,
             "fallback_none": 0, "fallback_one": 0, "fallback_both": 0}
    for pid, month, vec in iter_vectors(vectors_dir):
        stats["photos"] += 1
        parsed = split_id(pid)
        if parsed is None:
            continue
        no, _ = parsed
        other = pending.get(no)
        if other is None:
            pending[no] = (pid, month, vec)
            continue
        if other[0] == pid:  # 같은 사진 ID 가 다시 왔다(디렉터리 중복 등) — 짝이 아니므로 무시
            stats["repeated_ids"] = stats.get("repeated_ids", 0) + 1
            continue
        pending.pop(no)
        stats["animals_with_two"] += 1
        a_id, a_month, a_vec = other
        first, second = (a_id, pid) if a_id.endswith("_1") else (pid, a_id)  # 질의는 항상 사진 1
        q_vec, t_vec = (a_vec, vec) if first == a_id else (vec, a_vec)
        cos = float(np.dot(q_vec, t_vec) / (np.linalg.norm(q_vec) * np.linalg.norm(t_vec) + 1e-12))
        duplicate = cos >= dedupe_cos
        fq, ft = fallback.get(first, False), fallback.get(second, False)
        m = records.get(no, {})
        pairs.append({"desertion_no": no, "query_id": first, "target_id": second, "month": a_month or month,
                      "up_kind_cd": m.get("up_kind_cd", ""), "kind_cd": m.get("kind_cd", ""), "care_reg_no": m.get("care_reg_no", ""),
                      "fallback_query": int(fq), "fallback_target": int(ft), "cos_pair": round(cos, 4), "duplicate": int(duplicate)})
        if duplicate:
            stats["duplicates"] += 1
        else:
            stats["pairs"] += 1
            stats["fallback_none" if not (fq or ft) else "fallback_both" if (fq and ft) else "fallback_one"] += 1
    return pairs, stats


COLUMNS = ["desertion_no", "query_id", "target_id", "month", "up_kind_cd", "kind_cd", "care_reg_no",
           "fallback_query", "fallback_target", "cos_pair", "duplicate"]


def write_pairs(pairs: list[dict], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write("\t".join(COLUMNS) + "\n")
        for p in pairs:
            fp.write("\t".join(str(p[c]) for c in COLUMNS) + "\n")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="같은 개체 사진 1·2 정답쌍 생성 (중복 사진 표시)")
    ap.add_argument("--vectors-dir", required=True)
    ap.add_argument("--records", nargs="+", required=True, help="records.jsonl 글롭 (여러 개 가능)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--dedupe-cos", type=float, default=DEDUPE_COS)
    args = ap.parse_args()
    records = load_records(args.records)
    pairs, stats = build_pairs(Path(args.vectors_dir), records, args.dedupe_cos)
    write_pairs(pairs, Path(args.out))
    missing_meta = sum(1 for p in pairs if not p["up_kind_cd"])
    print(f"사진 {stats['photos']:,} → 두 장 있는 개체 {stats['animals_with_two']:,} → 중복(동일 사진) {stats['duplicates']:,} 제외 → "
          f"정답쌍 {stats['pairs']:,} (폴백 없음 {stats['fallback_none']:,} / 한쪽 {stats['fallback_one']:,} / 양쪽 {stats['fallback_both']:,}) | "
          f"메타 없음 {missing_meta:,} | 저장 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
