package com.mungo.mapreduce.theta;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/** 버킷(품종|지역) 안에서 비등가 날짜 조건(실종일 ≤ 발견일 ≤ 실종일+기간)을 평가한다. */
public class ThetaJoinReducer extends Reducer<Text, Text, Text, Text> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private int windowDays;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    private record Item(String id, LocalDate date) {}

    @Override
    protected void setup(Context context) {
        windowDays = context.getConfiguration().getInt(ThetaJoinDriver.WINDOW_DAYS_KEY, 90);
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        List<Item> queries = new ArrayList<>();
        List<Item> shelters = new ArrayList<>();
        for (Text value : values) {
            String[] parts = value.toString().split("\\|", 3);
            LocalDate date;
            try {
                date = LocalDate.parse(parts[2], FORMAT);
            } catch (DateTimeParseException e) {
                continue; // 외부 데이터의 파손 날짜는 조인 대상에서 제외
            }
            (parts[0].charAt(0) == 'Q' ? queries : shelters).add(new Item(parts[1], date));
        }
        for (Item query : queries) {
            LocalDate limit = query.date().plusDays(windowDays);
            for (Item shelter : shelters) {
                if (!shelter.date().isBefore(query.date()) && !shelter.date().isAfter(limit)) {
                    outKey.set(query.id());
                    outValue.set(shelter.id() + "\t" + shelter.date().format(FORMAT));
                    context.write(outKey, outValue);
                }
            }
        }
    }
}
