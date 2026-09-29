import io
import json
import sys
import tarfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_dataset  # noqa: E402
import extract_images  # noqa: E402
import make_dataset  # noqa: E402
import make_pairs  # noqa: E402

# 3차원 단위 벡터 — 개 A·B(같은 품종·색)·C, 고양이 D, 혼동 후보 E(고양이)·F(개)는 짝 없는 단독 사진
VECS = {
    "A_1": [1.0, 0.0, 0.0], "A_2": [0.98, 0.2, 0.0],
    "B_1": [0.8, 0.6, 0.0], "B_2": [0.6, 0.8, 0.0],
    "C_1": [0.0, 1.0, 0.0], "C_2": [0.31, 0.95, 0.0],
    "D_1": [0.0, 0.0, 1.0], "D_2": [0.0, 0.6, 0.8],
    "E_1": [0.0, 0.1, 0.995],
    "F_1": [0.995, 0.1, 0.0],
}
RECORDS = {
    "A": {"upKindCd": "417000", "upKindNm": "개", "kindCd": "000114", "kindNm": "믹스견", "colorCd": "흰색", "sexCd": "M"},
    "B": {"upKindCd": "417000", "upKindNm": "개", "kindCd": "000114", "kindNm": "믹스견", "colorCd": "흰색", "sexCd": "F"},
    "C": {"upKindCd": "417000", "upKindNm": "개", "kindCd": "000115", "kindNm": "푸들", "colorCd": "검정", "sexCd": "M"},
    "D": {"upKindCd": "422400", "upKindNm": "고양이", "kindCd": "000200", "kindNm": "한국 고양이", "colorCd": "치즈", "sexCd": "F"},
    "E": {"upKindCd": "422400", "upKindNm": "고양이", "kindCd": "000200", "kindNm": "한국 고양이", "colorCd": "치즈", "sexCd": "M"},
    "F": {"upKindCd": "417000", "upKindNm": "개", "kindCd": "000114", "kindNm": "믹스견", "colorCd": "흰색", "sexCd": "M"},
}


def _build_inputs(tmp_path: Path) -> tuple[Path, Path, Path]:
    vdir = tmp_path / "vec" / "yyyymm=202401"
    vdir.mkdir(parents=True, exist_ok=True)
    with (vdir / "vectors-images-202401-0000.tsv").open("w", encoding="utf-8", newline="\n") as fp:
        for pid, v in VECS.items():
            arr = np.array(v) / np.linalg.norm(v)
            fp.write(pid + "\t" + ",".join(f"{x:.6f}" for x in arr) + "\n")
    with (vdir / "detections-images-202401-0000.jsonl").open("w", encoding="utf-8", newline="\n") as fp:
        for pid in VECS:
            fp.write(json.dumps({"photo_id": pid, "fallback": pid == "C_2"}) + "\n")
    records = tmp_path / "records.jsonl"
    with records.open("w", encoding="utf-8", newline="\n") as fp:
        for no, r in RECORDS.items():
            fp.write(json.dumps({"desertionNo": no, **r}, ensure_ascii=False) + "\n")
    pairs, _ = make_pairs.build_pairs(tmp_path / "vec", make_pairs.load_records([str(records)]))
    pairs_path = tmp_path / "pairs.tsv"
    make_pairs.write_pairs(pairs, pairs_path)
    return tmp_path / "vec", records, pairs_path


def _run_make_dataset(tmp_path: Path, out_dir: Path, monkeypatch) -> None:
    vdir, records, pairs_path = _build_inputs(tmp_path)
    monkeypatch.setattr(sys, "argv", [
        "make_dataset.py", "--pairs", str(pairs_path), "--vectors-dir", str(vdir),
        "--records", str(records), "--out-dir", str(out_dir),
        "--pool-months", "202401", "--dogs", "3", "--cats", "1", "--others", "0",
        "--breed-cap", "2", "--hard-per-query", "1", "--attr-negatives", "5", "--easy-negatives", "3"])
    assert make_dataset.main() == 0


