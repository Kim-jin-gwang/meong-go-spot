package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

/**
 * 입력 벡터(`id\tv1,v2,...`)마다 최근접 중심을 찾아 (중심ID, "1|벡터") 부분합을 낸다.
 * Combiner/Reducer가 같은 형식("건수|합벡터")을 접어 새 중심을 만든다.
 */
public class KMeansMapper extends Mapper<LongWritable, Text, IntWritable, Text> {

    private TreeMap<Integer, double[]> centers;
    private int dim;
    private final IntWritable outKey = new IntWritable();
    private final Text outValue = new Text();

    @Override
    protected void setup(Context context) throws IOException {
        Path centersPath = new Path(context.getConfiguration().get(KMeansDriver.CENTERS_KEY));
        centers = Centers.load(context.getConfiguration(), centersPath);
        dim = Dimensions.expected(context.getConfiguration(), centers); // 설정 차원 ≠ 중심 차원이면 여기서 죽는다
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
        outValue.set("1|" + line.substring(tab + 1));
        context.write(outKey, outValue);
    }
}
