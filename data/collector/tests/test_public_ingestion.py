import datetime as dt
import hashlib
from pathlib import Path

import pytest

import public_ingestion as ingestion


CSV = """version,regionCode,emdCode,publicLocation,active
v1,11680,,서울특별시 강남구,true
v1,11680,1168010100,서울특별시 강남구 역삼동,true
v1,11710,,서울특별시 송파구,true
"""
NOW = dt.datetime(2026, 9, 10, tzinfo=dt.timezone.utc)


def catalog(tmp_path: Path) -> ingestion.RegionCatalog:
    path = tmp_path / "regions.csv"
    path.write_text(CSV, encoding="utf-8")
    return ingestion.RegionCatalog.from_csv(path, "v1", hashlib.sha256(path.read_bytes()).hexdigest())


def record(**changes: object) -> dict:
    result = {
        "desertionNo": "D-1", "careRegNo": "S-1", "careNm": "테스트 보호소", "careAddr": "서울특별시 강남구 역삼동 1",
        "happenPlace": "서울특별시 강남구 역삼동 공원", "happenDt": "20260901", "noticeSdt": "20260902",
        "processState": "보호중", "upKindNm": "개", "sexCd": "M", "neuterYn": "Y",
        "popfile1": "https://animal.example/images/1.jpg", "updTm": "2026-09-10 10:00:00",
    }
    result.update(changes)
    return result


def test_catalog_uses_longest_active_location_and_rejects_unmapped(tmp_path: Path) -> None:
    regions = catalog(tmp_path)
    assert regions.resolve("서울특별시 강남구 역삼동 1").emd_code == "1168010100"
    with pytest.raises(ingestion.RecordError, match="LOCATION_NOT_MAPPED"):
        regions.resolve("알 수 없는 지역")


def test_normalize_maps_contract_fields_and_preserves_public_photo(tmp_path: Path) -> None:
    normalized = ingestion.normalize(record(), catalog(tmp_path), NOW)
    assert (normalized.species, normalized.sex, normalized.neuter_status, normalized.status) == ("DOG", "MALE", "YES", "ACTIVE")
    assert normalized.event_location.public_location == "서울특별시 강남구 역삼동"
    assert normalized.listed_at == dt.datetime(2026, 9, 1, 15, tzinfo=dt.timezone.utc)
    assert normalized.photo_urls == ("https://animal.example/images/1.jpg",)


@pytest.mark.parametrize(
    ("process_state", "expected"),
    [
        ("보호중", "ACTIVE"),
        ("공고중", "ACTIVE"),
        ("종료(입양)", "CLOSED"),
        ("보호종료", "CLOSED"),
        ("공고종료", "CLOSED"),
        ("확인중", "CLOSED"),
        (None, "CLOSED"),
    ],
)
def test_normalize_activates_only_known_live_process_states(
    tmp_path: Path, process_state: str | None, expected: str
) -> None:
    normalized = ingestion.normalize(
        record(processState=process_state), catalog(tmp_path), NOW
    )

    assert normalized.status == expected


def test_normalize_interprets_timezone_less_source_update_as_kst(tmp_path: Path) -> None:
    normalized = ingestion.normalize(record(updTm="2026-09-10 10:00:00"), catalog(tmp_path), NOW)
    assert normalized.source_updated_at == dt.datetime(2026, 9, 10, 1, tzinfo=dt.timezone.utc)


def test_v1_keeps_photo_upsert_constraint() -> None:
    migration = Path(__file__).parents[3] / "backend/src/main/resources/db/migration/V1__initial_schema.sql"
    assert "uk_animal_photo_case_sort_order UNIQUE (animal_case_id, sort_order)" in migration.read_text(encoding="utf-8")


class RecordingCursor:
    def __init__(self) -> None:
        self.executions: list[tuple[str, tuple[object, ...]]] = []
        self._results = iter(((42, dt.datetime(2026, 9, 9, tzinfo=dt.timezone.utc)), (9,)))

    def execute(self, query: str, values: tuple[object, ...]) -> None:
        self.executions.append((query, values))

    def fetchone(self) -> tuple[object, ...]:
        return next(self._results)


def test_upsert_closed_case_retains_first_closed_at(tmp_path: Path) -> None:
    cursor = RecordingCursor()
    closed = ingestion.normalize(record(processState="종료"), catalog(tmp_path), NOW)

    result, shelter_id = ingestion._upsert_record(cursor, closed, 3, NOW)

    update_query, update_values = next(
        (query, values) for query, values in cursor.executions if "UPDATE animal_case SET" in query
    )
    assert result == "UPDATED"
    assert shelter_id is None
    assert "COALESCE(closed_at,%s)" in update_query
    assert update_values[-4:] == ("CLOSED", NOW, NOW, 42)


