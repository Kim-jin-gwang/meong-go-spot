from pathlib import Path

import pytest

import breed_species

CSV = """version,breedName,species
kind-test,골든 리트리버,DOG
kind-test,믹스견,DOG
kind-test,토이 푸들,DOG
kind-test,스탠다드 푸들,DOG
kind-test,코리안 숏헤어,CAT
kind-test,고양이,CAT
kind-test,기타,CAT
"""


@pytest.fixture
def table(tmp_path: Path) -> dict[str, str]:
    path = tmp_path / "breed-species.csv"
    path.write_text(CSV, encoding="utf-8", newline="\n")
    return breed_species.load_table(path)


def test_exact_names_and_whitespace_variants_resolve(table: dict[str, str]) -> None:
    assert breed_species.species_of("믹스견", table) == "DOG"
    assert breed_species.species_of("  코리안   숏헤어 ", table) == "CAT"


def test_partial_name_resolves_when_all_matching_breeds_share_a_species(table: dict[str, str]) -> None:
    # 분실 API 는 "푸들" 이라고만 쓴다 — 사전에는 토이·스탠다드 푸들만 있다 (2026-09-15 실측 13건)
    assert breed_species.species_of("푸들", table) == "DOG"


def test_other_and_unknown(table: dict[str, str]) -> None:
    assert breed_species.species_of("기타축종", table) == "OTHER"
    # "기타" 는 고양이 사전에도 이름으로 있지만 서비스 범위 밖으로 다룬다 — 사전보다 OTHER 규칙이 먼저다
    assert breed_species.species_of("기타", table) == "OTHER"
    assert breed_species.species_of("", table) is None
    assert breed_species.species_of(None, table) is None
    assert breed_species.species_of("이구아나", table) is None


def test_ambiguous_partial_name_is_not_guessed(tmp_path: Path) -> None:
    path = tmp_path / "b.csv"
    path.write_text("version,breedName,species\nv,털 개\n".replace("털 개\n", "털 개,DOG\nv,털 고양이,CAT\n"), encoding="utf-8")
    table = breed_species.load_table(path)
    assert breed_species.species_of("털", table) is None


def test_load_table_rejects_bad_header_and_conflicts(tmp_path: Path) -> None:
    bad = tmp_path / "bad.csv"
    bad.write_text("breed,species\n푸들,DOG\n", encoding="utf-8")
    with pytest.raises(ValueError, match="헤더"):
        breed_species.load_table(bad)
    conflict = tmp_path / "conflict.csv"
    conflict.write_text("version,breedName,species\nv,기타,DOG\nv,기타,CAT\n", encoding="utf-8")
    with pytest.raises(ValueError, match="두 축종"):
        breed_species.load_table(conflict)
