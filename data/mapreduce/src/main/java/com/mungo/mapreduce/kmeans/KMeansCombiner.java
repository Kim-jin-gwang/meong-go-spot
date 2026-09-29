package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.VectorUtil;

import java.io.IOException;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * 맵 쪽 부분합 압축 — "건수|합벡터"들을 하나로 접어 셔플 전송량을 줄인다.
 * 평균은 내지 않는다 (그건 Reducer의 몫 — 평균의 평균은 틀린 값이 되므로 합·건수만 유지).
 */
public class KMeansCombiner extends Reducer<IntWritable, Text, IntWritable, Text> {

    private final Text outValue = new Text();

    @Override
    protected void reduce(IntWritable key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        long count = 0;
        double[] sum = null;
        for (Text value : values) {
            String[] parts = value.toString().split("\\|", 2);
            double[] vector = VectorUtil.parse(parts[1]);
            if (sum == null) {
                sum = new double[vector.length];
            }
            VectorUtil.addInPlace(sum, vector);
            count += Long.parseLong(parts[0]);
        }
        if (sum == null) {
            return;
        }
        outValue.set(count + "|" + VectorUtil.format(sum));
        context.write(key, outValue);
    }
}