def test_make_dataset_outputs_are_consistent(tmp_path: Path, monkeypatch) -> None:
    out = tmp_path / "ds"
    _run_make_dataset(tmp_path, out, monkeypatch)
    manifest = check_dataset.load_manifest(out)
    rows = check_dataset.load_pairs(out)
    # 양성: 개 3(A·B·C) + 고양이 1(D) — 모두 같은 개체의 사진 1·2
    pos = [r for r in rows if r["pair_type"] == "pos"]
    assert {r["query_group"] for r in pos} == {"A", "B", "C", "D"}
    assert all(r["label"] == "1" and r["query_group"] == r["target_group"] for r in pos)
    # 음성은 전부 다른 개체, 채굴 음성은 같은 축종
    negs = [r for r in rows if r["label"] == "0"]
    assert negs and all(r["query_group"] != r["target_group"] for r in negs)
    mined = [r for r in rows if r["pair_type"] == "neg_hard_mined"]
    assert mined and all(r["query_species"] == r["target_species"] for r in mined)
    # A_1의 최고 코사인 혼동 상대는 F_1 (cos 0.995) — 짝 없는 단독 사진도 갤러리로 쓰인다
    assert any(r["query_id"] == "A_1" and r["target_id"] == "F_1" for r in mined)
    # 같은 품종·색 음성 (A×B: 000114·흰색)
    attr = [r for r in rows if r["pair_type"] == "neg_hard_attr"]
    assert attr and all({r["query_group"], r["target_group"]} <= {"A", "B", "F"} for r in attr)
    # 쉬운 음성은 축종이 다르다
    easy = [r for r in rows if r["pair_type"] == "neg_easy"]
    assert easy and all(r["query_species"] != r["target_species"] for r in easy)
    # manifest: pairs에 등장하는 모든 사진 + 폴백 플래그는 detections에서 온다
    assert {r[k] for r in rows for k in ("query_id", "target_id")} == set(manifest)
    assert manifest["C_2"]["fallback"] is True and manifest["A_1"]["fallback"] is False
    assert manifest["A_1"]["hdfs_tar"].endswith("yyyymm=202401/images-202401-0000.tar")
    for name in ("dataset.json", "stats.json", "extract-list.tsv"):
        assert (out / name).exists()
    header = json.loads((out / "dataset.json").read_text(encoding="utf-8"))
    assert header["model"]["model_version"] == "v2" and header["seed"] == 20260908


def test_make_dataset_is_deterministic(tmp_path: Path, monkeypatch) -> None:
    out1, out2 = tmp_path / "ds1", tmp_path / "ds2"
    _run_make_dataset(tmp_path, out1, monkeypatch)
    _run_make_dataset(tmp_path, out2, monkeypatch)
    assert (out1 / "pairs.tsv").read_bytes() == (out2 / "pairs.tsv").read_bytes()
    assert (out1 / "manifest.jsonl").read_bytes() == (out2 / "manifest.jsonl").read_bytes()


def test_breed_cap_limits_first_pass(tmp_path: Path, monkeypatch) -> None:
    vdir, records, pairs_path = _build_inputs(tmp_path)
    monkeypatch.setattr(sys, "argv", [
        "make_dataset.py", "--pairs", str(pairs_path), "--vectors-dir", str(vdir),
        "--records", str(records), "--out-dir", str(tmp_path / "ds"),
        "--pool-months", "202401", "--dogs", "2", "--cats", "0", "--others", "0",
        "--breed-cap", "1", "--hard-per-query", "0", "--attr-negatives", "0", "--easy-negatives", "0"])
    assert make_dataset.main() == 0
    rows = check_dataset.load_pairs(tmp_path / "ds")
    kinds = [r["query_kind_nm"] for r in rows if r["pair_type"] == "pos"]
    assert len(kinds) == 2 and len(set(kinds)) == 2, "품종 캡 1이면 개 2마리는 서로 다른 품종이어야 한다"


