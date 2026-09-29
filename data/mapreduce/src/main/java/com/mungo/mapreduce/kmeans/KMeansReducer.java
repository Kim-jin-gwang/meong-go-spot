package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.VectorUtil;

import java.io.IOException;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/** "건수|부분합벡터"들을 접어 평균 = 새 중심(`clusterId\tv1,v2,...`)을 출력한다. */
public class KMeansReducer extends Reducer<IntWritable, Text, IntWritable, Text> {

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
        if (sum == null || count == 0) {
            return;
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= count;
        }
        outValue.set(VectorUtil.format(sum));
        context.write(key, outValue);
    }
}