@pytest.mark.parametrize("changes,error", [
    ({"popfile1": "file:///private.jpg"}, "MISSING_PUBLIC_PHOTO"),
    ({"noticeSdt": "20260903", "noticeEdt": "20260902"}, "INVALID_NOTICE_DATES"),
    # 세 후보(orgNm·happenPlace·careAddr) 모두 못 풀 때만 거부한다 — 추측 저장 금지는 그대로다
    ({"happenPlace": "알 수 없는 지역", "careAddr": "어디인지 모름", "orgNm": None}, "LOCATION_NOT_MAPPED"),
])
def test_normalize_rejects_invalid_or_unmappable_records(tmp_path: Path, changes: dict, error: str) -> None:
    with pytest.raises(ingestion.RecordError, match=error):
        ingestion.normalize(record(**changes), catalog(tmp_path), NOW)


class TestPublicLocationResolution:
    """공공 레코드 지역 해석 — 2026-09-14 실측: happenPlace 만 보던 이전 규칙은 97% 를 버렸다."""

    def test_발견_지역은_orgNm_을_먼저_본다(self, tmp_path: Path) -> None:
        # 실제 happenPlace 는 "봉정삼거리", "○○아파트 앞" 같은 자유 지명이라 시군구가 없다
        normalized = ingestion.normalize(
            record(happenPlace="봉정삼거리 앞 도로", orgNm="서울특별시 송파구"), catalog(tmp_path), NOW)
        assert normalized.event_location.region_code == "11710"
        assert normalized.current_location.public_location == "서울특별시 강남구 역삼동", "CURRENT 는 careAddr"

    def test_orgNm_이_시도명뿐이면_happenPlace_careAddr_로_내려간다(self, tmp_path: Path) -> None:
        # 제주특별자치도는 관할이 도 단위라 orgNm 에 시군구가 없다
        normalized = ingestion.normalize(
            record(orgNm="서울특별시", happenPlace="어딘가 공원", careAddr="서울특별시 송파구 어딘가 1"),
            catalog(tmp_path), NOW)
        assert normalized.event_location.region_code == "11710", "happenPlace 도 못 풀면 careAddr"

    def test_보호_지역은_careAddr_가_안_풀리면_orgNm_으로_내려간다(self, tmp_path: Path) -> None:
        normalized = ingestion.normalize(
            record(careAddr="개편 전 이름으로 적힌 주소 12", orgNm="서울특별시 송파구"), catalog(tmp_path), NOW)
        assert normalized.current_location.region_code == "11710"

    def test_별칭은_현재_코드와_표시명으로_풀린다(self, tmp_path: Path) -> None:
        regions = catalog_with_aliases(tmp_path, "v1,옛서울 송파구,서울특별시 송파구\n")
        location = regions.resolve("옛서울 송파구 어딘가 1")
        assert (location.region_code, location.public_location) == ("11710", "서울특별시 송파구"), "저장값은 항상 현재명"

    def test_별칭과_본명이_같은_곳을_가리키면_모호하지_않다(self, tmp_path: Path) -> None:
        regions = catalog_with_aliases(tmp_path, "v1,옛서울 송파구,서울특별시 송파구\n")
        assert regions.resolve("옛서울 송파구 (서울특별시 송파구)").region_code == "11710"

    def test_동률_후보가_셋_이상이고_그중_다른_지역이_있으면_모호하다(self, tmp_path: Path) -> None:
        # MR !164 리뷰 지적: 앞 두 후보만 비교하면 [본명 A, 별칭 A, 다른 지역 B] 에서 B 를 놓친다.
        # 공백을 뺀 길이가 모두 8글자가 되도록 별칭 이름을 고른다 (서울특별시송파구 / 서울특별시강남구 / 옛서울특별송파구).
        regions = catalog_with_aliases(tmp_path, "v1,옛서울특별 송파구,서울특별시 송파구\n")
        with pytest.raises(ingestion.RecordError, match="LOCATION_AMBIGUOUS"):
            regions.resolve("서울특별시 송파구 · 옛서울특별 송파구 · 서울특별시 강남구 세 곳이 한 주소에")
        # 같은 셋 중 다른 지역(강남구)이 빠지면 본명·별칭만 남아 모호하지 않다
        assert regions.resolve("서울특별시 송파구 · 옛서울특별 송파구").region_code == "11710"

    def test_별칭이_없는_지역을_가리키면_기동을_거부한다(self, tmp_path: Path) -> None:
        with pytest.raises(ValueError, match="기준 데이터에 없습니다"):
            catalog_with_aliases(tmp_path, "v1,옛서울 마포구,서울특별시 마포구\n")

    def test_다른_version_별칭은_무시하고_checksum_은_검사한다(self, tmp_path: Path) -> None:
        regions = catalog_with_aliases(tmp_path, "v0,옛서울 송파구,서울특별시 송파구\n")
        with pytest.raises(ingestion.RecordError, match="LOCATION_NOT_MAPPED"):
            regions.resolve("옛서울 송파구 1")
        path = tmp_path / "aliases.csv"
        with pytest.raises(ValueError, match="checksum"):
            ingestion.RegionCatalog.from_csv(tmp_path / "regions.csv", "v1", hashlib.sha256((tmp_path / "regions.csv").read_bytes()).hexdigest(),
                                             aliases_path=path, aliases_checksum="0" * 64)


