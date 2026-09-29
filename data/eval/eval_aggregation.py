"""동물 점수 집계 규칙 비교와 점수 임계값 곡선 — 계약 §후보 출력 정책 0-2 의 '잠정' 을 확정하기 위한 실험.

질의 사진이 여러 장일 때 후보 **동물** 하나의 점수를 어떻게 만들지(집계 규칙)와, 그 점수가 얼마 이상이면
"유사하다" 고 할지(임계값)를 같은 정답쌍 집합에서 잰다.

    질의 Q = {정답쌍의 사진 1} ∪ {무관 사진 m 장}      (m = 0, 2, 9 → 1·3·10 장)
    갤러리 = 스냅샷 전체에서 질의 사진들과 무관 사진의 개체를 뺀 것
    동물 a 의 사진 집합 P(a) (공공 데이터는 개체당 최대 2장), 쌍 코사인 S[i, j] = cos(q_i, g_j)
    rank(정답 개체) = 1 + |{ a : score(a) > score(정답 개체) }|

집계 규칙
    max        : max_{i, j∈P(a)} S[i,j]                      — 현행(잠정). 무관 사진 한 장의 최고 매치가 상위를 차지할 수 있다
    top2_mean  : 쌍 점수 상위 2개 평균 (쌍이 하나면 그 값)
    mean_max   : mean_i max_{j∈P(a)} S[i,j]                  — 질의 사진마다 그 동물과의 최고 점수를 내고 평균 (합의)
    fb_max     : 탐지 성공 질의 사진이 하나라도 있으면 폴백 질의 사진을 뺀 뒤 max
    fb_mean_max: 같은 폴백 제외 뒤 mean_max
    *_gw       : 위 규칙에서 갤러리 사진이 폴백이면 그 쌍 점수에 가중 w 를 곱한다 (--gallery-fallback-weight)

무관 사진은 갤러리에서 무작위로 뽑되 **그 사진과 그 개체를 갤러리에서 뺀다** — 사용자가 섞어 넣은 다른 사진의
주인이 보호소에 있을 리 없고, 있으면 자기 자신(코사인 1.0)이 정답을 밀어내는 비현실적 결과가 된다.
남는 실패 모드는 현실적인 것 하나다: 무관 사진이 어떤 남의 개와 우연히 많이 닮는 것.

임계값 곡선 (규칙별, m 별)
    positives  : 정답 개체의 점수 분포 → recall(τ) = P(score_target ≥ τ)
    impostors  : 정답 개체를 뺀 최고 점수 → false_alarm(τ) = P(best_other ≥ τ)
                 — 실종 동물이 보호소에 **없을 때** 엉뚱한 후보를 보여줄 확률의 근사
    shown(τ)   : τ 이상인 개체 수의 평균 (K=20 상한 전)

후보 풀 (--pool N, --records)
    제품은 벡터 비교 전에 보호중·지역·축종·실종일로 후보를 수백 마리로 좁힌다(계약 0). 전국 25만 개체를 갤러리로 쓰면
    "최고 사칭자" 는 25만 개 중 최댓값이라 정답과 겹치고 임계값을 못 정한다. --pool N 은 질의와 **같은 축종**의
    개체 N 마리를 무작위로 뽑아 정답 개체와 함께 후보로 삼는다 — 제품 필터 뒤의 후보 크기를 흉내 낸다.
    --pool 0 은 전국 갤러리(위 규칙 비교용).

한계: 공공 데이터는 개체당 사진이 2장이라 "같은 개체 사진 3~10장" 질의는 만들 수 없다. 이 실험은 무관 사진
혼입에 대한 강건성만 잰다. 같은 개체 사진만 여럿이면 어느 규칙이든 같은 동물을 가리키므로 차이는 작다.

실행 (서버 2, ~/ai/.venv):
    python eval_aggregation.py --snapshot snapshot-20260913 --pairs out/pairs-v2.tsv --sample 5000 \
        --mix 0 2 9 --gallery-fallback-weight 0.8 --out out/aggregation.json
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_recall import load_fallback, load_snapshot, read_pairs  # noqa: E402
from make_pairs import load_records  # noqa: E402

RULES = ("max", "top2_mean", "mean_max", "fb_max", "fb_mean_max")
GALLERY_WEIGHT_RULES = ("max", "fb_max")
THRESHOLDS = [round(x, 2) for x in np.arange(0.30, 0.951, 0.05)]


def animal_of(photo_id: str) -> str:
    return photo_id.rpartition("_")[0]


def build_slots(ids: list[str]) -> tuple[list[str], np.ndarray, np.ndarray]:
    """사진 → 개체 묶음. 반환: 개체 id 목록, 사진별 개체 인덱스 (N,), 개체별 사진 인덱스 슬롯 (A, K) (-1 패딩)."""
    animals: dict[str, list[int]] = {}
    for i, pid in enumerate(ids):
        animals.setdefault(animal_of(pid), []).append(i)
    animal_ids = list(animals)
    width = max(len(v) for v in animals.values())
    slots = np.full((len(animal_ids), width), -1, dtype=np.int64)
    photo_animal = np.empty(len(ids), dtype=np.int64)
    for a, (aid, photos) in enumerate(animals.items()):
        slots[a, :len(photos)] = photos
        photo_animal[photos] = a
    return animal_ids, photo_animal, slots


def score_animals(pair_scores: np.ndarray, query_fallback: np.ndarray, slot_fallback: np.ndarray,
                  valid: np.ndarray, rule: str, gallery_weight: float) -> np.ndarray:
    """pair_scores (P, A, K) → 개체 점수 (A,). valid (A, K) 는 슬롯이 실제 사진인지.

    폴백 규칙(fb_*)은 탐지 성공 질의 사진이 하나라도 있을 때만 폴백 질의 사진을 뺀다 — 전부 폴백이면 그대로 쓴다.
    갤러리 폴백 가중은 쌍 점수에 곱한다 (w=1 이면 무효).
    """
    s = pair_scores
    if gallery_weight != 1.0:
        s = np.where(slot_fallback[None, :, :], s * gallery_weight, s)
    s = np.where(valid[None, :, :], s, -np.inf)
    return aggregate(s, query_keep(query_fallback, rule), rule[3:] if rule.startswith("fb_") else rule)


def query_keep(query_fallback: np.ndarray, rule: str) -> np.ndarray:
    keep = np.ones(len(query_fallback), dtype=bool)
    if rule.startswith("fb_") and (~query_fallback).any():
        keep = ~query_fallback
    return keep


def aggregate(masked: np.ndarray, keep: np.ndarray, base: str) -> np.ndarray:
    """masked (P, A, K) 는 패딩·가중이 이미 적용된 쌍 점수. keep 은 쓸 질의 사진."""
    s = masked[keep]  # (P', A, K)
    if base == "max":
        return s.max(axis=(0, 2))
    if base == "mean_max":
        return s.max(axis=2).mean(axis=0)
    if base == "top2_mean":
        flat = s.transpose(1, 0, 2).reshape(s.shape[1], -1)  # (A, P'·K)
        if flat.shape[1] < 2:
            return flat[:, 0]
        top = np.sort(-np.partition(-flat, 1, axis=1)[:, :2], axis=1)[:, ::-1]  # 내림차순 상위 2개
        return np.where(np.isfinite(top[:, 1]), top.mean(axis=1), top[:, 0])
    raise ValueError(base)


def evaluate(ids: list[str], gallery: np.ndarray, fallback: np.ndarray, pairs: list[dict], mixes: list[int],
             sample: int, seed: int, gallery_weight: float, ks: list[int],
             pool: int = 0, up_kind_of: dict[str, str] | None = None) -> dict:
    """pool > 0 이면 질의와 같은 축종 개체 pool 마리 + 정답 개체만 후보로 쓴다 (up_kind_of: desertionNo → upKindCd)."""
    index = {pid: i for i, pid in enumerate(ids)}
    animal_ids, photo_animal, slots = build_slots(ids)
    valid = slots >= 0
    safe_slots = np.where(valid, slots, 0)
    slot_fallback = np.where(valid, fallback[safe_slots], False)
    usable = [p for p in pairs if p["query_id"] in index and p["target_id"] in index]
    rng = random.Random(seed)
    if sample and len(usable) > sample:
        usable = rng.sample(usable, sample)
    n_photos = len(ids)
    by_kind: dict[str, np.ndarray] = {}
    if pool:
        if not up_kind_of:
            raise SystemExit("--pool 에는 --records 가 필요하다 (같은 축종 후보를 뽑는다)")
        kinds = [up_kind_of.get(aid, "") for aid in animal_ids]
        for kind in set(kinds) - {""}:
            by_kind[kind] = np.array([a for a, k in enumerate(kinds) if k == kind])
        usable = [p for p in usable if up_kind_of.get(animal_of(p["query_id"]), "") in by_kind]
    # 갤러리 폴백 가중은 max 계열에만 붙인다 — 평균 계열은 혼입 시나리오에서 이미 무너져 가중을 재는 의미가 없고, 규칙 수가 실행 시간에 비례한다.
    rules = list(RULES) + ([f"{r}_gw" for r in GALLERY_WEIGHT_RULES] if gallery_weight != 1.0 else [])
    result = {"pairs_evaluated": len(usable), "gallery_photos": n_photos, "gallery_animals": len(animal_ids),
              "fallback_photos": int(fallback.sum()), "gallery_fallback_weight": gallery_weight, "k": ks,
              "candidate_pool": pool or None, "thresholds": THRESHOLDS, "scenarios": {}}
    for m in mixes:
        t0 = time.time()
        ranks = {r: np.empty(len(usable), dtype=np.int64) for r in rules}
        pos = {r: np.empty(len(usable), dtype=np.float32) for r in rules}
        imp = {r: np.empty(len(usable), dtype=np.float32) for r in rules}
        shown = {r: np.zeros((len(usable), len(THRESHOLDS)), dtype=np.int32) for r in rules}
        query_fallback_count = 0
        for n, p in enumerate(usable):
            q = index[p["query_id"]]
            t_animal = photo_animal[index[p["target_id"]]]
            mix: list[int] = []
            while len(mix) < m:
                c = rng.randrange(n_photos)
                if photo_animal[c] != t_animal and c not in mix:
                    mix.append(c)
            q_idx = np.array([q] + mix)
            q_fb = fallback[q_idx]
            query_fallback_count += int(q_fb.sum())
            if pool:
                # 같은 축종 개체 pool 마리(정답·무관 사진 개체 제외) + 정답 개체. 점수는 그 사진들만 계산한다.
                kind_animals = by_kind[up_kind_of[animal_of(p["query_id"])]]
                banned = set(photo_animal[mix].tolist()) | {int(t_animal)}
                # 같은 축종 개체가 pool 보다 적으면 있는 만큼만 — 상한 없이 뽑으면 무한 루프다 (AI 리뷰 !173).
                # banned 에는 다른 축종 개체(무관 사진)도 섞일 수 있어 상한이 실제 가용 수보다 작게 잡힐 수 있지만, 그만큼만 덜 뽑는다.
                need = min(pool, len(kind_animals) - len(banned))
                cand = []
                while len(cand) < need:
                    a = int(kind_animals[rng.randrange(len(kind_animals))])
                    if a not in banned:
                        banned.add(a)
                        cand.append(a)
                cand_animals = np.array(cand + [int(t_animal)])
                c_slots, c_valid, c_fb = safe_slots[cand_animals], valid[cand_animals], slot_fallback[cand_animals]
                scores = gallery[q_idx] @ gallery[c_slots.reshape(-1)].T  # (P, (N+1)·K)
                scores = scores.reshape(len(q_idx), len(cand_animals), -1)
                scores[:, -1][:, c_slots[-1] == q] = -np.inf  # 자기 자신 제외
                masked = np.where(c_valid[None, :, :], scores, -np.inf)
                masked_gw = np.where(c_fb[None, :, :], masked * gallery_weight, masked) if gallery_weight != 1.0 else None
                excluded_animals = np.array([], dtype=np.int64)
                t_local = len(cand_animals) - 1
            else:
                scores = gallery[q_idx] @ gallery.T  # (P, N)
                scores[:, q] = -np.inf  # 자기 자신 제외
                excluded_animals = photo_animal[mix]
                masked = np.where(valid[None, :, :], scores[:, safe_slots], -np.inf)  # (P, A, K) — 패딩 제거 1회
                masked_gw = np.where(slot_fallback[None, :, :], masked * gallery_weight, masked) if gallery_weight != 1.0 else None
                t_local = t_animal
            for r in rules:
                base_rule = r[:-3] if r.endswith("_gw") else r
                src = masked_gw if r.endswith("_gw") else masked
                a_scores = aggregate(src, query_keep(q_fb, base_rule), base_rule[3:] if base_rule.startswith("fb_") else base_rule)
                a_scores[excluded_animals] = -np.inf
                target = a_scores[t_local]
                a_scores[t_local] = -np.inf
                ranks[r][n] = 1 + int((a_scores > target).sum())
                pos[r][n] = target
                imp[r][n] = a_scores.max()
                a_scores[t_local] = target
                finite = np.sort(a_scores[np.isfinite(a_scores)])
                shown[r][n] = len(finite) - np.searchsorted(finite, THRESHOLDS, side="left")
        scen = {"query_photos": 1 + m, "query_fallback_photos": query_fallback_count, "seconds": round(time.time() - t0, 1),
                "rules": {}}
        for r in rules:
            rk = ranks[r]
            curve = []
            for i, tau in enumerate(THRESHOLDS):
                curve.append({"tau": tau, "recall": round(float(np.mean(pos[r] >= tau)), 4),
                              "false_alarm": round(float(np.mean(imp[r] >= tau)), 4),
                              "shown_mean": round(float(shown[r][:, i].mean()), 2),
                              "shown_le_20": round(float(np.mean(shown[r][:, i] <= 20)), 4)})
            scen["rules"][r] = {**{f"recall@{k}": round(float(np.mean(rk <= k)), 4) for k in ks},
                                "mrr": round(float(np.mean(1.0 / rk)), 4), "median_rank": int(np.median(rk)),
                                "target_score": {"median": round(float(np.median(pos[r])), 4),
                                                 "p10": round(float(np.percentile(pos[r], 10)), 4),
                                                 "p90": round(float(np.percentile(pos[r], 90)), 4)},
                                "best_impostor": {"median": round(float(np.median(imp[r])), 4),
                                                  "p90": round(float(np.percentile(imp[r], 90)), 4),
                                                  "p99": round(float(np.percentile(imp[r], 99)), 4)},
                                "threshold_curve": curve}
        result["scenarios"][str(1 + m)] = scen
        print(f"질의 {1 + m}장: {len(usable)}쌍 {scen['seconds']}초 — " +
              " · ".join(f"{r} R@1 {scen['rules'][r]['recall@1']:.3f}" for r in rules), flush=True)
    return result


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--snapshot", required=True, help="fallback.npy 가 있는 스냅샷 디렉터리")
    ap.add_argument("--pairs", required=True)
    ap.add_argument("--sample", type=int, default=5000, help="평가할 쌍 수 (0 = 전부)")
    ap.add_argument("--mix", type=int, nargs="+", default=[0, 2, 9], help="질의에 섞는 무관 사진 수")
    ap.add_argument("--gallery-fallback-weight", type=float, default=1.0, help="갤러리 폴백 사진 쌍 점수 가중 (1 = 끔)")
    ap.add_argument("--pool", type=int, default=0, help="후보 풀 크기 — 질의와 같은 축종 개체 N 마리 + 정답 (0 = 전국 갤러리)")
    ap.add_argument("--records", nargs="*", default=[], help="records.jsonl 글롭 — --pool 의 축종 조회에 필요")
    ap.add_argument("--k", type=int, nargs="+", default=[1, 5, 20])
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--include-duplicates", action="store_true")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    ids, gallery = load_snapshot(Path(args.snapshot))
    fallback = load_fallback(Path(args.snapshot), ids)
    if fallback is None:
        raise SystemExit("스냅샷에 fallback.npy 가 없다 — 폴백 규칙을 잴 수 없으므로 최신 스냅샷을 쓴다")
    pairs = read_pairs(Path(args.pairs), args.include_duplicates)
    up_kind_of = {no: meta["up_kind_cd"] for no, meta in load_records(args.records).items()} if args.records else None
    result = evaluate(ids, gallery, fallback, pairs, args.mix, args.sample, args.seed, args.gallery_fallback_weight, args.k,
                      pool=args.pool, up_kind_of=up_kind_of)
    result["snapshot"] = str(args.snapshot)
    result["pairs"] = str(args.pairs)
    result["seed"] = args.seed
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"→ {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
