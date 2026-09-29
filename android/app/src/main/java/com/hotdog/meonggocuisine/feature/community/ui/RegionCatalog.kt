package com.hotdog.meonggocuisine.feature.community.ui

/**
 * 행정구역 기준 데이터에서 생성한 목록. 손으로 고치지 않는다.
 *
 * 원본: infra/reference/region-codes.csv (서버가 쓰는 것과 같은 파일)
 * 생성: python data/reference/build_region_catalog_kt.py
 *
 * 목록을 손으로 관리하면 공식 자료와 어긋난다. 폐지된 행정구역을 계속 제공하면
 * 사용자가 그 지역을 골라도 서버가 거부한다.
 */
object RegionCatalog {
    val districts =
        linkedMapOf(
            "서울특별시" to
                listOf(
                    RegionDistrict("11110", "종로구"), RegionDistrict("11140", "중구"), RegionDistrict("11170", "용산구"),
                    RegionDistrict("11200", "성동구"), RegionDistrict("11215", "광진구"), RegionDistrict("11230", "동대문구"),
                    RegionDistrict("11260", "중랑구"), RegionDistrict("11290", "성북구"), RegionDistrict("11305", "강북구"),
                    RegionDistrict("11320", "도봉구"), RegionDistrict("11350", "노원구"), RegionDistrict("11380", "은평구"),
                    RegionDistrict("11410", "서대문구"), RegionDistrict("11440", "마포구"), RegionDistrict("11470", "양천구"),
                    RegionDistrict("11500", "강서구"), RegionDistrict("11530", "구로구"), RegionDistrict("11545", "금천구"),
                    RegionDistrict("11560", "영등포구"), RegionDistrict("11590", "동작구"), RegionDistrict("11620", "관악구"),
                    RegionDistrict("11650", "서초구"), RegionDistrict("11680", "강남구"), RegionDistrict("11710", "송파구"),
                    RegionDistrict("11740", "강동구"),
                ),
            "전남광주통합특별시" to
                listOf(
                    RegionDistrict("12110", "목포시"), RegionDistrict("12130", "여수시"), RegionDistrict("12150", "순천시"),
                    RegionDistrict("12170", "나주시"), RegionDistrict("12190", "광양시"), RegionDistrict("12210", "동구"),
                    RegionDistrict("12240", "서구"), RegionDistrict("12270", "남구"), RegionDistrict("12300", "북구"),
                    RegionDistrict("12330", "광산구"), RegionDistrict("12710", "담양군"), RegionDistrict("12720", "곡성군"),
                    RegionDistrict("12730", "구례군"), RegionDistrict("12740", "고흥군"), RegionDistrict("12750", "보성군"),
                    RegionDistrict("12760", "화순군"), RegionDistrict("12770", "장흥군"), RegionDistrict("12780", "강진군"),
                    RegionDistrict("12790", "해남군"), RegionDistrict("12800", "영암군"), RegionDistrict("12810", "무안군"),
                    RegionDistrict("12820", "함평군"), RegionDistrict("12830", "영광군"), RegionDistrict("12840", "장성군"),
                    RegionDistrict("12850", "완도군"), RegionDistrict("12860", "진도군"), RegionDistrict("12870", "신안군"),
                ),
            "부산광역시" to
                listOf(
                    RegionDistrict("26110", "중구"), RegionDistrict("26140", "서구"), RegionDistrict("26170", "동구"),
                    RegionDistrict("26200", "영도구"), RegionDistrict("26230", "부산진구"), RegionDistrict("26260", "동래구"),
                    RegionDistrict("26290", "남구"), RegionDistrict("26320", "북구"), RegionDistrict("26350", "해운대구"),
                    RegionDistrict("26380", "사하구"), RegionDistrict("26410", "금정구"), RegionDistrict("26440", "강서구"),
                    RegionDistrict("26470", "연제구"), RegionDistrict("26500", "수영구"), RegionDistrict("26530", "사상구"),
                    RegionDistrict("26710", "기장군"),
                ),
            "대구광역시" to
                listOf(
                    RegionDistrict("27110", "중구"), RegionDistrict("27140", "동구"), RegionDistrict("27170", "서구"),
                    RegionDistrict("27200", "남구"), RegionDistrict("27230", "북구"), RegionDistrict("27260", "수성구"),
                    RegionDistrict("27290", "달서구"), RegionDistrict("27710", "달성군"), RegionDistrict("27720", "군위군"),
                ),
            "인천광역시" to
                listOf(
                    RegionDistrict("28125", "제물포구"), RegionDistrict("28155", "영종구"), RegionDistrict("28177", "미추홀구"),
                    RegionDistrict("28185", "연수구"), RegionDistrict("28200", "남동구"), RegionDistrict("28237", "부평구"),
                    RegionDistrict("28245", "계양구"), RegionDistrict("28275", "서해구"), RegionDistrict("28290", "검단구"),
                    RegionDistrict("28710", "강화군"), RegionDistrict("28720", "옹진군"),
                ),
            "대전광역시" to
                listOf(
                    RegionDistrict("30110", "동구"), RegionDistrict("30140", "중구"), RegionDistrict("30170", "서구"),
                    RegionDistrict("30200", "유성구"), RegionDistrict("30230", "대덕구"),
                ),
            "울산광역시" to
                listOf(
                    RegionDistrict("31110", "중구"), RegionDistrict("31140", "남구"), RegionDistrict("31170", "동구"),
                    RegionDistrict("31200", "북구"), RegionDistrict("31710", "울주군"),
                ),
            "세종특별자치시" to
                listOf(
                    RegionDistrict("36110", "세종특별자치시", "세종특별자치시"),
                ),
            "경기도" to
                listOf(
                    RegionDistrict("41110", "수원시"), RegionDistrict("41111", "수원시 장안구"), RegionDistrict("41113", "수원시 권선구"),
                    RegionDistrict("41115", "수원시 팔달구"), RegionDistrict("41117", "수원시 영통구"), RegionDistrict("41130", "성남시"),
                    RegionDistrict("41131", "성남시 수정구"), RegionDistrict("41133", "성남시 중원구"), RegionDistrict("41135", "성남시 분당구"),
                    RegionDistrict("41150", "의정부시"), RegionDistrict("41170", "안양시"), RegionDistrict("41171", "안양시 만안구"),
                    RegionDistrict("41173", "안양시 동안구"), RegionDistrict("41190", "부천시"), RegionDistrict("41192", "부천시 원미구"),
                    RegionDistrict("41194", "부천시 소사구"), RegionDistrict("41196", "부천시 오정구"), RegionDistrict("41210", "광명시"),
                    RegionDistrict("41220", "평택시"), RegionDistrict("41250", "동두천시"), RegionDistrict("41270", "안산시"),
                    RegionDistrict("41271", "안산시 상록구"), RegionDistrict("41273", "안산시 단원구"), RegionDistrict("41280", "고양시"),
                    RegionDistrict("41281", "고양시 덕양구"), RegionDistrict("41285", "고양시 일산동구"), RegionDistrict("41287", "고양시 일산서구"),
                    RegionDistrict("41290", "과천시"), RegionDistrict("41310", "구리시"), RegionDistrict("41360", "남양주시"),
                    RegionDistrict("41370", "오산시"), RegionDistrict("41390", "시흥시"), RegionDistrict("41410", "군포시"),
                    RegionDistrict("41430", "의왕시"), RegionDistrict("41450", "하남시"), RegionDistrict("41460", "용인시"),
                    RegionDistrict("41461", "용인시 처인구"), RegionDistrict("41463", "용인시 기흥구"), RegionDistrict("41465", "용인시 수지구"),
                    RegionDistrict("41480", "파주시"), RegionDistrict("41500", "이천시"), RegionDistrict("41550", "안성시"),
                    RegionDistrict("41570", "김포시"), RegionDistrict("41590", "화성시"), RegionDistrict("41591", "화성시 만세구"),
                    RegionDistrict("41593", "화성시 효행구"), RegionDistrict("41595", "화성시 병점구"), RegionDistrict("41597", "화성시 동탄구"),
                    RegionDistrict("41610", "광주시"), RegionDistrict("41630", "양주시"), RegionDistrict("41650", "포천시"),
                    RegionDistrict("41670", "여주시"), RegionDistrict("41800", "연천군"), RegionDistrict("41820", "가평군"),
                    RegionDistrict("41830", "양평군"),
                ),
            "충청북도" to
                listOf(
                    RegionDistrict("43110", "청주시"), RegionDistrict("43111", "청주시 상당구"), RegionDistrict("43112", "청주시 서원구"),
                    RegionDistrict("43113", "청주시 흥덕구"), RegionDistrict("43114", "청주시 청원구"), RegionDistrict("43130", "충주시"),
                    RegionDistrict("43150", "제천시"), RegionDistrict("43720", "보은군"), RegionDistrict("43730", "옥천군"),
                    RegionDistrict("43740", "영동군"), RegionDistrict("43745", "증평군"), RegionDistrict("43750", "진천군"),
                    RegionDistrict("43760", "괴산군"), RegionDistrict("43770", "음성군"), RegionDistrict("43800", "단양군"),
                ),
            "충청남도" to
                listOf(
                    RegionDistrict("44130", "천안시"), RegionDistrict("44131", "천안시 동남구"), RegionDistrict("44133", "천안시 서북구"),
                    RegionDistrict("44150", "공주시"), RegionDistrict("44180", "보령시"), RegionDistrict("44200", "아산시"),
                    RegionDistrict("44210", "서산시"), RegionDistrict("44230", "논산시"), RegionDistrict("44250", "계룡시"),
                    RegionDistrict("44270", "당진시"), RegionDistrict("44710", "금산군"), RegionDistrict("44760", "부여군"),
                    RegionDistrict("44770", "서천군"), RegionDistrict("44790", "청양군"), RegionDistrict("44800", "홍성군"),
                    RegionDistrict("44810", "예산군"), RegionDistrict("44825", "태안군"),
                ),
            "경상북도" to
                listOf(
                    RegionDistrict("47110", "포항시"), RegionDistrict("47111", "포항시 남구"), RegionDistrict("47113", "포항시 북구"),
                    RegionDistrict("47130", "경주시"), RegionDistrict("47150", "김천시"), RegionDistrict("47170", "안동시"),
                    RegionDistrict("47190", "구미시"), RegionDistrict("47210", "영주시"), RegionDistrict("47230", "영천시"),
                    RegionDistrict("47250", "상주시"), RegionDistrict("47280", "문경시"), RegionDistrict("47290", "경산시"),
                    RegionDistrict("47730", "의성군"), RegionDistrict("47750", "청송군"), RegionDistrict("47760", "영양군"),
                    RegionDistrict("47770", "영덕군"), RegionDistrict("47820", "청도군"), RegionDistrict("47830", "고령군"),
                    RegionDistrict("47840", "성주군"), RegionDistrict("47850", "칠곡군"), RegionDistrict("47900", "예천군"),
                    RegionDistrict("47920", "봉화군"), RegionDistrict("47930", "울진군"), RegionDistrict("47940", "울릉군"),
                ),
            "경상남도" to
                listOf(
                    RegionDistrict("48120", "창원시"), RegionDistrict("48121", "창원시 의창구"), RegionDistrict("48123", "창원시 성산구"),
                    RegionDistrict("48125", "창원시 마산합포구"), RegionDistrict("48127", "창원시 마산회원구"), RegionDistrict("48129", "창원시 진해구"),
                    RegionDistrict("48170", "진주시"), RegionDistrict("48220", "통영시"), RegionDistrict("48240", "사천시"),
                    RegionDistrict("48250", "김해시"), RegionDistrict("48270", "밀양시"), RegionDistrict("48310", "거제시"),
                    RegionDistrict("48330", "양산시"), RegionDistrict("48720", "의령군"), RegionDistrict("48730", "함안군"),
                    RegionDistrict("48740", "창녕군"), RegionDistrict("48820", "고성군"), RegionDistrict("48840", "남해군"),
                    RegionDistrict("48850", "하동군"), RegionDistrict("48860", "산청군"), RegionDistrict("48870", "함양군"),
                    RegionDistrict("48880", "거창군"), RegionDistrict("48890", "합천군"),
                ),
            "제주특별자치도" to
                listOf(
                    RegionDistrict("50110", "제주시"), RegionDistrict("50130", "서귀포시"),
                ),
            "강원특별자치도" to
                listOf(
                    RegionDistrict("51110", "춘천시"), RegionDistrict("51130", "원주시"), RegionDistrict("51150", "강릉시"),
                    RegionDistrict("51170", "동해시"), RegionDistrict("51190", "태백시"), RegionDistrict("51210", "속초시"),
                    RegionDistrict("51230", "삼척시"), RegionDistrict("51720", "홍천군"), RegionDistrict("51730", "횡성군"),
                    RegionDistrict("51750", "영월군"), RegionDistrict("51760", "평창군"), RegionDistrict("51770", "정선군"),
                    RegionDistrict("51780", "철원군"), RegionDistrict("51790", "화천군"), RegionDistrict("51800", "양구군"),
                    RegionDistrict("51810", "인제군"), RegionDistrict("51820", "고성군"), RegionDistrict("51830", "양양군"),
                ),
            "전북특별자치도" to
                listOf(
                    RegionDistrict("52110", "전주시"), RegionDistrict("52111", "전주시 완산구"), RegionDistrict("52113", "전주시 덕진구"),
                    RegionDistrict("52130", "군산시"), RegionDistrict("52140", "익산시"), RegionDistrict("52180", "정읍시"),
                    RegionDistrict("52190", "남원시"), RegionDistrict("52210", "김제시"), RegionDistrict("52710", "완주군"),
                    RegionDistrict("52720", "진안군"), RegionDistrict("52730", "무주군"), RegionDistrict("52740", "장수군"),
                    RegionDistrict("52750", "임실군"), RegionDistrict("52770", "순창군"), RegionDistrict("52790", "고창군"),
                    RegionDistrict("52800", "부안군"),
                ),
        )
}
