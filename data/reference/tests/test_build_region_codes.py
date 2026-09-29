"""법정동코드 변환기 검증.

원본 파일 없이 돈다. 실제 자료로 만든 파일은 verify_region_codes.py 가 따로 검사하고
그 실행 기록은 docs/deploy-guide.md 에 남긴다.
"""

import hashlib
from pathlib import Path

import pytest

import build_region_codes as build


def source(tmp_path: Path, rows: list[tuple[str, str, str]]) -> Path:
    lines = ["법정동코드\t법정동명\t폐지여부"]
    lines += ["\t".join(row) for row in rows]
    path = tmp_path / "src.txt"
    # 원본은 CP949 다. 변환기가 그 인코딩으로 읽는지도 함께 확인한다.
    path.write_bytes(("\n".join(lines) + "\n").encode("cp949"))
    return path


def run(tmp_path: Path, rows: list[tuple[str, str, str]], *extra: str) -> str:
    out = tmp_path / "out.csv"
    argv = [str(source(tmp_path, rows)), "--version", "v1", "--out", str(out), *extra]
    build.sys.argv = ["build_region_codes.py", *argv]
    assert build.main() == 0
    return out.read_bytes().decode("utf-8")


class TestLevel:
    def test_시군구만_고른다(self):
        assert build.is_district("1111000000")
        assert not build.is_district("1100000000"), "시도 레벨"
        assert not build.is_district("1111010100"), "읍면동 레벨"
        assert not build.is_district("1111010101"), "리 레벨"

    def test_시_아래_구도_시군구다(self):
        # 수원시(41110)와 장안구(41111)가 둘 다 시군구 레벨 코드다.
        assert build.is_district("4111000000")
        assert build.is_district("4111100000")

    def test_형식이_아니면_거부(self):
        assert not build.is_district("11110")
        assert not build.is_district("11110000ab")


class TestDisplay:
    @pytest.mark.parametrize("bad", ["", " 서울", "서울 ", '서울"시', "서울​시", "가" * 201])
    def test_규칙_위반을_잡는다(self, bad):
        assert not build.valid_display(bad)

    def test_정상값(self):
        assert build.valid_display("경기도 수원시 장안구")
        assert build.valid_display("가" * 200)


class TestOutput:
    def test_활성만_담고_형식을_지킨다(self, tmp_path):
        text = run(tmp_path, [
            ("1100000000", "서울특별시", "존재"),
            ("1111000000", "서울특별시 종로구", "존재"),
            ("1111010100", "서울특별시 종로구 청운동", "존재"),
            ("2911000000", "광주광역시 동구", "폐지"),
        ])
        assert text.startswith(build.HEADER + "\n")
        assert text == build.HEADER + "\nv1,11110,,서울특별시 종로구,true\n"
        assert "\r" not in text
        assert not text.encode("utf-8").startswith(b"\xef\xbb\xbf")

    def test_폐지도_담을_수_있다(self, tmp_path):
        text = run(tmp_path, [
            ("1111000000", "서울특별시 종로구", "존재"),
            ("2911000000", "광주광역시 동구", "폐지"),
        ], "--include-abolished")
        assert "v1,11110,,서울특별시 종로구,true\n" in text
        assert "v1,29110,,광주광역시 동구,false\n" in text

    def test_코드순_정렬(self, tmp_path):
        text = run(tmp_path, [
            ("2611000000", "부산광역시 중구", "존재"),
            ("1111000000", "서울특별시 종로구", "존재"),
        ])
        assert text.index("11110") < text.index("26110")

    def test_중복_코드는_실패(self, tmp_path):
        # 같은 5자리로 접히는 서로 다른 10자리는 존재할 수 없지만, 원본이 깨졌을 때 조용히
        # 덮어쓰지 않고 멈추는지 확인한다.
        with pytest.raises(SystemExit):
            run(tmp_path, [
                ("1111000000", "서울특별시 종로구", "존재"),
                ("1111000000", "서울특별시 종로구(중복)", "존재"),
            ])

    def test_쉼표가_든_이름은_실패(self, tmp_path):
        with pytest.raises(SystemExit):
            run(tmp_path, [("1111000000", "서울특별시 종로구,중구", "존재")])

    def test_헤더가_다르면_실패(self, tmp_path):
        path = tmp_path / "src.txt"
        path.write_bytes("엉뚱한헤더\t이름\t상태\n1111000000\t종로구\t존재\n".encode("cp949"))
        build.sys.argv = ["x", str(path), "--version", "v1", "--out", str(tmp_path / "o.csv")]
        with pytest.raises(SystemExit):
            build.main()

    def test_출력_체크섬이_파일_바이트와_일치(self, tmp_path):
        out = tmp_path / "out.csv"
        build.sys.argv = ["x", str(source(tmp_path, [("1111000000", "서울특별시 종로구", "존재")])),
                          "--version", "v1", "--out", str(out)]
        assert build.main() == 0
        raw = out.read_bytes()
        assert hashlib.sha256(raw).hexdigest() == hashlib.sha256(
            out.read_text(encoding="utf-8").encode("utf-8")).hexdigest()


