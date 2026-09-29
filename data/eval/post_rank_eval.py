"""게시물 단위 유사도 집계(max·mean·대표 1장) 순위 비교 — 계약 미합의 항목의 회의 근거 자료 (#155).

게시물은 사진 최대 10장인데 유사도는 사진(벡터) 단위다. repr-v1 로 "게시물"을 근사해
집계 규칙별 개체 단위 Top-K 순위 품질을 같은 잣대로 잰다. 계약 문서 변경은 범위 밖.

    질의 게시물 Q(A) = 개체 A 의 사진 전부 (repr-v1 은 개체당 최대 2장 — 양성 쌍 개체는 2장)
    갤러리 게시물   = manifest 의 개체 단위 사진 묶음 (328개)
    쌍 점수 S[q, g] = cos(q, g). 단 같은 개체 안에서 q 와 같은 사진, cos ≥ DEDUPE_COS(동일 파일)는 무효
    rank(A) = 1 + |{ B ≠ A : score(B) > score(A) }|  (동점은 정답에 유리)

집계 규칙
    max      : 유효 쌍 점수의 최댓값 — 무관/저품질 사진 한 장의 우연한 최고 매치가 사칭자를 끌어올릴 수 있다
    mean     : 유효 쌍 점수의 평균 — 사진 수가 다른 개체(1장 vs 2장) 간 평균 대상 수가 달라진다
    rep1     : 질의·갤러리 각 대표 1장만 — 대표 = photo_id 사전순 첫 장(공공 API 사진 1).
               정답 개체만 질의 사진(자기 자신)을 제외한 첫 장(=사진 2) — -154 사진 단위 결과와의 연결점
    mean_max : 질의 사진마다 그 개체와의 최고 점수를 내고 평균 — -143(eval_aggregation.py)의 합의 후보, 참고용

구조적 한계 (결과 해석에 필수)
    개체당 사진이 2장이라 정답 개체의 유효 교차쌍은 (사진1↔사진2) 하나 값뿐 — **정답 점수는 규칙과 무관하게
    같다.** 규칙 간 차이는 전적으로 사칭자 측 집계에서 나온다. mean 은 사칭자 점수를 항상 max 이하로 누르므로
    이 구성에서 순위가 나빠질 수 없다 — 방향이 아니라 **격차의 크기**와 rep1 의 상대 위치를 읽는 실험이다.

no_animal 두 조건 — 검사키트류 사진이 max 집계를 오염시키는 정도가 실전 관심사
    포함(include): manifest 그대로
    제외(exclude): quality=no_animal 사진을 질의 묶음·갤러리 묶음 양쪽에서 뺀다.
                   사진이 다 빠진 개체는 후보에서 빠지고, 질의가 비거나 정답 유효쌍이 없으면 그 쌍은 dropped

결정적 실행 — 표본 추출·무작위성 없음. 469×768 이라 서버 2 에서 수 초.

실행 (서버 2 ~/eval-app):
    ~/ai/.venv/bin/python post_rank_eval.py --dataset datasets/repr-v1 \
        --vectors-dir vectors vectors-samples --out out/repr-v1-post-agg.json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

from check_dataset import load_manifest
from make_dataset import cos_band
from make_pairs import DEDUPE_COS
from rank_eval import NO_ANIMAL, fallback_pattern, group_by, load_dataset_vectors, load_pos_pairs, summarize, tag_of

METHODS = ("max", "mean", "rep1", "mean_max")
CONDITIONS = ("include", "exclude")  # no_animal 포함 / 제외


def build_groups(manifest: dict[str, dict], ids: list[str]) -> dict[str, list[int]]:
    """group_id → 갤러리 인덱스 목록 (photo_id 사전순 = ids 순서라 대표 1장 선정이 결정적이다)."""
    index = {pid: i for i, pid in enumerate(ids)}
    groups: dict[str, list[int]] = defaultdict(list)
    for pid in ids:  # ids 는 정렬돼 있다 (load_dataset_vectors)
        groups[manifest[pid]["group_id"]].append(index[pid])
    return dict(groups)


def keep_mask(manifest: dict[str, dict], ids: list[str], condition: str) -> np.ndarray:
    if condition == "include":
        return np.ones(len(ids), dtype=bool)
    return np.array([tag_of(manifest[pid], "quality") != NO_ANIMAL for pid in ids])


def entity_scores(scores: np.ndarray, query_idx: list[int], groups: dict[str, list[int]],
                  target_group: str, keep: np.ndarray, rep_query: int | None) -> dict[str, dict[str, float]]:
    """개체별 집계 점수. 반환: {method: {group_id: score}} — 유효 쌍이 없는 개체는 키가 빠진다.

    정답 개체 안에서는 질의 사진 자신과 동일 파일(cos ≥ DEDUPE_COS) 쌍을 무효로 한다
    (rank_eval 의 junk 규칙과 동일 — 남겨 두면 cos 1.0 이 순위를 왜곡한다).
    """
    q_rows = scores[query_idx]  # (|Q|, N)
    out: dict[str, dict[str, float]] = {m: {} for m in METHODS}
    for gid, photos in groups.items():
        cols = [g for g in photos if keep[g]]
        if not cols:
            continue
        block = q_rows[:, cols]  # (|Q|, |G|)
        valid = np.ones_like(block, dtype=bool)
        if gid == target_group:
            for r, q in enumerate(query_idx):
                for c, g in enumerate(cols):
                    if g == q or block[r, c] >= DEDUPE_COS:
                        valid[r, c] = False
        if not valid.any():
            continue
        flat = block[valid]
        out["max"][gid] = float(flat.max())
        out["mean"][gid] = float(flat.mean())
        row_max = np.where(valid.any(axis=1), np.where(valid, block, -np.inf).max(axis=1), np.nan)
        out["mean_max"][gid] = float(np.nanmean(row_max))
        if rep_query is not None:
            r = query_idx.index(rep_query)
            rep_cols = [c for c in range(len(cols)) if valid[r, c]]
            if rep_cols:
                out["rep1"][gid] = float(block[r, rep_cols[0]])  # 사전순 첫 유효 사진
    return out


def rank_of(per_entity: dict[str, float], target_group: str) -> int | None:
    if target_group not in per_entity:
        return None
    target = per_entity[target_group]
    return 1 + sum(1 for gid, s in per_entity.items() if gid != target_group and s > target)


def evaluate(ids: list[str], gallery: np.ndarray, manifest: dict[str, dict], pairs: list[dict],
             ks: list[int]) -> tuple[dict, list[dict]]:
    groups = build_groups(manifest, ids)
    index = {pid: i for i, pid in enumerate(ids)}
    scores = gallery @ gallery.T  # (469, 469) — L2 정규화라 내적 = 코사인
    keeps = {cond: keep_mask(manifest, ids, cond) for cond in CONDITIONS}

    rows: list[dict] = []
    for p in pairs:
        q, t = manifest[p["query_id"]], manifest[p["target_id"]]
        gid = q["group_id"]
        row = {"pair_id": p["pair_id"], "query_group": gid, "band": cos_band(float(p["cos_v2"])),
               "species": q["species"] or "샘플", "fallback": fallback_pattern(q, t),
               "q_quality": tag_of(q, "quality"), "t_quality": tag_of(t, "quality"),
               "no_animal": tag_of(q, "quality") == NO_ANIMAL or tag_of(t, "quality") == NO_ANIMAL}
        for cond in CONDITIONS:
            keep = keeps[cond]
            query_idx = [g for g in groups[gid] if keep[g]]
            rep = index[p["query_id"]] if keep[index[p["query_id"]]] else (query_idx[0] if query_idx else None)
            per = (entity_scores(scores, query_idx, groups, gid, keep, rep) if query_idx
                   else {m: {} for m in METHODS})
            for m in METHODS:
                row[f"rank_{m}" if cond == "include" else f"rank_{m}_xna"] = rank_of(per[m], gid)
        rows.append(row)

    def track(cond: str) -> dict:
        suffix = "" if cond == "include" else "_xna"
        keep = keeps[cond]
        candidates = sum(1 for photos in groups.values() if any(keep[g] for g in photos))
        out: dict = {"gallery_photos": int(keep.sum()), "candidate_entities": candidates, "methods": {}}
        for m in METHODS:
            col = f"rank_{m}{suffix}"
            main = [r for r in rows if r[col] is not None and not r["no_animal"]]
            na = [r for r in rows if r[col] is not None and r["no_animal"]]
            out["methods"][m] = {
                "overall": summarize([r[col] for r in main], ks),
                "by_band": group_by([(r["band"], r[col]) for r in main], ks),
                "by_species": group_by([(r["species"], r[col]) for r in main], ks),
                "by_query_quality": group_by([(r["q_quality"], r[col]) for r in main if r["q_quality"]], ks),
                "no_animal_pairs": summarize([r[col] for r in na], ks),
                "dropped": sum(1 for r in rows if r[col] is None),
            }
        return out

    # 방식 간 순위 괴리 상위 사례 (포함 조건, 본 지표 쌍) — 정성 관찰 소재
    spread_rows = sorted((r for r in rows if not r["no_animal"] and all(r[f"rank_{m}"] is not None for m in METHODS)),
                         key=lambda r: max(r[f"rank_{m}"] for m in ("max", "mean", "rep1"))
                         - min(r[f"rank_{m}"] for m in ("max", "mean", "rep1")), reverse=True)
    divergent = [{k: r[k] for k in ("pair_id", "band", "species", "q_quality", "t_quality",
                                    "rank_max", "rank_mean", "rank_rep1", "rank_mean_max")}
                 for r in spread_rows[:10]]

    result = {"gallery_size": len(ids), "entities": len(groups), "dim": int(gallery.shape[1]),
              "pos_pairs": len(pairs), "k": ks, "methods": list(METHODS),
              "include": track("include"), "exclude_no_animal": track("exclude"),
              "divergent_pairs": divergent}
    return result, rows


RANK_COLUMNS = ["pair_id", "query_group", "band", "species", "fallback", "q_quality", "t_quality", "no_animal",
                "rank_max", "rank_mean", "rank_rep1", "rank_mean_max",
                "rank_max_xna", "rank_mean_xna", "rank_rep1_xna", "rank_mean_max_xna"]


def write_ranks(rows: list[dict], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="\n") as fp:
        fp.write("\t".join(RANK_COLUMNS) + "\n")
        for r in rows:
            fp.write("\t".join("" if r[c] is None else str(int(r[c]) if c == "no_animal" else r[c])
                               for c in RANK_COLUMNS) + "\n")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="repr-v1 게시물(개체) 단위 집계 규칙 순위 비교 (결정적 — 시드 불필요)")
    ap.add_argument("--dataset", required=True, help="manifest.jsonl·pairs.tsv 가 있는 디렉터리 (datasets/repr-v1)")
    ap.add_argument("--vectors-dir", nargs="+", help="풀 벡터 TSV 디렉터리(여러 개 가능, 앞이 우선)")
    ap.add_argument("--snapshot", help="index_tools.py snapshot 디렉터리 (TSV 대신)")
    ap.add_argument("--k", type=int, nargs="+", default=[1, 5, 10])
    ap.add_argument("--out", required=True, help="결과 요약 JSON")
    ap.add_argument("--out-ranks", help="쌍별 순위 TSV (생략하면 --out 옆에 .tsv 로)")
    args = ap.parse_args()
    if bool(args.vectors_dir) == bool(args.snapshot):
        ap.error("--vectors-dir 과 --snapshot 중 정확히 하나를 줘야 합니다")

    dataset_dir = Path(args.dataset)
    manifest = load_manifest(dataset_dir)
    pairs = load_pos_pairs(dataset_dir)
    t0 = time.time()
    ids, gallery = load_dataset_vectors(manifest, [Path(d) for d in args.vectors_dir] if args.vectors_dir else None,
                                        Path(args.snapshot) if args.snapshot else None)
    print(f"갤러리 {len(ids)} × {gallery.shape[1]} 추출 {time.time() - t0:.1f}초 | 양성 쌍 {len(pairs)}")
    result, rows = evaluate(ids, gallery, manifest, pairs, args.k)
    result["dataset"] = str(dataset_dir)
    result["vectors_source"] = str(args.snapshot) if args.snapshot else [str(d) for d in args.vectors_dir]

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    write_ranks(rows, Path(args.out_ranks) if args.out_ranks else out.with_suffix(".tsv"))

    for cond, label in (("include", "no_animal 포함"), ("exclude_no_animal", "no_animal 제외")):
        tr = result[cond]
        print(f"[{label}] 갤러리 {tr['gallery_photos']}장 · 후보 개체 {tr['candidate_entities']}")
        for m in METHODS:
            o = tr["methods"][m]["overall"]
            if o["n"]:
                print(f"  {m:8s} n={o['n']} " + " ".join(f"R@{k}={o[f'recall@{k}']:.3f}" for k in args.k)
                      + f" mRR={o['mrr']:.3f} 중앙순위={o['median_rank']}")
            else:
                print(f"  {m:8s} 평가할 쌍이 없습니다 (dropped {tr['methods'][m]['dropped']})")
    print(f"→ {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
