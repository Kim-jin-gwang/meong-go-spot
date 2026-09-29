package com.mungo.mapreduce.knn;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;
import com.mungo.mapreduce.kmeans.KMeansDriver;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

/**
 * 벡터를 읽으며 즉석에서 최근접 클러스터를 배정하고 (클러스터, 태그|id|벡터)를 방출한다.
 * 별도 조인 잡 없이 K-Means 색인으로 블로킹하는 것이 kNN 조인의 1단계다.
 * 태그(Q=신고 질의, S=보호 동물)는 하위 클래스가 정한다 — MultipleInputs로 입력별 매퍼 지정.
 * 차원은 중심 파일(과 설정)에서 정해지고 모든 입력 벡터가 그 차원인지 검증된다 (계약 §4 ③).
 */
public abstract class TaggedVectorMapper extends Mapper<LongWritable, Text, IntWritable, Text> {

    private TreeMap<Integer, double[]> centers;
    private int dim;
    private final IntWritable outKey = new IntWritable();
    private final Text outValue = new Text();

    protected abstract char tag();

    @Override
    protected void setup(Context context) throws IOException {
        Path centersPath = new Path(context.getConfiguration().get(KMeansDriver.CENTERS_KEY));
        centers = Centers.load(context.getConfiguration(), centersPath);
        dim = Dimensions.expected(context.getConfiguration(), centers);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString();
        int tab = line.indexOf('\t');
        if (tab < 0) {
            return;
        }
        double[] vector = VectorUtil.parse(line.substring(tab + 1), dim);

        int nearest = -1;
        double best = Double.MAX_VALUE;
        for (Map.Entry<Integer, double[]> center : centers.entrySet()) {
            double d = VectorUtil.squaredDistance(vector, center.getValue());
            if (d < best) {
                best = d;
                nearest = center.getKey();
            }
        }
        outKey.set(nearest);
        outValue.set(tag() + "|" + line.substring(0, tab) + "|" + line.substring(tab + 1));
        context.write(outKey, outValue);
    }

    /** 신고(질의) 쪽 입력. */
    public static class QueryMapper extends TaggedVectorMapper {
        @Override
        protected char tag() {
            return 'Q';
        }
    }

    /** 보호 동물 쪽 입력. */
    public static class ShelterMapper extends TaggedVectorMapper {
        @Override
        protected char tag() {
            return 'S';
        }
    }
}
