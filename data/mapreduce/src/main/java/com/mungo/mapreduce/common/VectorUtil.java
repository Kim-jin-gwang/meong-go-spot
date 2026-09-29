package com.mungo.mapreduce.common;

/**
 * 벡터 텍스트 형식(comma 구분 float)의 파싱·연산 유틸.
 *
 * 차원은 어디에도 상수로 두지 않는다 — 입력에서 읽고, 기대 차원이 있으면 검증한다 (계약 §4 ③).
 * 연산은 길이가 다르면 예외로 죽는다: 예전엔 짧은 쪽 길이만 돌아 "조용히 틀린 거리" 를 냈다.
 */
public final class VectorUtil {

    private VectorUtil() {}

    public static double[] parse(String csv) {
        String[] parts = csv.split(",");
        double[] vector = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Double.parseDouble(parts[i]);
        }
        return vector;
    }

    /** 기대 차원이 있으면(>0) 길이가 다를 때 즉시 실패 — 다른 모델의 벡터가 섞인 첫 줄에서 잡는다. */
    public static double[] parse(String csv, int expectedDim) {
        double[] vector = parse(csv);
        if (expectedDim > 0 && vector.length != expectedDim) {
            throw new IllegalArgumentException("벡터 차원 " + vector.length + " ≠ 기대 " + expectedDim
                    + " — 다른 모델의 벡터가 섞였거나 mungo.vector.dim/_meta.json 이 틀렸습니다");
        }
        return vector;
    }

    public static String format(double[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    public static double squaredDistance(double[] a, double[] b) {
        requireSameLength(a, b);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    public static void addInPlace(double[] acc, double[] v) {
        requireSameLength(acc, v);
        for (int i = 0; i < acc.length; i++) {
            acc[i] += v[i];
        }
    }

    private static void requireSameLength(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("벡터 차원 불일치: " + a.length + " vs " + b.length);
        }
    }
}
