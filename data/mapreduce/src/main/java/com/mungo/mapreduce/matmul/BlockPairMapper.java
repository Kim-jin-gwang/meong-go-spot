package com.mungo.mapreduce.matmul;

import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;
import java.io.IOException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

/**
 * 블록 행렬곱의 맵 단계 — 각 행을 정규화한 뒤 자기 블록과 만나야 할 상대 블록
 * 조합((qBlock,sBlock))마다 복제 방출한다. 행 블록은 id 해시로 정한다.
 *
 * Q행(질의)은 모든 S블록에, S행(보호)은 모든 Q블록에 복제된다 —
 * 복제 계수가 곧 셔플 비용이므로 블록 수는 데이터 크기에 맞게 conf로 조정한다.
 *
 * 차원: 설정({@code mungo.vector.dim}, 드라이버가 _meta.json 에서 심음)이 있으면 그 값으로, 없으면 이 매퍼가 읽은
 * 첫 벡터의 차원으로 잠근다 — 어느 쪽이든 한 입력 안에 다른 차원이 섞이면 예외 (계약 §4 ③).
 */
public abstract class BlockPairMapper extends Mapper<LongWritable, Text, Text, Text> {

    static final String Q_BLOCKS_KEY = "matmul.q.blocks";
    static final String S_BLOCKS_KEY = "matmul.s.blocks";

    private int qBlocks;
    private int sBlocks;
    private int dim;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    protected abstract char tag();

    @Override
    protected void setup(Context context) {
        qBlocks = context.getConfiguration().getInt(Q_BLOCKS_KEY, 2);
        sBlocks = context.getConfiguration().getInt(S_BLOCKS_KEY, 8);
        dim = context.getConfiguration().getInt(Dimensions.DIM_KEY, 0);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
        String line = value.toString();
        int tab = line.indexOf('\t');
        if (tab < 0) {
            return;
        }
        String id = line.substring(0, tab);
        double[] vector = VectorUtil.parse(line.substring(tab + 1), dim);
        if (dim == 0) {
            dim = vector.length; // 설정이 없으면 첫 벡터 차원으로 잠근다
        }

        // 코사인 유사도 = 정규화 벡터의 내적 — 정규화는 여기서 한 번만
        double squaredSum = 0;
        for (double component : vector) {
            squaredSum += component * component;
        }
        double norm = Math.sqrt(squaredSum);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        String payload = tag() + "|" + id + "|" + VectorUtil.format(vector);

        int ownBlock = Math.floorMod(id.hashCode(), tag() == 'Q' ? qBlocks : sBlocks);
        int otherCount = tag() == 'Q' ? sBlocks : qBlocks;
        for (int other = 0; other < otherCount; other++) {
            int qb = tag() == 'Q' ? ownBlock : other;
            int sb = tag() == 'Q' ? other : ownBlock;
            outKey.set(qb + ":" + sb);
            outValue.set(payload);
            context.write(outKey, outValue);
        }
    }

    /** 질의 행렬 Q의 행. */
    public static class QueryMapper extends BlockPairMapper {
        @Override
        protected char tag() {
            return 'Q';
        }
    }

    /** 보호 행렬 S의 행. */
    public static class ShelterMapper extends BlockPairMapper {
        @Override
        protected char tag() {
            return 'S';
        }
    }
}