def test_check_dataset_detects_missing_image_and_label_mismatch(tmp_path: Path, monkeypatch) -> None:
    out = tmp_path / "ds"
    _run_make_dataset(tmp_path, out, monkeypatch)
    images = tmp_path / "images"
    images.mkdir()
    manifest = check_dataset.load_manifest(out)
    for pid in manifest:
        (images / f"{pid}.jpg").write_bytes(b"\xff\xd8fake")
    errors, stats = check_dataset.check(out, images)
    assert not errors and stats["pos"] + stats["neg"] == len(check_dataset.load_pairs(out))
    (images / "A_1.jpg").unlink()
    errors, _ = check_dataset.check(out, images)
    assert any("이미지 없음" in e for e in errors)
    # label 뒤집힘 감지
    text = (out / "pairs.tsv").read_text(encoding="utf-8").splitlines()
    header, first = text[0].split("\t"), text[1].split("\t")
    first[header.index("label")] = "0"
    (out / "pairs.tsv").write_text("\n".join([text[0], "\t".join(first)] + text[2:]) + "\n", encoding="utf-8")
    errors, _ = check_dataset.check(out, None)
    assert any("음성인데 같은 개체" in e for e in errors)


def test_check_tolerates_null_tags_and_missing_images_dir(tmp_path: Path, monkeypatch) -> None:
    out = tmp_path / "ds"
    _run_make_dataset(tmp_path, out, monkeypatch)
    # tags: null 레코드가 있어도 예외 없이 통계가 나와야 한다
    entries = [json.loads(l) for l in (out / "manifest.jsonl").read_text(encoding="utf-8").splitlines() if l]
    entries[0]["tags"] = None
    with (out / "manifest.jsonl").open("w", encoding="utf-8", newline="\n") as fp:
        for e in entries:
            fp.write(json.dumps(e, ensure_ascii=False) + "\n")
    errors, stats = check_dataset.check(out, None)
    assert not errors and stats["tagged"] == 0
    # 존재하지 않는 이미지 디렉터리는 스택 트레이스 대신 오류 항목으로 보고한다
    errors, _ = check_dataset.check(out, tmp_path / "no-such-dir")
    assert any("이미지 디렉터리 없음" in e for e in errors)


def test_merge_tags_updates_manifest(tmp_path: Path, monkeypatch) -> None:
    out = tmp_path / "ds"
    _run_make_dataset(tmp_path, out, monkeypatch)
    tags = tmp_path / "tags.tsv"
    tags.write_text("photo_id\tangle\tbackground\tquality\nA_1\tfront\tcage\tgood\n", encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["make_dataset.py", "--out-dir", str(out), "--merge-tags", str(tags)])
    assert make_dataset.main() == 0
    manifest = check_dataset.load_manifest(out)
    assert manifest["A_1"]["tags"] == {"angle": "front", "background": "cage", "quality": "good"}
    assert manifest["A_2"]["tags"]["angle"] is None


def test_extract_images_streams_only_wanted_entries(tmp_path: Path) -> None:
    tar_path = tmp_path / "images-202401-0000.tar"
    with tarfile.open(tar_path, "w") as tar:
        for name, payload in (("A_1.jpg", b"a1"), ("A_2.png", b"a2"), ("B_1.jpg", b"b1")):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
    out = tmp_path / "images"
    out.mkdir()
    cat = [sys.executable, "-c",
           "import sys,shutil; shutil.copyfileobj(open(sys.argv[1],'rb'), sys.stdout.buffer)"]
    saved = extract_images.extract_tar(cat, str(tar_path), {"A_1", "A_2"}, out)
    assert sorted(saved) == ["A_1", "A_2"]
    assert (out / "A_1.jpg").read_bytes() == b"a1" and (out / "A_2.png").read_bytes() == b"a2"
    assert not (out / "B_1.jpg").exists()
