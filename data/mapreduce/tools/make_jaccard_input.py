"""자카드 셀프조인 입력 — 실수집 레코드에서 동물별 속성 태그 집합을 뽑는다.

출력: tags.tsv — id \t tag1,tag2,...

태그 선택 기준(중요): 품종(KIND:)과 색상 토큰(COLOR:)만 사용한다.
성별·중성화처럼 절반이 공유하는 저변별 태그는 (a) 유사도 신호가 약하고
(b) 후보쌍 생성이 태그 빈도의 제곱으로 폭발하기 때문에 제외한다.

사용법: python make_jaccard_input.py --records records.jsonl --out tags.tsv
"""

import argparse
import json
import re


def tags_of(record: dict) -> list[str]:
    tags = []
    kind = str(record.get("kindCd") or "").strip()
    if kind:
        tags.append(f"KIND:{kind}")
    color = str(record.get("colorCd") or "")
    for token in re.split(r"[^0-9A-Za-z가-힣]+", color):
        if len(token) >= 2:  # 한 글자 토큰('색' 등)은 노이즈
            tags.append(f"COLOR:{token}")
    return sorted(set(tags))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--records", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    seen = set()
    count = 0
    with open(args.records, encoding="utf-8") as src, open(args.out, "w", encoding="utf-8") as dst:
        for line in src:
            record = json.loads(line)
            animal_id = str(record.get("desertionNo") or "")
            tags = tags_of(record)
            if animal_id and len(tags) >= 2 and animal_id not in seen:
                seen.add(animal_id)
                dst.write(f"{animal_id}\t{','.join(tags)}\n")
                count += 1
    print(f"태그 집합 {count}건 → {args.out}")


if __name__ == "__main__":
    main()
