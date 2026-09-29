"""repr-v1 순위(랭킹) 평가 — 표준 데이터셋의 양성 쿼리로 갤러리 469장을 검색해 Top-K 순위 품질을 잰다 (#154).

임계값 곡선(-115)이 "점수 축"이라면 이 하네스는 "순위 축"이다 — 같은 repr-v1 로 서로 겹치지 않는다.

    갤러리 G = manifest 의 사진 469장 (자기 자신 제외)
    질의 q  = pairs.tsv 양성 쌍의 query_id (역할 pos_query, 120장)
    정답    = 같은 group_id 의 다른 사진. 단 cos(q,·) ≥ 0.999(동일 파일)는 정답도 오답도 아니므로
              후보에서 통째로 제외한다 (datasets/README.md 규칙 — 남겨 두면 진짜 정답을 부당하게 밀어낸다)
    rank    = 1 + |{후보 c ∉ 정답 : cos(q,c) > max_{t∈정답} cos(q,t)}|  (동점은 정답에 유리)

보고 항목
- 본 지표: recall@1/5/10 · mRR — quality=no_animal 이 낀 쌍은 제외 (별도 표로 보고)
- 분해: 난이도 밴드(양성 cos_v2 밴드, make_dataset.COS_BANDS) · 축종 · 시각 태그(질의 쪽/정답 쪽) · 폴백 패턴
- 폴백 별도 트랙: 갤러리에서 폴백 73장을 뺀 조건과 포함 조건의 지표 차이

결정적 실행 — 표본 추출·무작위성이 없어 같은 입력이면 같은 출력이다 (시드 불필요).
벡터는 L2 정규화돼 있어 내적 = 코사인. 469×768 이라 서버 2 에서 수 초면 끝난다.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

from check_dataset import load_manifest
from eval_recall import load_snapshot
from make_dataset import cos_band
from make_pairs import DEDUPE_COS, iter_vectors

NO_ANIMAL = "no_animal"


def load_pos_pairs(dataset_dir: Path) -> list[dict]:
    with (dataset_dir / "pairs.tsv").open(encoding="utf-8") as fp:
        pairs = [r for r in csv.DictReader(fp, delimiter="\t") if r["label"] == "1"]
    if not pairs:
        raise SystemExit(f"{dataset_dir}/pairs.tsv 에 양성 쌍이 없습니다")
    for r in pairs:  # 밴드 분해가 cos_v2 에 걸려 있다 — 결함 데이터는 traceback 이 아니라 어느 쌍인지로 알린다
        try:
            r["cos_v2"] = float(r["cos_v2"])
        except (KeyError, TypeError, ValueError):
            raise SystemExit(f"{r.get('pair_id')}: cos_v2 가 없거나 숫자가 아닙니다 ({r.get('cos_v2')!r}) — pairs.tsv 확인") from None
    return pairs


def load_dataset_vectors(manifest: dict[str, dict], vectors_dirs: list[Path] | None,
                         snapshot_dir: Path | None) -> tuple[list[str], np.ndarray]:
    """풀 벡터에서 manifest 의 photo_id 만 추출한다 — 재임베딩하지 않는다 (이슈 -154 입력 규칙).

    디렉터리를 여러 개 주면 순서대로 읽고 먼저 나온 벡터가 이긴다 — 백필 벡터에 없는
    팀 샘플 3장(cats·dog·none)은 별도 디렉터리로 보충한다.
    하나라도 빠지면 실패한다: 조용히 468장으로 평가하면 다른 실행과 비교가 어긋난다.
    """
    if snapshot_dir is None and not vectors_dirs:  # CLI 는 막지만 함수 직접 호출은 TypeError 로 죽는다
        raise ValueError("vectors_dirs 또는 snapshot_dir 중 하나는 줘야 합니다")
    wanted = set(manifest)
    found: dict[str, np.ndarray] = {}
    if snapshot_dir is not None:
        ids, matrix = load_snapshot(snapshot_dir)  # 정규화 검사 포함
        for i, pid in enumerate(ids):
            if pid in wanted:
                found[pid] = np.asarray(matrix[i], dtype=np.float32)
    else:
        for vectors_dir in vectors_dirs:
            for pid, _, vec in iter_vectors(vectors_dir):
                if pid in wanted and pid not in found:
                    found[pid] = vec
    missing = sorted(wanted - set(found))
    if missing:
        raise SystemExit(f"벡터 없음 {len(missing)}장 (예: {missing[:5]}) — manifest 와 벡터 소스가 맞는지 확인")
    order = sorted(wanted)  # 입력 파일 순서에 의존하지 않는 결정적 갤러리 순서
    gallery = np.vstack([found[pid] for pid in order])
    norms = np.linalg.norm(gallery, axis=1, keepdims=True)
    return order, gallery / np.maximum(norms, 1e-12)


def rank_pairs(ids: list[str], gallery: np.ndarray, manifest: dict[str, dict], pairs: list[dict],
               gallery_mask: np.ndarray | None = None) -> list[dict]:
    """양성 쌍별 정답 순위. gallery_mask(bool, ids 순서)를 주면 False 인 사진은 후보에서 뺀다.

    반환: 쌍마다 {pair_id, rank, dropped} — 정답이 전부 중복(동일 파일)이거나 마스크 밖이면 dropped.
    """
    index = {pid: i for i, pid in enumerate(ids)}
    group_members: dict[str, list[int]] = defaultdict(list)
    for pid, e in manifest.items():
        group_members[e["group_id"]].append(index[pid])
    scores = gallery @ gallery.T  # (469, 469) — 작아서 한 번에 든다
    out = []
    for p in pairs:
        qi = index[p["query_id"]]
        row = scores[qi]
        allowed = np.ones(len(ids), dtype=bool) if gallery_mask is None else gallery_mask.copy()
        allowed[qi] = False
        members = [i for i in group_members[manifest[p["query_id"]]["group_id"]] if i != qi]
        junk = [i for i in members if row[i] >= DEDUPE_COS]  # 동일 파일 — 정답도 오답도 아님
        allowed[junk] = False
        correct = [i for i in members if i not in junk and allowed[i]]
        if not correct:
            out.append({"pair_id": p["pair_id"], "rank": None, "dropped": True})
            continue
        best = max(row[i] for i in correct)
        others = allowed.copy()
        others[correct] = False  # 다른 정답이 위에 있어도 오답이 아니므로 순위를 밀지 않는다
        out.append({"pair_id": p["pair_id"], "rank": int((row[others] > best).sum()) + 1, "dropped": False})
    return out


def summarize(ranks: list[int], ks: list[int]) -> dict:
    if not ranks:  # 예: 정답이 전부 폴백이라 제외 조건에서 평가할 쌍이 없음 — nan→int 변환으로 죽지 않는다
        return {**{f"recall@{k}": None for k in ks}, "mrr": None, "n": 0, "median_rank": None}
    arr = np.array(ranks, dtype=np.int64)
    return {**{f"recall@{k}": round(float(np.mean(arr <= k)), 4) for k in ks},
            "mrr": round(float(np.mean(1.0 / arr)), 4), "n": int(len(arr)), "median_rank": int(np.median(arr))}


def fallback_pattern(q: dict, t: dict) -> str:
    return ("both_detected" if not q["fallback"] and not t["fallback"]
            else "both_fallback" if q["fallback"] and t["fallback"] else "one_fallback")


def tag_of(entry: dict, axis: str) -> str | None:
    return (entry.get("tags") or {}).get(axis)


def group_by(rows: list[tuple[str, int]], ks: list[int]) -> dict:
    buckets: dict[str, list[int]] = defaultdict(list)
    for key, rank in rows:
        buckets[key].append(rank)
    return {key: summarize(buckets[key], ks) for key in sorted(buckets)}


def evaluate(ids: list[str], gallery: np.ndarray, manifest: dict[str, dict], pairs: list[dict],
             ks: list[int]) -> tuple[dict, list[dict]]:
    """결과 요약 dict 와 쌍별 순위 행(기계용 TSV 소재)을 반환한다."""
    ranked = {r["pair_id"]: r for r in rank_pairs(ids, gallery, manifest, pairs)}
    fb_mask = np.array([not manifest[pid]["fallback"] for pid in ids])
    ranked_nofb = {r["pair_id"]: r for r in rank_pairs(ids, gallery, manifest, pairs, gallery_mask=fb_mask)}

    rows: list[dict] = []
    for p in pairs:
        q, t = manifest[p["query_id"]], manifest[p["target_id"]]
        r, r_nofb = ranked[p["pair_id"]], ranked_nofb[p["pair_id"]]
        rows.append({"pair_id": p["pair_id"], "query_id": p["query_id"], "target_id": p["target_id"],
                     "band": cos_band(float(p["cos_v2"])), "species": q["species"] or "샘플",
                     "fallback": fallback_pattern(q, t),
                     "q_angle": tag_of(q, "angle"), "q_background": tag_of(q, "background"), "q_quality": tag_of(q, "quality"),
                     "t_angle": tag_of(t, "angle"), "t_background": tag_of(t, "background"), "t_quality": tag_of(t, "quality"),
                     "no_animal": tag_of(q, "quality") == NO_ANIMAL or tag_of(t, "quality") == NO_ANIMAL,
                     "rank": r["rank"], "rank_nofb": r_nofb["rank"]})

    dropped = [r for r in rows if r["rank"] is None]
    main = [r for r in rows if r["rank"] is not None and not r["no_animal"]]
    no_animal = [r for r in rows if r["rank"] is not None and r["no_animal"]]

    def axis(side: str, name: str) -> dict:
        return group_by([(r[f"{side}_{name}"], r["rank"]) for r in main if r[f"{side}_{name}"]], ks)

    result = {
        "gallery_size": len(ids), "dim": int(gallery.shape[1]), "k": ks,
        "pos_pairs": len(pairs), "dropped_all_duplicates": len(dropped),
        "no_animal_excluded": len(no_animal), "pairs_evaluated": len(main),
        "overall": summarize([r["rank"] for r in main], ks),
        "by_band": group_by([(r["band"], r["rank"]) for r in main], ks),
        "by_species": group_by([(r["species"], r["rank"]) for r in main], ks),
        "by_fallback_pair": group_by([(r["fallback"], r["rank"]) for r in main], ks),
        "by_query_tag": {name: axis("q", name) for name in ("angle", "background", "quality")},
        "by_target_tag": {name: axis("t", name) for name in ("angle", "background", "quality")},
        "no_animal_pairs": [{k: r[k] for k in ("pair_id", "query_id", "target_id", "q_quality", "t_quality", "rank")}
                            for r in no_animal],
    }

    nofb_main = [r for r in main if r["rank_nofb"] is not None]
    result["fallback_gallery_excluded"] = {
        "gallery_size": int(fb_mask.sum()),
        "dropped_fallback_target": len(main) - len(nofb_main),
        "overall": summarize([r["rank_nofb"] for r in nofb_main], ks),
        "by_fallback_pair": group_by([(r["fallback"], r["rank_nofb"]) for r in nofb_main], ks),
    }
    return result, rows


RANK_COLUMNS = ["pair_id", "query_id", "target_id", "band", "species", "fallback",
                "q_angle", "q_background", "q_quality", "t_angle", "t_background", "t_quality",
                "no_animal", "rank", "rank_nofb"]


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
    ap = argparse.ArgumentParser(description="repr-v1 양성 쿼리 Top-K 순위 평가 (결정적 — 시드 불필요)")
    ap.add_argument("--dataset", required=True, help="manifest.jsonl·pairs.tsv 가 있는 디렉터리 (datasets/repr-v1)")
    ap.add_argument("--vectors-dir", nargs="+", help="풀 벡터 TSV 디렉터리(여러 개 가능, 앞이 우선) — manifest 의 photo_id 만 추출해 쓴다")
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

    o = result["overall"]
    if o["n"]:
        print(f"본 지표 n={o['n']} | " + " ".join(f"R@{k}={o[f'recall@{k}']:.3f}" for k in args.k)
              + f" mRR={o['mrr']:.3f} 중앙순위={o['median_rank']} (no_animal {result['no_animal_excluded']}쌍 제외)")
    else:
        print(f"본 지표로 평가할 쌍이 없습니다 (no_animal {result['no_animal_excluded']} · 전부 중복 {result['dropped_all_duplicates']})")
    for name, grp in (("밴드", result["by_band"]), ("축종", result["by_species"]), ("폴백", result["by_fallback_pair"])):
        for g, s in grp.items():
            print(f"  {name} {g}: n={s['n']} R@1={s['recall@1']:.3f} R@{args.k[-1]}={s[f'recall@{args.k[-1]}']:.3f} mRR={s['mrr']:.3f}")
    fb = result["fallback_gallery_excluded"]
    if fb["overall"]["n"]:
        print(f"  폴백 제외 갤러리({fb['gallery_size']}장, 정답 유실 {fb['dropped_fallback_target']}쌍): "
              + " ".join(f"R@{k}={fb['overall'][f'recall@{k}']:.3f}" for k in args.k) + f" mRR={fb['overall']['mrr']:.3f}")
    else:
        print(f"  폴백 제외 갤러리({fb['gallery_size']}장): 평가할 쌍이 없습니다 (정답 유실 {fb['dropped_fallback_target']}쌍)")
    for na in result["no_animal_pairs"]:
        print(f"  no_animal {na['pair_id']}: rank={na['rank']} (q={na['q_quality']}, t={na['t_quality']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
