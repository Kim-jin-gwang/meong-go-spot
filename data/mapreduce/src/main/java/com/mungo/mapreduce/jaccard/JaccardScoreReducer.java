package com.mungo.mapreduce.jaccard;

import java.io.IOException;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/** 쌍별 공유 태그 수를 합산해 자카드 = c/(|A|+|B|-c)를 계산, 임계값 이상만 출력한다. */
public class JaccardScoreReducer extends Reducer<Text, Text, Text, Text> {

    private double threshold;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    @Override
    protected void setup(Context context) {
        threshold = context.getConfiguration().getDouble(JaccardDriver.THRESHOLD_KEY, 0.6);
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        long common = 0;
        for (Text ignored : values) {
            common++;
        }
        // 키 형식: idA|sizeA|idB|sizeB
        String[] parts = key.toString().split("\\|");
        long sizeA = Long.parseLong(parts[1]);
        long sizeB = Long.parseLong(parts[3]);
        double jaccard = (double) common / (sizeA + sizeB - common);
        if (jaccard >= threshold) {
            outKey.set(parts[0]);
            outValue.set(parts[2] + "\t" + jaccard);
            context.write(outKey, outValue);
        }
    }
}