def catalog_with_aliases(tmp_path: Path, alias_rows: str) -> ingestion.RegionCatalog:
    regions_path = tmp_path / "regions.csv"
    regions_path.write_text(CSV, encoding="utf-8")
    aliases_path = tmp_path / "aliases.csv"
    aliases_path.write_text("version,alias,publicLocation\n" + alias_rows, encoding="utf-8")
    return ingestion.RegionCatalog.from_csv(
        regions_path, "v1", hashlib.sha256(regions_path.read_bytes()).hexdigest(),
        aliases_path=aliases_path, aliases_checksum=hashlib.sha256(aliases_path.read_bytes()).hexdigest())


def test_normalize_stores_photo_urls_as_https(tmp_path: Path) -> None:
    """평문으로 저장하면 Android(targetSdk 28+)에서 썸네일이 반드시 깨진다."""
    normalized = ingestion.normalize(
        record(popfile1="http://openapi.animal.go.kr/img/1.jpg",
               popfile2="https://openapi.animal.go.kr/img/2.jpg"),
        catalog(tmp_path), NOW)
    assert normalized.photo_urls == (
        "https://openapi.animal.go.kr/img/1.jpg",
        "https://openapi.animal.go.kr/img/2.jpg",
    )


def test_normalize_upgrades_only_the_scheme(tmp_path: Path) -> None:
    """경로·질의에 들어 있는 http 문자열은 건드리지 않는다."""
    normalized = ingestion.normalize(
        record(popfile1="http://h/redirect?to=http://inner/a.jpg"), catalog(tmp_path), NOW)
    assert normalized.photo_urls == ("https://h/redirect?to=http://inner/a.jpg",)


@pytest.mark.parametrize("kind", ["기타", "[기타축종] 토끼", "닭"])
def test_normalize_rejects_other_species(tmp_path: Path, kind: str) -> None:
    """서비스는 개·고양이만 다룬다 (2026-09-14 결정). 기타 축종은 조회에서 가리지 않고 적재에서 뺀다."""
    with pytest.raises(ingestion.RecordError, match="SPECIES_NOT_SUPPORTED"):
        ingestion.normalize(record(upKindNm=kind), catalog(tmp_path), NOW)


def test_normalize_keeps_dogs_and_cats(tmp_path: Path) -> None:
    assert ingestion.normalize(record(upKindNm="개"), catalog(tmp_path), NOW).species == "DOG"
    assert ingestion.normalize(record(upKindNm="고양이"), catalog(tmp_path), NOW).species == "CAT"


def test_normalize_reads_species_from_the_public_code_when_the_name_is_missing(tmp_path: Path) -> None:
    """upKindNm 없이 upKindCd 만 오는 레코드도 개·고양이를 알아본다 (AI 리뷰 !166 지적 — 이름만 보면 코드는 전부 거부됐다)."""
    assert ingestion.normalize(record(upKindNm=None, upKindCd="417000"), catalog(tmp_path), NOW).species == "DOG"
    assert ingestion.normalize(record(upKindNm=None, upKindCd="422400"), catalog(tmp_path), NOW).species == "CAT"
    with pytest.raises(ingestion.RecordError, match="SPECIES_NOT_SUPPORTED"):
        ingestion.normalize(record(upKindNm=None, upKindCd="429900"), catalog(tmp_path), NOW)


def test_normalize_percent_encodes_brackets_in_photo_urls(tmp_path: Path) -> None:
    """공공 API 파일명의 ~9% 가 `…518[1].jpg` 다. 대괄호는 URI 경로에 허용되지 않아 백엔드 java.net.URI 가
    거부하고 썸네일을 null 로 내렸다 (2026-09-14, ACTIVE 452건). 파일 서버는 %5B%5D 로도 같은 파일을 준다."""
    normalized = ingestion.normalize(
        record(popfile1="http://openapi.animal.go.kr/files/shelter/2026/09/202609111309518[1].jpg"), catalog(tmp_path), NOW)
    assert normalized.photo_urls == ("https://openapi.animal.go.kr/files/shelter/2026/09/202609111309518%5B1%5D.jpg",)


BACKEND_STATUS_SERVICE = (Path(__file__).resolve().parents[3] / "backend" / "src" / "main" / "java" / "com" / "meonggo"
                          / "backend" / "ingestion" / "service" / "IngestionStatusService.java")


@pytest.mark.skipif(not BACKEND_STATUS_SERVICE.exists(),
                    reason="모노레포 밖(적재기만 있는 환경)에서는 백엔드 소스와 대조할 수 없다")
def test_source_system_matches_the_backend_daily_summary_lookup() -> None:
    """D1/D2 는 ingestion_run.source_system 을 문자열 그대로 비교한다. 적재기가 다른 표기로 적으면 홈의 일일 요약이
    영원히 null 이다 (2026-09-11~15 실제 발생: "animal-protection-api" vs "ANIMAL_PROTECTION_API").
    백엔드는 보호동물 적재(ANIMAL_PROTECTION_API)만 조회한다 — 분실 적재(LOSS_INFO_API)는 대조 대상이 아니다."""
    service = BACKEND_STATUS_SERVICE.read_text(encoding="utf-8")
    assert f'SOURCE_SYSTEM = "{ingestion.SOURCE_SYSTEM}"' in service
