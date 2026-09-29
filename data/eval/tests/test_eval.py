import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import eval_recall  # noqa: E402
import make_pairs  # noqa: E402


def _write_vectors(d: Path, rows: dict[str, list[float]], fallbacks: dict[str, bool] | None = None) -> None:
    d.mkdir(parents=True, exist_ok=True)
    with (d / "vectors-images-202407-0000.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        for pid, v in rows.items():
            fp.write(pid + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
    with (d / "detections-images-202407-0000.jsonl").open("w", encoding="utf-8", newline="\n") as fp:
        for pid in rows:
            fp.write(json.dumps({"photo_id": pid, "fallback": bool((fallbacks or {}).get(pid, False))}) + "\n")


def _write_records(path: Path, animals: dict[str, tuple[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as fp:
        for no, (kind, care) in animals.items():
            fp.write(json.dumps({"desertionNo": no, "upKindCd": kind, "kindCd": "000114", "careRegNo": care}) + "\n")


# 3차원 단위 벡터 — A 의 두 사진은 가깝고, C 의 두 사진은 동일(중복), B 의 2번째 사진은 D 보다 멀다
VECS = {
    "A_1": [1.0, 0.0, 0.0], "A_2": [0.98, 0.2, 0.0],
    "B_1": [0.0, 1.0, 0.0], "B_2": [0.0, 0.6, 0.8],          # B_1 에서 B_2 (cos 0.6) 보다 D_1 (cos 0.8) 이 더 가깝다 → rank 2
    "C_1": [0.0, 0.0, 1.0], "C_2": [0.0, 0.0, 1.0],          # 동일 사진 → duplicate
    "D_1": [0.0, 0.8, 0.6],                                   # 짝 없음 → 쌍 아님, 갤러리에는 포함
}
ANIMALS = {"A": ("417000", "care1"), "B": ("417000", "care1"), "C": ("422400", "care2"), "D": ("417000", "care1")}


def test_make_pairs_marks_duplicates_and_fallback(tmp_path: Path) -> None:
    vdir = tmp_path / "vec" / "yyyymm=202407"
    _write_vectors(vdir, VECS, fallbacks={"B_2": True})
    _write_records(tmp_path / "records.jsonl", ANIMALS)
    pairs, stats = make_pairs.build_pairs(tmp_path / "vec", make_pairs.load_records([str(tmp_path / "records.jsonl")]))
    by = {p["desertion_no"]: p for p in pairs}
    assert set(by) == {"A", "B", "C"} and stats["animals_with_two"] == 3
    assert by["C"]["duplicate"] == 1 and stats["duplicates"] == 1
    assert by["A"]["duplicate"] == 0 and by["A"]["query_id"] == "A_1" and by["A"]["target_id"] == "A_2"
    assert by["B"]["fallback_target"] == 1 and by["B"]["fallback_query"] == 0
    assert stats["fallback_one"] == 1 and stats["fallback_none"] == 1 and stats["pairs"] == 2
    assert by["A"]["care_reg_no"] == "care1" and by["C"]["up_kind_cd"] == "422400" and by["A"]["month"] == "202407"
    out = tmp_path / "pairs.tsv"
    make_pairs.write_pairs(pairs, out)
    assert eval_recall.read_pairs(out, include_duplicates=False) and all(p["duplicate"] == "0" for p in eval_recall.read_pairs(out, False))
    assert len(eval_recall.read_pairs(out, include_duplicates=True)) == 3


def test_recall_ranks_are_exact(tmp_path: Path) -> None:
    vdir = tmp_path / "vec" / "yyyymm=202407"
    _write_vectors(vdir, VECS)
    _write_records(tmp_path / "records.jsonl", ANIMALS)
    records = make_pairs.load_records([str(tmp_path / "records.jsonl")])
    pairs, _ = make_pairs.build_pairs(tmp_path / "vec", records)
    pairs = [p for p in pairs if not p["duplicate"]]
    for p in pairs:  # eval 은 TSV 를 읽은 문자열 딕셔너리를 기대한다
        for k in ("fallback_query", "fallback_target", "duplicate"):
            p[k] = str(p[k])
    ids, gallery = eval_recall.load_gallery(tmp_path / "vec")
    up_kind_of = {no: m["up_kind_cd"] for no, m in records.items()}
    result = eval_recall.evaluate(ids, gallery, pairs, [1, 2], sample=None, seed=0, chunk=1, up_kind_of=up_kind_of)
    # A: rank 1, B: rank 2 (D_1 이 더 가까움) → R@1 = 0.5, R@2 = 1.0, MRR = (1 + 0.5)/2
    o = result["overall"]
    assert (o["recall@1"], o["recall@2"], o["mrr"], o["n"]) == (0.5, 1.0, 0.75, 2)
    # 같은 축종 갤러리로 제한해도 D 가 같은 축종(417000)이라 B 의 순위는 그대로 2
    assert result["same_up_kind_gallery"]["recall@1"] == 0.5
    assert result["gallery_size"] == 7 and result["pairs_evaluated"] == 2


def test_random_baseline_recall_near_k_over_n() -> None:
    rng = np.random.default_rng(0)
    n = 2000
    gallery = rng.standard_normal((n, 16), dtype=np.float32)
    gallery /= np.linalg.norm(gallery, axis=1, keepdims=True)
    q = np.arange(0, n, 2)
    t = q + 1
    ranks = eval_recall.rank_targets(gallery, q, t, chunk=250)
    r20 = float(np.mean(ranks <= 20))
    assert 0.003 < r20 < 0.03, f"무작위 벡터의 recall@20 은 20/{n - 1}≈0.01 근처여야 한다: {r20}"
    assert ranks.min() >= 1 and ranks.max() <= n - 1


def test_repeated_photo_id_does_not_consume_the_pair(tmp_path: Path) -> None:
    vdir = tmp_path / "vec"
    # 같은 A_1 이 두 파일에 걸쳐 두 번 등장 — 첫 A_1 과 두 번째 A_1 을 짝지으면 진짜 짝 A_2 가 유실된다
    _write_vectors(vdir / "yyyymm=202407", {"A_1": [1.0, 0.0, 0.0]})
    _write_vectors(vdir / "yyyymm=202408", {"A_1": [1.0, 0.0, 0.0], "A_2": [0.98, 0.2, 0.0]})
    pairs, stats = make_pairs.build_pairs(vdir, {})
    assert [(p["query_id"], p["target_id"], p["duplicate"]) for p in pairs] == [("A_1", "A_2", 0)]
    assert stats["repeated_ids"] == 1 and stats["animals_with_two"] == 1


def test_probe_ranks_and_candidate_fraction(tmp_path: Path) -> None:
    # 갤러리 7개를 두 클러스터로 — 0: {A_1, A_2, D_1, B_2}, 1: {B_1, C_1, C_2}. B_1 의 정답 B_2 는 다른 클러스터.
    vdir = tmp_path / "vec" / "yyyymm=202407"
    _write_vectors(vdir, VECS)
    ids, gallery = eval_recall.load_gallery(tmp_path / "vec")
    cluster_of = {"A_1": 0, "A_2": 0, "D_1": 0, "B_2": 0, "B_1": 1, "C_1": 1, "C_2": 1}
    labels = np.array([cluster_of[pid] for pid in ids])
    center_ids = np.array([0, 1])
    centers = np.array([[0.9, 0.3, 0.1], [0.0, 0.5, 0.9]], dtype=np.float32)  # A 쪽 / B_1·C 쪽
    index = {pid: i for i, pid in enumerate(ids)}
    q = np.array([index["A_1"], index["B_1"]])
    t = np.array([index["A_2"], index["B_2"]])
    exact = eval_recall.rank_targets(gallery, q, t, chunk=1)
    assert list(exact) == [1, 2]
    ranks1, frac1 = eval_recall.rank_targets_probe(gallery, q, t, 1, labels, center_ids, centers, nprobe=1)
    assert ranks1[0] == 1, "A_1 → 클러스터 0 만 봐도 A_2 는 1위"
    assert ranks1[1] == len(ids) + 1, "B_1 은 클러스터 1 만 보므로 정답 B_2(클러스터 0)가 범위 밖"
    assert 0 < frac1 < 1
    ranks2, frac2 = eval_recall.rank_targets_probe(gallery, q, t, 1, labels, center_ids, centers, nprobe=2)
    assert list(ranks2) == [1, 2] and frac2 == 1.0, "nprobe=K 면 정확 탐색과 같다"
    assert frac2 > frac1


def test_evaluate_reports_probe_and_index_stats(tmp_path: Path) -> None:
    vdir = tmp_path / "vec" / "yyyymm=202407"
    _write_vectors(vdir, VECS)
    _write_records(tmp_path / "records.jsonl", ANIMALS)
    records = make_pairs.load_records([str(tmp_path / "records.jsonl")])
    pairs, _ = make_pairs.build_pairs(tmp_path / "vec", records)
    pairs = [p for p in pairs if not p["duplicate"]]
    for p in pairs:
        for k in ("fallback_query", "fallback_target", "duplicate"):
            p[k] = str(p[k])
    ids, gallery = eval_recall.load_gallery(tmp_path / "vec")
    assignments_dir = tmp_path / "assignments"
    assignments_dir.mkdir()
    (assignments_dir / "part-m-00000").write_text("".join(f"{pid}\t{0 if pid[0] in 'AD' or pid == 'B_2' else 1}\n" for pid in ids), encoding="utf-8")
    centers_file = tmp_path / "centers.tsv"
    centers_file.write_text("0\t0.9,0.3,0.1\n1\t0.0,0.5,0.9\n", encoding="utf-8")
    center_ids, centers = eval_recall.load_centers(centers_file)
    probe = (eval_recall.load_assignments(assignments_dir), center_ids, centers, [1, 2])
    result = eval_recall.evaluate(ids, gallery, pairs, [1, 2], sample=None, seed=0, chunk=1, up_kind_of=None, probe=probe)
    assert result["index"]["clusters"] == 2 and result["index"]["assigned"] == 7 and result["index"]["cluster_size"]["max"] == 4
    assert result["probe"]["1"]["recall@2"] == 0.5 and result["probe"]["1"]["target_outside"] == 0.5
    assert result["probe"]["2"]["recall@2"] == 1.0 and result["probe"]["2"]["candidate_fraction"] == 1.0


def _write_snapshot(d: Path, ids: list[str], matrix: np.ndarray, dtype: str = "float32") -> None:
    d.mkdir(parents=True, exist_ok=True)
    name = f"vectors-{dtype}.npy"
    np.save(d / name, matrix.astype(dtype))
    (d / "ids.txt").write_text("".join(i + "\n" for i in ids), encoding="utf-8")
    (d / "meta.json").write_text(json.dumps({"dim": matrix.shape[1], "dtype": dtype,
                                             "vectors_file": name, "ids_file": "ids.txt"}), encoding="utf-8")


def test_assignment_files_order_without_duplicates(tmp_path: Path) -> None:
    a = tmp_path / "assignments"
    a.mkdir()
    for name in ("base.tsv", "part-00000.tsv", "part-m-00001", "shelter-daily-dt-2026-09-10.tsv"):
        (a / name).write_text("X_1" + chr(9) + "1" + chr(10), encoding="utf-8")
    files = [f.name for f in eval_recall.assignment_files(a)]
    assert files == ["base.tsv", "part-00000.tsv", "part-m-00001", "shelter-daily-dt-2026-09-10.tsv"]
    assert len(files) == len(set(files)), "part-00000.tsv 가 확장자 때문에 두 번 들어가면 안 된다"
    assert eval_recall.load_assignments(a) == {"X_1": 1}

    single = tmp_path / "one.tsv"
    single.write_text("Y_1" + chr(9) + "9" + chr(10), encoding="utf-8")
    assert eval_recall.assignment_files(single) == [single], "파일을 직접 가리키면 그것만"


def test_load_assignments_later_file_wins(tmp_path: Path) -> None:
    a = tmp_path / "assignments"
    a.mkdir()
    (a / "base.tsv").write_text("A_1" + chr(9) + "20" + chr(10) + "B_1" + chr(9) + "99" + chr(10), encoding="utf-8")
    (a / "shelter-daily-dt-2026-09-09.tsv").write_text("B_1" + chr(9) + "7" + chr(10), encoding="utf-8")
    assert eval_recall.load_assignments(a) == {"A_1": 20, "B_1": 7}, "일일 증분이 base 를 덮는다"


def test_load_snapshot_matches_tsv_gallery(tmp_path: Path) -> None:
    vdir = tmp_path / "vec" / "yyyymm=202407"
    _write_vectors(vdir, VECS)
    tsv_ids, tsv_gallery = eval_recall.load_gallery(tmp_path / "vec")

    snap = tmp_path / "snapshot"
    _write_snapshot(snap, tsv_ids, tsv_gallery)
    ids, gallery = eval_recall.load_snapshot(snap)
    assert ids == tsv_ids and gallery.shape == tsv_gallery.shape
    assert np.allclose(np.asarray(gallery), tsv_gallery, atol=1e-6), "스냅샷은 TSV 갤러리와 같은 값이어야 한다"


def test_load_snapshot_rejects_shape_and_norm_mismatch(tmp_path: Path) -> None:
    good = np.eye(3, dtype=np.float32)
    bad_shape = tmp_path / "bad-shape"
    _write_snapshot(bad_shape, ["A_1", "A_2"], good)  # ids 2개 vs 행 3개
    with pytest.raises(SystemExit) as error:
        eval_recall.load_snapshot(bad_shape)
    assert "형태" in str(error.value)

    unnormalized = tmp_path / "bad-norm"
    _write_snapshot(unnormalized, ["A_1", "A_2", "A_3"], good * 2.0)
    with pytest.raises(SystemExit) as error:
        eval_recall.load_snapshot(unnormalized)
    assert "정규화" in str(error.value), "정규화가 깨진 스냅샷은 조용히 고치지 않고 실패한다"


def test_load_snapshot_rejects_empty_snapshot(tmp_path: Path) -> None:
    # 빈 표본은 np.allclose 가 True 를 돌려주므로 정규화 검사를 지나간다 — 빈 갤러리가 조용히 통과하면 안 된다
    empty = tmp_path / "empty"
    _write_snapshot(empty, [], np.empty((0, 3), dtype=np.float32))
    with pytest.raises(SystemExit) as error:
        eval_recall.load_snapshot(empty)
    assert "벡터가 없습니다" in str(error.value)


def test_load_snapshot_promotes_float16_to_float32(tmp_path: Path, capsys) -> None:
    snap = tmp_path / "half"
    _write_snapshot(snap, ["A_1", "A_2"], np.eye(2, dtype=np.float32), dtype="float16")
    ids, gallery = eval_recall.load_snapshot(snap)
    assert ids == ["A_1", "A_2"] and gallery.dtype == np.float32
    assert "주의" not in capsys.readouterr().out, "mmap 이 아니면 경고할 것이 없다"

    eval_recall.load_snapshot(snap, mmap=True)  # 승격이 전량을 RAM 에 올려 mmap 이 무의미해진다
    assert "--mmap 무의미" in capsys.readouterr().out


def test_cluster_purity_by_kind(tmp_path: Path) -> None:
    import cluster_purity
    _write_records(tmp_path / "records.jsonl", ANIMALS)  # A·B·D 개(417000), C 고양이(422400)
    records = make_pairs.load_records([str(tmp_path / "records.jsonl")])
    assignments = {"A_1": 0, "A_2": 0, "D_1": 0, "B_1": 1, "C_1": 1, "C_2": 1, "X_1": 1}  # X 는 레코드 없음 → 제외
    label_of = {pid: records[pid.rpartition("_")[0]]["up_kind_cd"] for pid in assignments if pid != "X_1"}
    r = cluster_purity.purity(assignments, label_of)
    # 클러스터 0: 개 3 → 3, 클러스터 1: 개 1·고양이 2 → 2 ⇒ (3+2)/6
    assert r["n"] == 6 and r["purity"] == round(5 / 6, 4) and r["labels"] == 2 and r["majority_label_share"] == round(4 / 6, 4)
    assert cluster_purity.size_stats(assignments) == {"clusters": 2, "min": 3, "median": 4, "max": 4, "p90": 4, "total": 7}
    assert 0 < cluster_purity.random_purity(assignments, label_of, seed=0) <= 1.0
    # 레코드와 배정 ID 가 하나도 맞지 않으면 키는 그대로, 값은 0 — 출력부가 KeyError 로 죽지 않는다
    empty = cluster_purity.purity(assignments, {})
    assert empty["n"] == 0 and empty["purity"] == 0.0 and empty["labels"] == 0 and empty["top_labels"] == []
    assert cluster_purity.random_purity(assignments, {}, seed=0) == 0.0


def test_shelter_bias_with_single_query_returns_without_looping() -> None:
    gallery = np.eye(2, dtype=np.float32)
    pairs = [{"query_id": "A_1", "target_id": "A_2", "care_reg_no": "care1"}]
    import random
    assert eval_recall.shelter_bias(gallery, {"A_1": 0, "A_2": 1}, pairs, random.Random(0), 10) == {"n": 0}



class TestLoadFallback:
    """스냅샷의 탐지 폴백 플래그 — 없으면 None, 어긋나면 실패."""

    def snapshot(self, tmp_path, *, flags=None, count=None, ids=("a_1", "a_2")):
        d = tmp_path / "snapshot"
        d.mkdir()
        meta = {"dim": 2, "vectors_file": "v.npy", "ids_file": "ids.txt"}
        if flags is not None:
            np.save(d / "fallback.npy", np.array(flags, dtype=bool))
            meta["fallback_file"] = "fallback.npy"
            meta["fallback_count"] = int(np.array(flags, dtype=bool).sum()) if count is None else count
        (d / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
        (d / "ids.txt").write_text("\n".join(ids) + "\n", encoding="utf-8")
        return d, list(ids)

    def test_없으면_None(self, tmp_path):
        d, ids = self.snapshot(tmp_path)
        assert eval_recall.load_fallback(d, ids) is None

    def test_읽는다(self, tmp_path):
        d, ids = self.snapshot(tmp_path, flags=[True, False])
        flags = eval_recall.load_fallback(d, ids)
        assert flags.dtype == bool
        assert flags.tolist() == [True, False]

    def test_길이가_다르면_실패(self, tmp_path):
        d, _ = self.snapshot(tmp_path, flags=[True, False])
        with pytest.raises(SystemExit):
            eval_recall.load_fallback(d, ["a_1", "a_2", "a_3"])

    def test_meta_개수와_다르면_실패(self, tmp_path):
        d, ids = self.snapshot(tmp_path, flags=[True, False], count=2)
        with pytest.raises(SystemExit):
            eval_recall.load_fallback(d, ids)
