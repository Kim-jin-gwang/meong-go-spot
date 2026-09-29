package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

/** 수렴한 중심으로 각 벡터의 소속 클러스터를 확정하는 맵 전용 잡 — `id\tclusterId` 출력. */
public class AssignMapper extends Mapper<LongWritable, Text, Text, Text> {

    private TreeMap<Integer, double[]> centers;
    private int dim;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

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
        outKey.set(line.substring(0, tab));
        outValue.set(Integer.toString(nearest));
        context.write(outKey, outValue);
    }
}
