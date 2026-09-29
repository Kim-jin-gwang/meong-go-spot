package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.NATIONWIDE_REGION_CODE
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import com.hotdog.meonggocuisine.feature.community.data.asApiRegionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionOptionsTest {
    @Test
    fun `시도 목록은 전국으로 시작하고 카탈로그를 그대로 잇는다`() {
        assertEquals("전국", RegionOptions.provinces.first())
        assertEquals(RegionCatalog.districts.keys.toList(), RegionOptions.provinces.drop(1))
    }

    @Test
    fun `시도의 첫 항목은 2자리 접두 코드의 전체이고 나머지는 카탈로그다`() {
        val seoul = RegionOptions.districts("서울특별시")
        assertEquals(RegionDistrict("11", "전체", "서울특별시 전체"), seoul.first())
        assertEquals(RegionCatalog.districts.getValue("서울특별시"), seoul.drop(1))
        assertTrue(seoul.drop(1).all { it.code.startsWith("11") })
    }

    @Test
    fun `전국은 항목이 하나뿐이고 코드 00 이다`() {
        assertEquals(listOf(RegionDistrict(NATIONWIDE_REGION_CODE, "전체", "전국")), RegionOptions.districts("전국"))
    }

    @Test
    fun `저장된 코드 세 종류를 모두 화면 항목으로 되돌린다`() {
        assertEquals("전국", RegionOptions.find("00")?.first)
        assertEquals("서울특별시" to RegionDistrict("11", "전체", "서울특별시 전체"), RegionOptions.find("11"))
        assertEquals("마포구", RegionOptions.find("11440")?.second?.name)
        assertNull(RegionOptions.find("99999"))
    }

    @Test
    fun `서버에는 전국만 코드를 생략하고 시도·시군구는 그대로 보낸다`() {
        assertNull("00".asApiRegionCode())
        assertEquals("11", "11".asApiRegionCode())
        assertEquals("11440", "11440".asApiRegionCode())
        assertNull((null as String?).asApiRegionCode())
        assertTrue(SelectedRegion("11440", "서울특별시 마포구").isDistrict)
        assertFalse(SelectedRegion("11", "서울특별시 전체").isDistrict)
        assertFalse(SelectedRegion("00", "전국").isDistrict)
    }
}
