package com.mungo.mapreduce.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;

/** 계약 §4 ③ — 차원은 메타데이터에서 읽고, 중심·입력이 그 차원과 다르면 시끄럽게 실패한다. */
class DimensionsTest {

    private static final String META = """
            {
             "model_id": "dinov2_vitb14",
             "model_version": "v2",
             "dim": 768,
             "normalized": true
            }
            """;

    @Test
    void 메타에서_dim을_읽는다() throws IOException {
        assertEquals(768, Dimensions.parseMetaDim(META, "test"));
        assertEquals(384, Dimensions.parseMetaDim("{\"dim\":384}", "compact"));
    }

    @Test
    void 메타에_dim이_없거나_0이면_실패한다() {
        IOException missing = assertThrows(IOException.class, () -> Dimensions.parseMetaDim("{\"model_id\": \"x\"}", "no-dim"));
        assertTrue(missing.getMessage().contains("no-dim"));
        assertThrows(IOException.class, () -> Dimensions.parseMetaDim("{\"dim\": 0}", "zero"));
    }

    @Test
    void 중심_파일의_차원이_섞이면_실패한다() {
        TreeMap<Integer, double[]> centers = new TreeMap<>();
        centers.put(0, new double[] {1, 2, 3});
        centers.put(1, new double[] {4, 5, 6});
        assertEquals(3, Dimensions.ofCenters(centers));
        centers.put(2, new double[] {7, 8});
        assertThrows(IllegalStateException.class, () -> Dimensions.ofCenters(centers));
    }

    @Test
    void 설정_차원과_중심_차원이_다르면_다른_모델로_보고_실패한다() {
        TreeMap<Integer, double[]> centers = new TreeMap<>();
        centers.put(0, new double[] {1, 2, 3});
        Configuration conf = new Configuration(false);
        assertEquals(3, Dimensions.expected(conf, centers), "설정이 없으면 중심 차원을 쓴다");
        conf.setInt(Dimensions.DIM_KEY, 3);
        assertEquals(3, Dimensions.expected(conf, centers));
        conf.setInt(Dimensions.DIM_KEY, 768);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Dimensions.expected(conf, centers));
        assertTrue(error.getMessage().contains("768") && error.getMessage().contains("3"));
    }

    @Test
    void 드라이버_configure는_dim이_이미_있으면_메타를_읽지_않는다() throws IOException {
        Configuration conf = new Configuration(false);
        conf.setInt(Dimensions.DIM_KEY, 512);
        conf.set(Dimensions.META_KEY, "/does/not/exist/_meta.json"); // 열리면 예외 — 안 열어야 통과
        assertEquals(512, Dimensions.configure(conf));
        assertEquals(0, Dimensions.configure(new Configuration(false)), "둘 다 없으면 0 = 중심에서 유도");
    }
}