class TestAliases:
    """폐지 시군구 이름 → 현재 시군구 별칭. 적재기가 개편 전 이름의 보호소 주소를 풀기 위한 재료다."""

    ALIVE = {"강원특별자치도 화천군", "전남광주통합특별시 북구", "경기도 광주시", "인천광역시 강화군", "서울특별시 중구"}

    def test_시도_개편은_별칭이_된다(self):
        aliases = build.build_aliases([
            ("4272000000", "강원도 화천군", "폐지"),
            ("2917000000", "광주광역시 북구", "폐지"),
        ], self.ALIVE)
        assert aliases == {"강원도 화천군": "강원특별자치도 화천군", "광주광역시 북구": "전남광주통합특별시 북구"}

    def test_같은_이름이_하나뿐이어도_시도_개편이_아니면_넣지_않는다(self):
        # 2026-09-14 실제 사고: "같은 이름 하나뿐" 규칙이 옛 광주(전라남도 광주시)를 경기도 광주시로 묶었다.
        aliases = build.build_aliases([
            ("4610000000", "전라남도 광주시", "폐지"),   # 전라남도→전남광주통합특별시 개편인데 그 아래 광주시가 없다
            ("4133000000", "경기도 강화군", "폐지"),     # 시도 간 이관 — 개편표에 없다
        ], self.ALIVE)
        assert aliases == {}

    def test_현재도_존재하는_이름과_존재_행은_건너뛴다(self):
        aliases = build.build_aliases([
            ("1114000000", "서울특별시 중구", "존재"),
            ("2611000000", "부산직할시 중구", "폐지"),   # 부산광역시 중구가 ALIVE 에 없으니 안 넣는다
        ], self.ALIVE)
        assert aliases == {}

    def test_출력_파일_형식(self, tmp_path):
        out, aliases_out = tmp_path / "out.csv", tmp_path / "aliases.csv"
        build.sys.argv = ["x", str(source(tmp_path, [
            ("4272000000", "강원특별자치도 화천군", "존재"),
            ("4272000000", "강원도 화천군", "폐지"),
        ])), "--version", "v1", "--out", str(out), "--aliases-out", str(aliases_out)]
        # 같은 5자리가 폐지·존재로 두 번 나오면 코드 CSV 는 존재 행만 담고 별칭이 폐지 행을 받는다
        assert build.main() == 0
        text = aliases_out.read_bytes().decode("utf-8")
        assert text == build.ALIAS_HEADER + "\nv1,강원도 화천군,강원특별자치도 화천군\n"
        assert "\r" not in text and not text.encode("utf-8").startswith(b"\xef\xbb\xbf")
