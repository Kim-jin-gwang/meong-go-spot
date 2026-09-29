"""recall@K 평가 — 사진 1의 벡터로 전체 갤러리를 검색해 같은 개체의 사진 2가 Top-K 안에 드는 비율.

모델 비교의 공정한 심판(§4 ⑤): 정답쌍 파일은 고정하고 벡터 디렉터리만 바꿔 실행한다.

    질의 q = pairs.query_id 의 벡터,  정답 t = pairs.target_id 의 벡터
    갤러리 G = --vectors-dir 의 모든 벡터 (자기 자신 q 는 제외)
    rank(t) = 1 + |{g ∈ G∖{q} : cos(q,g) > cos(q,t)}|
    recall@K = mean(rank ≤ K),  MRR = mean(1/rank)

보고 항목
- 전체 / 갤러리를 같은 축종(upKindCd)으로 제한한 경우(제품이 실제로 하는 필터)
- 폴백 패턴별(양쪽 탐지 / 한쪽 폴백 / 양쪽 폴백), 축종별
- 같은 보호소 다른 개체 vs 무작위 다른 개체의 평균 코사인 (워터마크 편향 진단)
- --random-baseline: 벡터를 무작위 정규 벡터로 바꿔 채점기 자체를 검증 (recall@K ≈ K/N 이어야 함)

벡터는 L2 정규화돼 있어 내적 = 코사인. 452k×768 float32 ≈ 1.4GB — 서버 2(15GB)에서 실행.
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

from make_pairs import iter_vectors, load_records


def load_snapshot(snapshot_dir: Path, mmap: bool = False) -> tuple[list[str], np.ndarray]:
    """`index_tools.py snapshot` 산출물 — TSV 파싱(45만 장 65초) 없이 이진 행렬을 그대로 쓴다.

    정규화는 하지 않고 **검사만** 한다 — 계약상 벡터는 L2 정규화돼 있고(§4 `normalized: true`),
    아니라면 상류가 깨진 것이라 조용히 고칠 일이 아니다.

    `mmap` 선택 기준(실측): 메모리맵은 열기가 0.1초지만 **행렬 전체를 반복해 훑는 작업에서는 느리다**
    — 평가 2만 쌍이 40묶음으로 갤러리를 40번 읽어 191초가 걸렸고, RAM 에 올리면 77초였다.
    평가처럼 전량을 훑으면 기본값(RAM)을, 요청마다 수백 행만 보는 상주 worker 는 `mmap=True` 를 쓴다.
    """
    meta = json.loads((snapshot_dir / "meta.json").read_text(encoding="utf-8"))
    ids = (snapshot_dir / meta.get("ids_file", "ids.txt")).read_text(encoding="utf-8").split()
    matrix = np.load(snapshot_dir / meta["vectors_file"], mmap_mode="r" if mmap else None)
    if matrix.shape != (len(ids), int(meta["dim"])):
        raise SystemExit(f"스냅샷 형태 {matrix.shape} != ids {len(ids)} × dim {meta['dim']}")
    if not ids:  # 빈 표본은 allclose 가 True 라 정규화 검사를 그냥 지난다 — 여기서 막는다 (load_gallery 와 같은 계약)
        raise SystemExit(f"{snapshot_dir} 스냅샷에 벡터가 없습니다")
    probe = np.asarray(matrix[np.linspace(0, len(ids) - 1, min(100, len(ids)), dtype=int)], dtype=np.float32)
    norms = np.linalg.norm(probe, axis=1)
    if not np.allclose(norms, 1.0, atol=2e-3):
        raise SystemExit(f"스냅샷이 L2 정규화돼 있지 않습니다 (표본 norm {norms.min():.4f}~{norms.max():.4f})")
    if matrix.dtype != np.float32:  # float16 스냅샷은 내적 정밀도·속도가 떨어져 올릴 때 한 번 변환한다
        if mmap:  # 변환이 전량을 RAM 으로 올리므로 메모리맵 이점이 사라진다 — 조용히 넘기지 않고 알린다
            print(f"주의: {matrix.dtype} 스냅샷은 float32 로 승격되어 전량 RAM 에 올라갑니다 (--mmap 무의미)")
        matrix = np.asarray(matrix, dtype=np.float32)
    return ids, matrix


def load_fallback(snapshot_dir: Path, ids: list[str]) -> np.ndarray | None:
    """스냅샷의 탐지 폴백 플래그 (ids 순서, bool). 없으면 None.

    `load_snapshot` 의 반환 형태를 바꾸지 않는다 — 기존 호출부 셋(평가·데모·시늉)이 두 값을
    받아 쓰고 있고, 플래그가 필요한 쪽만 이 함수를 부르면 된다.

    폴백 사진은 탐지에 실패해 원본 전체(배경 포함)를 임베딩한 것이다. 계약 §후보 출력 정책
    0-2 의 근거 — 한쪽만 폴백이면 recall@1 이 0.179 로 떨어지고 양쪽 폴백은 0.603 이다.
    배경끼리 닮아 점수가 나오기 때문이다. 실측 비율은 55,136/455,486 = 12.1% (2026-09-11).

    **오래된 스냅샷에는 없다.** None 을 조용히 "전부 정상" 으로 바꾸지 않는다 — 호출부가
    플래그 없이 어떻게 할지 직접 정해야 한다.
    """
    meta = json.loads((snapshot_dir / "meta.json").read_text(encoding="utf-8"))
    name = meta.get("fallback_file")
    if not name or not (snapshot_dir / name).is_file():
        return None
    flags = np.load(snapshot_dir / name)
    if flags.shape != (len(ids),):
        raise SystemExit(f"폴백 배열 형태 {flags.shape} != ids {len(ids)}")
    if flags.dtype != bool:
        raise SystemExit(f"폴백 배열 dtype {flags.dtype} != bool")
    recorded = meta.get("fallback_count")
    if recorded is not None and int(flags.sum()) != int(recorded):
        raise SystemExit(f"폴백 개수 {int(flags.sum()):,} != meta 의 {int(recorded):,}")
    return flags


def load_gallery(vectors_dir: Path) -> tuple[list[str], np.ndarray]:
    ids: list[str] = []
    rows: list[np.ndarray] = []
    for pid, _, vec in iter_vectors(vectors_dir):
        ids.append(pid)
        rows.append(vec)
    if not rows:
        raise SystemExit(f"{vectors_dir} 아래에 벡터가 없습니다")
    matrix = np.vstack(rows)
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return ids, matrix / np.maximum(norms, 1e-12)


def read_pairs(path: Path, include_duplicates: bool) -> list[dict]:
    with path.open(encoding="utf-8") as fp:
        pairs = [row for row in csv.DictReader(fp, delimiter="\t")]
    return [p for p in pairs if include_duplicates or p["duplicate"] == "0"]


def rank_targets(gallery: np.ndarray, q_idx: np.ndarray, t_idx: np.ndarray, chunk: int,
                 kinds: np.ndarray | None = None) -> np.ndarray:
    """질의 묶음별로 갤러리 전체와 내적해 정답의 순위(1부터)를 구한다.

    kinds 가 주어지면 질의와 같은 값(축종)인 갤러리만 후보로 센다 — 제품이 실제로 하는 축종 필터 재현.
    묶음 단위로 마스크를 만들어 (질의 수 × 갤러리) 크기의 행렬을 한 번에 들지 않는다.
    """
    ranks = np.empty(len(q_idx), dtype=np.int64)
    for start in range(0, len(q_idx), chunk):
        qi, ti = q_idx[start:start + chunk], t_idx[start:start + chunk]
        scores = gallery[qi] @ gallery.T  # (chunk, N)
        target = scores[np.arange(len(qi)), ti][:, None]
        better = scores > target
        better[np.arange(len(qi)), qi] = False  # 자기 자신 제외
        if kinds is not None:
            better &= kinds[None, :] == kinds[qi][:, None]
        ranks[start:start + chunk] = better.sum(axis=1) + 1
    return ranks


def assignment_files(path: Path) -> list[Path]:
    """배정 파일을 **정해진 순서**로 — `base.tsv`(배치 전량) → `part-*`(잡 출력) → 나머지 증분 `*.tsv` 이름 순.

    같은 사진 ID 가 두 번 나오면 나중 파일이 이긴다(계약 §3). 그 우선순위를 파일명 알파벳 우연에
    맡기지 않으려고 순서를 여기서 못 박는다 — base 를 먼저 깔고 일일 증분이 덮는 구조다.
    """
    if not path.is_dir():
        return [path]
    base = sorted(path.glob("base.tsv"))
    parts = sorted(path.glob("part-*"))
    seen = set(base) | set(parts)  # part-00000.tsv 처럼 확장자가 붙으면 rest 에도 걸려 두 번 읽힌다
    rest = sorted(p for p in path.glob("*.tsv") if p not in seen)
    return base + parts + rest


def load_assignments(path: Path) -> dict[str, int]:
    """K-Means 배정(`id\\tclusterId`) — 파일 하나 또는 part-* 디렉터리."""
    files = assignment_files(path)
    out: dict[str, int] = {}
    overwritten = 0
    for f in files:
        with f.open(encoding="utf-8") as fp:
            for line in fp:
                pid, _, cid = line.rstrip("\n").partition("\t")
                if pid and cid:
                    overwritten += pid in out
                    out[pid] = int(cid)
    if not out:
        raise SystemExit(f"{path} 에 배정이 없습니다 (찾은 파일: {[f.name for f in files]})")
    if overwritten:
        print(f"  배정 파일 {len(files)}개 · 중복 사진 {overwritten:,}건은 나중 파일 값을 씀")
    return out


def load_centers(path: Path) -> tuple[np.ndarray, np.ndarray]:
    """중심 파일(`clusterId\\tv1,...`) → (클러스터 id 배열, (K, d) 행렬). 파일 하나 또는 part-* 디렉터리."""
    files = sorted(path.glob("part-*")) if path.is_dir() else [path]
    ids, rows = [], []
    for f in files:
        with f.open(encoding="utf-8") as fp:
            for line in fp:
                cid, _, csv_ = line.rstrip("\n").partition("\t")
                if cid and csv_:
                    ids.append(int(cid))
                    rows.append(np.array(csv_.split(","), dtype=np.float32))
    if not rows:
        raise SystemExit(f"{path} 에 중심이 없습니다")
    return np.array(ids), np.vstack(rows)


def rank_targets_probe(gallery: np.ndarray, q_idx: np.ndarray, t_idx: np.ndarray, chunk: int,
                       labels: np.ndarray, center_ids: np.ndarray, centers: np.ndarray, nprobe: int) -> tuple[np.ndarray, float]:
    """K-Means 색인으로 후보를 줄였을 때의 정답 순위 — 질의와 가까운 중심 nprobe 개의 클러스터만 탐색한다.

    정답이 탐색 범위 밖이면 순위 = 갤러리 크기 + 1 (어느 K 에도 안 잡힘). 반환 (순위, 평균 후보 비율).
    중심 배정은 K-Means 와 같은 유클리드 기준: argmin ‖q−c‖² = argmax (q·c − ‖c‖²/2). labels 는 갤러리 순서의 클러스터 id, 없으면 -1.
    """
    half_norms = 0.5 * (centers.astype(np.float32) ** 2).sum(axis=1)
    n = gallery.shape[0]
    ranks = np.empty(len(q_idx), dtype=np.int64)
    candidate_total = 0.0
    nprobe = min(nprobe, len(center_ids))
    for start in range(0, len(q_idx), chunk):
        qi, ti = q_idx[start:start + chunk], t_idx[start:start + chunk]
        affinity = gallery[qi] @ centers.T - half_norms[None, :]  # (chunk, K)
        top = np.argpartition(-affinity, nprobe - 1, axis=1)[:, :nprobe]  # 중심 인덱스
        allowed = np.zeros((len(qi), n), dtype=bool)
        for j in range(nprobe):
            allowed |= labels[None, :] == center_ids[top[:, j]][:, None]
        allowed[np.arange(len(qi)), qi] = False  # 자기 자신 제외
        candidate_total += float(allowed.sum())
        scores = gallery[qi] @ gallery.T
        target = scores[np.arange(len(qi)), ti][:, None]
        better = (scores > target) & allowed
        in_range = allowed[np.arange(len(qi)), ti]
        ranks[start:start + chunk] = np.where(in_range, better.sum(axis=1) + 1, n + 1)
    return ranks, candidate_total / (len(q_idx) * max(n - 1, 1))


def summarize(ranks: np.ndarray, ks: list[int]) -> dict:
    return {**{f"recall@{k}": round(float(np.mean(ranks <= k)), 4) for k in ks},
            "mrr": round(float(np.mean(1.0 / ranks)), 4), "n": int(len(ranks)), "median_rank": int(np.median(ranks))}


def shelter_bias(gallery: np.ndarray, index: dict[str, int], pairs: list[dict], rng: random.Random, n: int) -> dict:
    """같은 보호소의 다른 개체 vs 무작위 다른 개체 — 질의 사진 1끼리의 평균 코사인."""
    by_care: dict[str, list[str]] = defaultdict(list)
    for p in pairs:
        if p["care_reg_no"]:
            by_care[p["care_reg_no"]].append(p["query_id"])
    candidates = [c for c, ids in by_care.items() if len(ids) >= 2]
    all_queries = sorted({p["query_id"] for p in pairs})
    if not candidates or len(all_queries) < 2:  # 비교 상대가 없으면 계산 불가 (무한 루프 방지)
        return {"n": 0}
    same, rand_ = [], []
    for _ in range(min(n, len(candidates) * 4)):
        care = rng.choice(candidates)
        a, b = rng.sample(by_care[care], 2)
        same.append(float(gallery[index[a]] @ gallery[index[b]]))
        c = rng.choice(all_queries)
        while c == a:
            c = rng.choice(all_queries)
        rand_.append(float(gallery[index[a]] @ gallery[index[c]]))
    return {"n": len(same), "same_shelter_mean_cos": round(float(np.mean(same)), 4), "random_mean_cos": round(float(np.mean(rand_)), 4)}


def evaluate(gallery_ids: list[str], gallery: np.ndarray, pairs: list[dict], ks: list[int], sample: int | None,
             seed: int, chunk: int, up_kind_of: dict[str, str] | None,
             probe: tuple[dict[str, int], np.ndarray, np.ndarray, list[int]] | None = None) -> dict:
    """probe = (배정, 중심 id, 중심 행렬, nprobe 목록) — 주면 K-Means 후보 축소 시 recall 손실도 계산한다."""
    index = {pid: i for i, pid in enumerate(gallery_ids)}
    usable = [p for p in pairs if p["query_id"] in index and p["target_id"] in index]
    rng = random.Random(seed)
    if sample and len(usable) > sample:
        usable = rng.sample(usable, sample)
    q_idx = np.array([index[p["query_id"]] for p in usable])
    t_idx = np.array([index[p["target_id"]] for p in usable])
    t0 = time.time()
    ranks = rank_targets(gallery, q_idx, t_idx, chunk)
    result = {"pairs_evaluated": len(usable), "gallery_size": len(gallery_ids), "k": ks,
              "overall": summarize(ranks, ks), "seconds": round(time.time() - t0, 1)}

    def group(key):
        out = {}
        for g in sorted({key(p) for p in usable}):
            sel = np.array([key(p) == g for p in usable])
            if sel.sum() >= 30:
                out[g] = summarize(ranks[sel], ks)
        return out

    result["by_fallback"] = group(lambda p: "both_detected" if p["fallback_query"] == "0" and p["fallback_target"] == "0"
                                  else "both_fallback" if p["fallback_query"] == "1" and p["fallback_target"] == "1" else "one_fallback")
    result["by_up_kind"] = group(lambda p: p["up_kind_cd"] or "unknown")

    if up_kind_of:  # 제품 필터 재현: 갤러리를 질의와 같은 축종으로 제한
        kinds = np.array([up_kind_of.get(pid.rpartition("_")[0], "") for pid in gallery_ids])
        result["same_up_kind_gallery"] = summarize(rank_targets(gallery, q_idx, t_idx, chunk, kinds), ks)

    result["shelter_bias"] = shelter_bias(gallery, index, usable, rng, 2000)

    if probe:  # K-Means 색인 후보 축소 — 정확 kNN(overall) 대비 손실과 탐색 후보 비율
        assignments, center_ids, centers, nprobes = probe
        labels = np.array([assignments.get(pid, -1) for pid in gallery_ids])
        sizes = np.bincount(labels[labels >= 0])
        result["index"] = {"clusters": int(len(center_ids)), "assigned": int((labels >= 0).sum()),
                           "unassigned": int((labels < 0).sum()),
                           "cluster_size": {"min": int(sizes[sizes > 0].min()), "median": int(np.median(sizes[sizes > 0])),
                                            "max": int(sizes.max()), "empty": int((sizes == 0).sum())}}
        result["probe"] = {}
        for nprobe in nprobes:
            t1 = time.time()
            pranks, fraction = rank_targets_probe(gallery, q_idx, t_idx, chunk, labels, center_ids, centers, nprobe)
            result["probe"][str(nprobe)] = {**summarize(pranks, ks), "candidate_fraction": round(fraction, 4),
                                            "target_outside": round(float(np.mean(pranks > len(gallery_ids))), 4),
                                            "seconds": round(time.time() - t1, 1)}
    return result


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="정답쌍 recall@K 평가 (모델 무관 심판)")
    ap.add_argument("--vectors-dir", help="벡터 TSV 디렉터리 (월 파티션 포함)")
    ap.add_argument("--snapshot", help="index_tools.py snapshot 디렉터리 — 주면 TSV 대신 이진 행렬을 읽는다 (로딩 65초 → 3초)")
    ap.add_argument("--mmap", action="store_true", help="스냅샷을 메모리맵으로 (RAM 부족할 때만 — 전량 훑기가 2.5배 느려진다)")
    ap.add_argument("--pairs", required=True)
    ap.add_argument("--records", nargs="*", default=[], help="records.jsonl 글롭 — 주면 같은 축종 갤러리 제한 결과도 계산")
    ap.add_argument("--k", type=int, nargs="+", default=[1, 5, 10, 20])
    ap.add_argument("--sample", type=int, default=20000, help="평가할 쌍 수 (0 = 전부)")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--chunk", type=int, default=500)
    ap.add_argument("--include-duplicates", action="store_true")
    ap.add_argument("--random-baseline", action="store_true", help="벡터를 무작위로 바꿔 채점기 검증 (recall@K ≈ K/N)")
    ap.add_argument("--assignments", help="K-Means 배정(id\\tcluster) 파일 또는 part-* 디렉터리 — --centers 와 함께 주면 후보 축소 손실 계산")
    ap.add_argument("--centers", help="K-Means 최종 중심(cluster\\tv1,...) 파일 또는 part-* 디렉터리")
    ap.add_argument("--nprobe", type=int, nargs="+", default=[1, 2, 4, 8], help="탐색할 최근접 클러스터 수 (여러 개)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if bool(args.assignments) != bool(args.centers):
        ap.error("--assignments 와 --centers 는 함께 줘야 합니다")
    if bool(args.vectors_dir) == bool(args.snapshot):
        ap.error("--vectors-dir 과 --snapshot 중 정확히 하나를 줘야 합니다")

    t_load = time.time()
    ids, gallery = load_snapshot(Path(args.snapshot), args.mmap) if args.snapshot else load_gallery(Path(args.vectors_dir))
    print(f"갤러리 {len(ids):,} × {gallery.shape[1]} 로딩 {time.time() - t_load:.1f}초"
          f" ({'스냅샷' if args.snapshot else 'TSV'})")
    if args.random_baseline:  # 새 배열을 만든다 — 스냅샷은 읽기 전용 메모리맵이라 제자리 수정이 안 된다
        rng = np.random.default_rng(args.seed)
        random_gallery = rng.standard_normal(gallery.shape, dtype=np.float32)
        gallery = random_gallery / np.linalg.norm(random_gallery, axis=1, keepdims=True)
    pairs = read_pairs(Path(args.pairs), args.include_duplicates)
    up_kind_of = {no: m["up_kind_cd"] for no, m in load_records(args.records).items()} if args.records else None
    probe = None
    if args.assignments:
        center_ids, centers = load_centers(Path(args.centers))
        probe = (load_assignments(Path(args.assignments)), center_ids, centers, args.nprobe)
    result = evaluate(ids, gallery, pairs, args.k, args.sample or None, args.seed, args.chunk, up_kind_of, probe)
    result["random_baseline"] = args.random_baseline
    result["vectors_dir"] = str(args.snapshot or args.vectors_dir)
    result["gallery_source"] = "snapshot" if args.snapshot else "tsv"
    if probe:
        result["index_paths"] = {"assignments": args.assignments, "centers": args.centers}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    o = result["overall"]
    print(f"갤러리 {result['gallery_size']:,} | 쌍 {result['pairs_evaluated']:,} | " + " ".join(f"R@{k}={o[f'recall@{k}']:.3f}" for k in args.k)
          + f" MRR={o['mrr']:.3f} 중앙순위={o['median_rank']} | {result['seconds']}초")
    for name, grp in (("폴백", result["by_fallback"]), ("축종", result["by_up_kind"])):
        for g, s in grp.items():
            print(f"  {name} {g}: n={s['n']:,} R@1={s['recall@1']:.3f} R@{args.k[-1]}={s[f'recall@{args.k[-1]}']:.3f}")
    if "same_up_kind_gallery" in result:
        s = result["same_up_kind_gallery"]
        print(f"  같은 축종 갤러리: R@1={s['recall@1']:.3f} R@{args.k[-1]}={s[f'recall@{args.k[-1]}']:.3f}")
    sb = result["shelter_bias"]
    if sb.get("n"):
        print(f"  같은 보호소 다른 개체 코사인 {sb['same_shelter_mean_cos']:.3f} vs 무작위 {sb['random_mean_cos']:.3f} (n={sb['n']})")
    if "probe" in result:
        cs = result["index"]["cluster_size"]
        print(f"  색인: 클러스터 {result['index']['clusters']} · 크기 min/중앙/max {cs['min']}/{cs['median']}/{cs['max']} · 빈 {cs['empty']} · 미배정 {result['index']['unassigned']}")
        for nprobe, s in result["probe"].items():
            print(f"  nprobe={nprobe}: 후보 {s['candidate_fraction']:.3%} · 정답 범위 밖 {s['target_outside']:.3%} · "
                  + " ".join(f"R@{k}={s[f'recall@{k}']:.3f}" for k in args.k) + f" ({s['seconds']}초)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
