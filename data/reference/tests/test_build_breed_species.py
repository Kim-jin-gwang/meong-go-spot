import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_breed_species as bbs  # noqa: E402


def payload(names: list[str], code: str = "00") -> dict:
    return {"response": {"header": {"resultCode": code}, "body": {"items": {"item": [{"kindNm": n} for n in names]}}}}


def test_kinds_from_payload_cleans_whitespace_and_handles_single_item() -> None:
    assert bbs.kinds_from_payload(payload([" 골든  리트리버 ", "믹스견"])) == ["골든 리트리버", "믹스견"]
    single = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": {"kindNm": "고양이"}}}}}
    assert bbs.kinds_from_payload(single) == ["고양이"]
    with pytest.raises(SystemExit):
        bbs.kinds_from_payload(payload(["x"], code="30"))


def test_build_rows_sorts_by_species_then_name_and_refuses_cross_species_duplicates() -> None:
    rows = bbs.build_rows("v1", {"CAT": ["코리안 숏헤어", "고양이"], "DOG": ["믹스견", "골든 리트리버"]})
    assert rows == [("v1", "고양이", "CAT"), ("v1", "코리안 숏헤어", "CAT"), ("v1", "골든 리트리버", "DOG"), ("v1", "믹스견", "DOG")]
    # "기타" 는 두 사전에 다 있다 — 축종을 말하지 않는 이름이라 사전에서 빠지고, 진짜 품종이 겹치면 실패한다
    assert bbs.build_rows("v1", {"CAT": ["기타", "고양이"], "DOG": ["기타"]}) == [("v1", "고양이", "CAT")]
    with pytest.raises(SystemExit, match="양쪽"):
        bbs.build_rows("v1", {"CAT": ["믹스"], "DOG": ["믹스"]})


def test_write_csv_round_trips_through_the_collector_loader(tmp_path: Path) -> None:
    out = tmp_path / "breed-species.csv"
    bbs.write_csv([("v1", "믹스견", "DOG"), ("v1", "고양이", "CAT")], out)
    assert out.read_text(encoding="utf-8") == "version,breedName,species\nv1,믹스견,DOG\nv1,고양이,CAT\n"
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "collector"))
    import breed_species  # noqa: PLC0415

    assert breed_species.load_table(out) == {"믹스견": "DOG", "고양이": "CAT"}
