"""대표 테스트 데이터셋 생성 — 임계값 비교(-115)·성능 검증(-58)이 반복 사용하는 표준 세트.

전량(45만 장, TAR 523개·129GB)에서 매번 뽑는 대신, 벡터 파일이 TAR 단위(vectors-<stem>.tsv)로
나뉘는 점을 이용해 **월별로 분산된 TAR 풀** 안에서만 선별한다 — 같은 개체의 사진 1·2는 같은 TAR에
있으므로 쌍이 깨지지 않고, 이미지 추출은 풀 TAR 몇 개만 스트리밍하면 된다.

    입력: --pairs        make_pairs.py 출력 (전체 정답쌍, duplicate 표시 포함)
          --vectors-dir  vectors-*.tsv + detections-*.jsonl (bulk_embed 출력, 풀 stem만 읽는다)
          --records      records.jsonl 글롭 — 품종·색·성별 메타데이터
    출력: --out-dir 아래
          dataset.json     재현성 헤더 (출처·정답 기준·전처리·모델 버전·시드·풀 구성)
          manifest.jsonl   사진별 1행 — photo_id·그룹·출처·TAR·품종·색·폴백·역할·태그(시각 태그는 --merge-tags로 병합)
          pairs.tsv        양성·음성 쌍 (label 1/0, 유형별) — 임계값 곡선 입력
          extract-list.tsv TAR별 추출 대상 (extract_images.py 입력, 커밋 대상 아님)
          stats.json       축별 분포 통계

양성 = 같은 개체의 사진 1·2 (make_pairs 규칙, cos ≥ 0.999 동일 사진 제외).
음성 = ① neg_hard_mined: 풀 갤러리에서 질의와 코사인이 가장 높은 **다른 개체** (같은 축종)
       ② neg_hard_attr: 같은 품종·같은 색의 다른 개체  ③ neg_easy: 다른 축종 무작위.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

# 매 홀수 달의 첫 TAR — 수집 기간(2024-01~2026-09) 전체에 계절·시기가 고르게 걸린다
DEFAULT_POOL_MONTHS = ["202401", "202403", "202405", "202407", "202409", "202411",
                       "202501", "202503", "202505", "202507", "202509", "202511",
                       "202601", "202603", "202605", "202607", "202609"]
HDFS_TAR_ROOT = "/data/shelter/images-backfill"

SPECIES = {"417000": "개", "422400": "고양이"}  # 그 외 upKindCd는 '기타'
COS_BANDS = [("hard", 0.65), ("mid", 0.85), ("easy", 1.01)]  # cos_pair < 경계 → 밴드
FB_SHARE = {"none": 0.7, "one": 0.2, "both": 0.1}  # 실측 폴백 분포(80/15/5)보다 폴백 쪽을 약간 과표집


def species_of(up_kind_cd: str) -> str:
    return SPECIES.get(up_kind_cd, "기타")


def cos_band(cos: float) -> str:
    for name, upper in COS_BANDS:
        if cos < upper:
            return name
    return "easy"


def fallback_pattern(fq: str | int, ft: str | int) -> str:
    fq, ft = int(fq), int(ft)
    return "none" if not (fq or ft) else "both" if (fq and ft) else "one"


def pick_pool_stems(vectors_dir: Path, months: list[str], per_month: int = 1) -> list[str]:
    """월별로 stem 정렬 후 앞에서 per_month개 — 풀 구성은 (months, per_month, 파일 목록)에만 의존해 결정적이다."""
    by_month: dict[str, list[str]] = defaultdict(list)
    for path in sorted(vectors_dir.rglob("vectors-*.tsv")):
        stem = path.stem.removeprefix("vectors-")  # images-YYYYMM-NNNN
        parts = stem.split("-")
        if len(parts) >= 3 and parts[1] in months:
            by_month[parts[1]].append(stem)
    return [s for m in months for s in sorted(set(by_month.get(m, [])))[:per_month]]


def load_pool(vectors_dir: Path, stems: list[str]) -> tuple[list[str], np.ndarray, dict[str, str], dict[str, bool]]:
    """풀 stem의 벡터·탐지 플래그만 적재 — (photo_id 목록, L2 정규화 행렬, photo_id→stem, photo_id→fallback)."""
    wanted = set(stems)
    ids: list[str] = []
    rows: list[np.ndarray] = []
    pid2stem: dict[str, str] = {}
    fallback: dict[str, bool] = {}
    for path in sorted(vectors_dir.rglob("vectors-*.tsv")):
        stem = path.stem.removeprefix("vectors-")
        if stem not in wanted:
            continue
        with path.open(encoding="utf-8") as fp:
            for line in fp:
                pid, _, vec_csv = line.rstrip("\n").partition("\t")
                if not vec_csv or pid in pid2stem:
                    continue
                ids.append(pid)
                rows.append(np.array(vec_csv.split(","), dtype=np.float32))
                pid2stem[pid] = stem
    for path in sorted(vectors_dir.rglob("detections-*.jsonl")):
        if path.stem.removeprefix("detections-") not in wanted:
            continue
        with path.open(encoding="utf-8") as fp:
            for line in fp:
                d = json.loads(line)
                if "error" not in d:
                    fallback[d["photo_id"]] = bool(d.get("fallback"))
    if not rows:
        raise SystemExit(f"풀 stem {stems} 에 해당하는 벡터가 {vectors_dir} 아래에 없습니다")
    matrix = np.vstack(rows)
    matrix /= np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    return ids, matrix, pid2stem, fallback


def load_records_full(patterns: list[str]) -> dict[str, dict]:
    """desertionNo → 품종·색·성별 메타 (보호소 연락처·인명 필드는 싣지 않는다 — AI 리뷰 diff 포함 주의)."""
    meta: dict[str, dict] = {}
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            with open(path, encoding="utf-8") as fp:
                for line in fp:
                    r = json.loads(line)
                    no = str(r.get("desertionNo") or "")
                    if no:
                        meta[no] = {"up_kind_cd": r.get("upKindCd") or "", "up_kind_nm": r.get("upKindNm") or "",
                                    "kind_cd": r.get("kindCd") or "", "kind_nm": r.get("kindNm") or "",
                                    "color": r.get("colorCd") or "", "sex": r.get("sexCd") or ""}
    return meta


def select_positive_pairs(pairs: list[dict], pid2stem: dict[str, str], rng: np.random.Generator,
                          quotas: dict[str, int], breed_cap: int) -> list[dict]:
    """풀 안의 실쌍을 축종 × 폴백 패턴 × 코사인 밴드로 층화 선별.

    품종 캡으로 다양성을 강제하되, 캡 때문에 할당량이 미달하면 캡을 완화해 채운다.
    폴백 층이 비어 할당량이 남으면 마지막에 전체 층에서 채운다. 셔플은 시드에만 의존한다.
    """
    candidates = [p for p in pairs if p["duplicate"] == "0"
                  and p["query_id"] in pid2stem and p["target_id"] in pid2stem]
    by_stratum: dict[tuple, list[dict]] = defaultdict(list)
    for p in candidates:
        by_stratum[(species_of(p["up_kind_cd"]), fallback_pattern(p["fallback_query"], p["fallback_target"]),
                    cos_band(float(p["cos_pair"])))].append(p)
    for group in by_stratum.values():
        rng.shuffle(group)  # type: ignore[arg-type]

    selected: list[dict] = []
    chosen: set[str] = set()
    breed_count: Counter = Counter()

    def take(sp: str, fbs: list[str], want: int, relax_cap: bool) -> int:
        """코사인 밴드를 라운드로빈으로 돌며 want개까지 선택 — 난이도가 고르게 섞인다."""
        bands = [by_stratum.get((sp, fb, b), []) for fb in fbs for b, _ in COS_BANDS]
        idx = [0] * len(bands)
        got = 0
        while got < want:
            progressed = False
            for bi, band in enumerate(bands):
                while idx[bi] < len(band):
                    p = band[idx[bi]]
                    idx[bi] += 1
                    if p["desertion_no"] in chosen:
                        continue
                    if not relax_cap and breed_count[(sp, p["kind_cd"])] >= breed_cap:
                        continue
                    selected.append(p)
                    chosen.add(p["desertion_no"])
                    breed_count[(sp, p["kind_cd"])] += 1
                    got += 1
                    progressed = True
                    break
                if got >= want:
                    break
            if not progressed:
                break
        return got

    for sp, quota in quotas.items():
        picked = 0
        for fb, share in FB_SHARE.items():
            want = round(quota * share)
            got = take(sp, [fb], want, relax_cap=False)
            if got < want:
                got += take(sp, [fb], want - got, relax_cap=True)
            picked += got
        if picked < quota:  # 폴백 층 소진 — 전체 층에서 마저 채운다 (캡 우선, 그래도 미달이면 완화)
            got = take(sp, list(FB_SHARE), quota - picked, relax_cap=False)
            if picked + got < quota:
                take(sp, list(FB_SHARE), quota - picked - got, relax_cap=True)
    return selected


def mine_hard_negatives(ids: list[str], matrix: np.ndarray, queries: list[str],
                        meta: dict[str, dict], per_query: int) -> list[dict]:
    """풀 갤러리에서 질의별 최고 코사인 **다른 개체·같은 축종** 사진을 채굴 — 실제 임계값이 걸러야 하는 상대."""
    index = {pid: i for i, pid in enumerate(ids)}
    gallery_group = np.array([pid.rpartition("_")[0] for pid in ids])
    gallery_species = np.array([species_of(meta.get(g, {}).get("up_kind_cd", "")) for g in gallery_group])
    out: list[dict] = []
    for q in queries:
        qi = index.get(q)
        if qi is None:
            continue
        q_no = q.rpartition("_")[0]
        scores = matrix @ matrix[qi]
        mask = (gallery_group != q_no) & (gallery_species == species_of(meta.get(q_no, {}).get("up_kind_cd", "")))
        cand = np.where(mask)[0]
        if not len(cand):
            continue
        top = cand[np.argsort(scores[cand])[::-1][:per_query]]
        out.extend({"query_id": q, "target_id": ids[t], "cos": float(scores[t])} for t in top)
    return out


PAIR_COLUMNS = ["pair_id", "pair_type", "label", "query_id", "target_id", "cos_v2",
                "query_group", "target_group", "query_species", "target_species",
                "query_kind_nm", "target_kind_nm"]


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="대표 테스트 데이터셋 생성 (층화 선별 + 음성쌍 채굴)")
    ap.add_argument("--pairs")
    ap.add_argument("--vectors-dir")
    ap.add_argument("--records", nargs="+", default=[])
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--dataset-id", default="repr-v1")
    ap.add_argument("--seed", type=int, default=20260908)
    ap.add_argument("--pool-months", nargs="*", default=DEFAULT_POOL_MONTHS)
    ap.add_argument("--tars-per-month", type=int, default=1)
    ap.add_argument("--dogs", type=int, default=60)
    ap.add_argument("--cats", type=int, default=45)
    ap.add_argument("--others", type=int, default=15)
    ap.add_argument("--breed-cap", type=int, default=8, help="1차 선별에서 품종당 최대 개체 수")
    ap.add_argument("--hard-per-query", type=int, default=2)
    ap.add_argument("--attr-negatives", type=int, default=100)
    ap.add_argument("--easy-negatives", type=int, default=100)
    ap.add_argument("--samples-dir", default="", help="팀 자체 촬영 이미지 디렉터리 (source=team-sample로 등재)")
    ap.add_argument("--merge-tags", default="", help="photo_id/angle/background/quality TSV — manifest에 병합만 하고 종료")
    args = ap.parse_args()
    out_dir = Path(args.out_dir)

    if args.merge_tags:
        return merge_tags(out_dir, Path(args.merge_tags))
    if not (args.pairs and args.vectors_dir and args.records):
        ap.error("데이터셋 생성에는 --pairs, --vectors-dir, --records 가 모두 필요합니다")

    with open(args.pairs, encoding="utf-8") as fp:
        all_pairs = list(csv.DictReader(fp, delimiter="\t"))
    meta = load_records_full(args.records)
    stems = pick_pool_stems(Path(args.vectors_dir), args.pool_months, args.tars_per_month)
    ids, matrix, pid2stem, pool_fallback = load_pool(Path(args.vectors_dir), stems)
    print(f"풀: TAR {len(stems)}개, 사진 {len(ids):,}장")

    rng = np.random.default_rng(args.seed)
    quotas = {"개": args.dogs, "고양이": args.cats, "기타": args.others}
    positives = select_positive_pairs(all_pairs, pid2stem, rng, quotas, args.breed_cap)

    hard = mine_hard_negatives(ids, matrix, [p["query_id"] for p in positives], meta, args.hard_per_query)

    def sp_kind(pid: str) -> tuple[str, str, str]:
        m = meta.get(pid.rpartition("_")[0], {})
        return species_of(m.get("up_kind_cd", "")), m.get("kind_cd", ""), m.get("color", "")

    # 같은 품종·같은 색 다른 개체 (선별된 사진끼리 — 새 추출 없이)
    sel_photos = sorted({p[k] for p in positives for k in ("query_id", "target_id")})
    index = {pid: i for i, pid in enumerate(ids)}
    by_attr: dict[tuple, list[str]] = defaultdict(list)
    for pid in sel_photos:
        _, kind, color = sp_kind(pid)
        if kind and color:
            by_attr[(kind, color)].append(pid)
    attr_all = [(a, b) for group in by_attr.values() for i, a in enumerate(group) for b in group[i + 1:]
                if a.rpartition("_")[0] != b.rpartition("_")[0]]
    attr_pairs = [{"query_id": attr_all[i][0], "target_id": attr_all[i][1],
                   "cos": float(matrix[index[attr_all[i][0]]] @ matrix[index[attr_all[i][1]]])}
                  for i in rng.permutation(len(attr_all))[:args.attr_negatives]] if attr_all else []

    # 다른 축종 무작위 (쉬운 음성 — 곡선의 바닥 확인용)
    by_species: dict[str, list[str]] = defaultdict(list)
    for pid in sel_photos:
        by_species[sp_kind(pid)[0]].append(pid)
    sp_names = sorted(by_species)
    easy_pairs: list[dict] = []
    if len(sp_names) >= 2:
        seen: set[tuple[str, str]] = set()
        tries = 0
        while len(easy_pairs) < args.easy_negatives and tries < args.easy_negatives * 20:
            tries += 1
            sa, sb = rng.choice(len(sp_names), 2, replace=False)
            a = by_species[sp_names[sa]][rng.integers(len(by_species[sp_names[sa]]))]
            b = by_species[sp_names[sb]][rng.integers(len(by_species[sp_names[sb]]))]
            if (a, b) in seen:
                continue
            seen.add((a, b))
            easy_pairs.append({"query_id": a, "target_id": b,
                               "cos": float(matrix[index[a]] @ matrix[index[b]])})

    # ---- 산출물 ----
    out_dir.mkdir(parents=True, exist_ok=True)
    photos: dict[str, dict] = {}

    def add_photo(pid: str, role: str) -> None:
        no = pid.rpartition("_")[0]
        m = meta.get(no, {})
        stem = pid2stem.get(pid, "")
        month = stem.split("-")[1] if stem else ""
        entry = photos.setdefault(pid, {
            "photo_id": pid, "group_id": no, "source": "public-api-backfill", "month": month,
            "tar_stem": stem, "hdfs_tar": f"{HDFS_TAR_ROOT}/yyyymm={month}/{stem}.tar" if stem else "",
            "up_kind_cd": m.get("up_kind_cd", ""), "species": species_of(m.get("up_kind_cd", "")),
            "kind_nm": m.get("kind_nm", ""), "color": m.get("color", ""), "sex": m.get("sex", ""),
            "fallback": bool(pool_fallback.get(pid, False)), "roles": [],
            "tags": {"angle": None, "background": None, "quality": None}})
        if role not in entry["roles"]:
            entry["roles"].append(role)

    rows: list[dict] = []

    def add_pair(ptype: str, label: int, q: str, t: str, cos: float) -> None:
        add_photo(q, "pos_query" if ptype == "pos" else "neg_query")
        add_photo(t, "pos_target" if ptype == "pos" else "confuser" if ptype == "neg_hard_mined" else "neg_target")
        rows.append({"pair_id": f"{ptype}-{len(rows):04d}", "pair_type": ptype, "label": label,
                     "query_id": q, "target_id": t, "cos_v2": round(cos, 4),
                     "query_group": q.rpartition("_")[0], "target_group": t.rpartition("_")[0],
                     "query_species": sp_kind(q)[0], "target_species": sp_kind(t)[0],
                     "query_kind_nm": meta.get(q.rpartition("_")[0], {}).get("kind_nm", ""),
                     "target_kind_nm": meta.get(t.rpartition("_")[0], {}).get("kind_nm", "")})

    for p in positives:
        add_pair("pos", 1, p["query_id"], p["target_id"], float(p["cos_pair"]))
    for n in hard:
        add_pair("neg_hard_mined", 0, n["query_id"], n["target_id"], n["cos"])
    for n in attr_pairs:
        add_pair("neg_hard_attr", 0, n["query_id"], n["target_id"], n["cos"])
    for n in easy_pairs:
        add_pair("neg_easy", 0, n["query_id"], n["target_id"], n["cos"])

    if args.samples_dir:
        for f in sorted(Path(args.samples_dir).glob("*.*")):
            photos[f.stem] = {"photo_id": f.stem, "group_id": f"sample-{f.stem}", "source": "team-sample",
                              "month": "", "tar_stem": "", "hdfs_tar": "",
                              "up_kind_cd": "", "species": "", "kind_nm": "", "color": "", "sex": "",
                              "fallback": False, "roles": ["sample"],
                              "tags": {"angle": None, "background": None, "quality": None}}

    with (out_dir / "manifest.jsonl").open("w", encoding="utf-8", newline="\n") as fp:
        for pid in sorted(photos):
            fp.write(json.dumps(photos[pid], ensure_ascii=False) + "\n")
    with (out_dir / "pairs.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        fp.write("\t".join(PAIR_COLUMNS) + "\n")
        for r in rows:
            fp.write("\t".join(str(r[c]) for c in PAIR_COLUMNS) + "\n")
    with (out_dir / "extract-list.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        for pid in sorted(photos):
            if photos[pid]["hdfs_tar"]:
                fp.write(f"{photos[pid]['hdfs_tar']}\t{pid}\n")

    stats = {
        "photos": len(photos), "animals": len({p["group_id"] for p in photos.values()}),
        "pairs": {t: sum(1 for r in rows if r["pair_type"] == t)
                  for t in ("pos", "neg_hard_mined", "neg_hard_attr", "neg_easy")},
        "by_species": dict(Counter(p["species"] or "샘플" for p in photos.values())),
        "by_fallback": dict(Counter("fallback" if p["fallback"] else "detected" for p in photos.values())),
        "distinct_breeds": len({(p["species"], p["kind_nm"]) for p in photos.values() if p["kind_nm"]}),
        "pos_cos_band": dict(Counter(cos_band(float(r["cos_v2"])) for r in rows if r["pair_type"] == "pos")),
        "months": sorted({p["month"] for p in photos.values() if p["month"]}),
    }
    (out_dir / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    header = {
        "dataset_id": args.dataset_id, "created": "2026-09-08", "seed": args.seed,
        "sources": {"public-api-backfill": "공공데이터포털 유기동물 API 역사분(2024-01~2026-09 수집) — HDFS " + HDFS_TAR_ROOT,
                    "team-sample": "팀 자체 촬영 대표 이미지 (#56, 서버 2 /home/ubuntu/ai/samples)"},
        "ground_truth": "양성 = 같은 desertionNo의 사진 1·2 (make_pairs.py — cos ≥ 0.999 동일 사진 제외). "
                        "음성 = 다른 개체 (neg_hard_mined: 풀 갤러리 최고 코사인 혼동 개체 / "
                        "neg_hard_attr: 같은 품종·같은 색 / neg_easy: 다른 축종)",
        "preprocessing": "마스킹·크롭 v2 (YOLO26l-seg, ai/model.yaml detector 블록)",
        "model": {"model_id": "dinov2_vitb14", "model_version": "v2", "dim": 768, "normalized": True},
        "vectors": "/embeddings/dinov2_vitb14/v2/shelter-backfill (HDFS)",
        "pool": {"months": args.pool_months, "tars_per_month": args.tars_per_month, "stems": stems},
        "quotas": quotas, "breed_cap": args.breed_cap,
        "images": {"server": f"/home/ubuntu/eval-app/datasets/{args.dataset_id}/images",
                   "hdfs": f"/embeddings/eval/datasets/{args.dataset_id}"},
    }
    (out_dir / "dataset.json").write_text(json.dumps(header, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    print(f"사진 {stats['photos']}장 (개체 {stats['animals']}) | 쌍 {stats['pairs']} | "
          f"품종 {stats['distinct_breeds']}종 | 저장 {out_dir}")
    return 0


def merge_tags(out_dir: Path, tags_path: Path) -> int:
    """시각 태그(각도·배경·품질) TSV를 manifest.jsonl에 병합 — 태그 파일에 없는 사진은 그대로 둔다."""
    with tags_path.open(encoding="utf-8") as fp:
        tags = {r["photo_id"]: r for r in csv.DictReader(fp, delimiter="\t")}
    manifest = out_dir / "manifest.jsonl"
    entries = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines() if line]
    unknown = set(tags) - {e["photo_id"] for e in entries}
    merged = 0
    for e in entries:
        t = tags.get(e["photo_id"])
        if t:
            e["tags"] = {"angle": t.get("angle") or None, "background": t.get("background") or None,
                         "quality": t.get("quality") or None}
            merged += 1
    with manifest.open("w", encoding="utf-8", newline="\n") as fp:
        for e in entries:
            fp.write(json.dumps(e, ensure_ascii=False) + "\n")
    if unknown:
        print(f"경고: manifest에 없는 photo_id {len(unknown)}건 무시 — {sorted(unknown)[:5]}")
    print(f"태그 병합 {merged}/{len(entries)}장")
    return 0


if __name__ == "__main__":
    sys.exit(main())
