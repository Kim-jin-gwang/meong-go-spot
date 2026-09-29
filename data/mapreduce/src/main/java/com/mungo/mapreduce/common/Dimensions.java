package com.mungo.mapreduce.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * 벡터 차원 계약 (docs/data-ai-interface.md §4 ③) — 차원은 상수가 아니라 **메타데이터에서 읽고, 입력마다 검증**한다.
 *
 * <p>드라이버는 {@code -Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json} 을 받으면 그 {@code dim} 을
 * {@link #DIM_KEY} 에 심는다 ({@code -Dmungo.vector.dim=768} 로 직접 줄 수도 있다). 매퍼는 중심 파일의 차원과
 * 설정 차원이 일치하는지 setup 에서 확인하고, 이후 모든 입력 벡터를 그 차원으로 검증한다 — 다른 모델(384/1024차원)의
 * 벡터가 섞이면 "그럴듯한 쓰레기 거리" 대신 첫 줄에서 예외로 죽는다 (계약 원칙 2: 다른 모델 벡터는 섞이지 않는다).
 *
 * <p>차원 설정이 없으면 중심 파일(또는 첫 벡터)의 차원을 기준으로 삼는다 — 여전히 "입력 안에서 일관" 은 강제된다.
 * 하둡 클라이언트에 JSON 라이브러리를 기대하지 않도록 {@code "dim": N} 은 정규식으로 읽는다 (thin jar).
 */
public final class Dimensions {

    public static final String DIM_KEY = "mungo.vector.dim";
    public static final String META_KEY = "mungo.vector.meta";
    private static final Pattern DIM_IN_META = Pattern.compile("\"dim\"\\s*:\\s*(\\d+)");

    private Dimensions() {}

    /** 드라이버용: 메타 경로가 주어졌고 차원이 아직 없으면 읽어 conf 에 심는다. 반환 0 = 미지정(중심/첫 벡터에서 유도). */
    public static int configure(Configuration conf) throws IOException {
        int dim = conf.getInt(DIM_KEY, 0);
        String meta = conf.get(META_KEY);
        if (dim <= 0 && meta != null && !meta.isBlank()) {
            dim = readMetaDim(conf, new Path(meta.trim()));
            conf.setInt(DIM_KEY, dim);
            System.out.println("벡터 차원 " + dim + " (" + meta.trim() + ")");
        } else if (dim > 0) {
            System.out.println("벡터 차원 " + dim + " (" + DIM_KEY + ")");
        }
        return dim;
    }

    public static int readMetaDim(Configuration conf, Path meta) throws IOException {
        FileSystem fs = meta.getFileSystem(conf);
        try (InputStream in = fs.open(meta)) {
            return parseMetaDim(new String(in.readAllBytes(), StandardCharsets.UTF_8), meta.toString());
        }
    }

    /** {@code _meta.json} 본문에서 dim 을 뽑는다 — 없거나 0 이면 실패 (조용히 넘기지 않는다). */
    public static int parseMetaDim(String json, String source) throws IOException {
        Matcher m = DIM_IN_META.matcher(json);
        if (!m.find()) {
            throw new IOException("_meta.json 에 dim 이 없습니다: " + source);
        }
        int dim = Integer.parseInt(m.group(1));
        if (dim <= 0) {
            throw new IOException("_meta.json 의 dim 이 0 이하입니다: " + source);
        }
        return dim;
    }

    /** 중심 파일의 차원 — 중심끼리 다르면 파일이 깨진 것이므로 실패. */
    public static int ofCenters(Map<Integer, double[]> centers) {
        int dim = 0;
        for (Map.Entry<Integer, double[]> entry : centers.entrySet()) {
            int length = entry.getValue().length;
            if (dim == 0) {
                dim = length;
            } else if (dim != length) {
                throw new IllegalStateException("중심 " + entry.getKey() + " 차원 " + length + " ≠ " + dim + " — 중심 파일이 섞였습니다");
            }
        }
        return dim;
    }

    /** 매퍼 setup 용: 설정 차원(있으면)과 중심 차원이 일치해야 하고, 이후 입력 검증에 쓸 차원을 돌려준다. */
    public static int expected(Configuration conf, Map<Integer, double[]> centers) {
        int fromCenters = ofCenters(centers);
        int configured = conf.getInt(DIM_KEY, 0);
        if (configured > 0 && fromCenters > 0 && configured != fromCenters) {
            throw new IllegalStateException("설정 차원 " + configured + " ≠ 중심 파일 차원 " + fromCenters
                    + " — 다른 모델의 중심을 쓰고 있습니다 (" + META_KEY + " / 중심 경로 확인)");
        }
        return configured > 0 ? configured : fromCenters;
    }
}
