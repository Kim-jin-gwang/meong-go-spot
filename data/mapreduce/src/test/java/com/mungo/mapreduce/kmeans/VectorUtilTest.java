package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.VectorUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VectorUtilTest {

    @Test
    void 파싱과_포맷은_왕복이_보존된다() {
        double[] vector = {1.5, -0.25, 0.0, 3.125};
        assertArrayEquals(vector, VectorUtil.parse(VectorUtil.format(vector)));
    }

    @Test
    void 거리_제곱은_피타고라스와_일치한다() {
        assertEquals(25.0, VectorUtil.squaredDistance(new double[] {0, 0}, new double[] {3, 4}));
        assertEquals(0.0, VectorUtil.squaredDistance(new double[] {1, 2}, new double[] {1, 2}));
    }

    @Test
    void 누적합은_제자리에서_더해진다() {
        double[] acc = {1, 2};
        VectorUtil.addInPlace(acc, new double[] {10, 20});
        assertArrayEquals(new double[] {11, 22}, acc);
    }

    @Test
    void 숫자가_아닌_입력은_시끄럽게_실패한다() {
        // Mapper 내부 계약: 파손 벡터는 조용히 넘기지 않고 예외로 드러낸다 (fail-fast)
        assertThrows(NumberFormatException.class, () -> VectorUtil.parse("1.0,abc,2.0"));
    }

    @Test
    void 기대_차원과_다른_벡터는_첫_줄에서_실패한다() {
        assertArrayEquals(new double[] {1, 2, 3}, VectorUtil.parse("1,2,3", 3));
        assertArrayEquals(new double[] {1, 2, 3}, VectorUtil.parse("1,2,3", 0), "0 = 검증 없음(차원 유도 단계)");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> VectorUtil.parse("1,2,3", 768));
        assertEquals(true, error.getMessage().contains("3") && error.getMessage().contains("768"));
    }

    @Test
    void 차원이_다른_벡터_연산은_조용히_잘리지_않고_실패한다() {
        // 예전 구현은 짧은 쪽 길이만 돌아 384차원 vs 768차원 거리를 "그럴듯한 값" 으로 냈다 — 계약 원칙 2 위반
        assertThrows(IllegalArgumentException.class, () -> VectorUtil.squaredDistance(new double[] {1, 2}, new double[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> VectorUtil.addInPlace(new double[] {1, 2, 3}, new double[] {1, 2}));
    }
}
