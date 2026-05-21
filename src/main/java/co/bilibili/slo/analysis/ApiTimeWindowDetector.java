package co.bilibili.slo.analysis;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ApiTimeWindowDetector {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_WINDOW_POINTS = 10;  // 5 分钟
    private static final int MAX_WINDOW_POINTS = 240; // 2 小时
    private static final int MERGE_GAP_POINTS = 10;   // 间隔 < 5 分钟则合并

    public record TimeWindow(long start, long end, double totalErrors, double peakErrors, double ratio) {}

    /**
     * 针对指定接口，对比基线日和异常日的 30s 粒度时间序列，
     * 找出异常日相对基线日错误数显著增加的 top N 时间段。
     *
     * 算法：
     * 1. 提取该接口在两天的逐点时间序列
     * 2. 对齐时间点，计算每个点的差值（异常日 - 基线日同时刻）
     * 3. 标记差值显著的异常点（> 基线同时刻 * 3 且绝对增量 > 2）
     * 4. 合并连续异常点为窗口，间隔 < 5 分钟的合并
     * 5. 窗口长度约束：最短 5 分钟，最长 2 小时
     * 6. 按窗口内总增量评分，取 top N
     */
    @SuppressWarnings("unchecked")
    public static List<TimeWindow> detectTopWindows(
            Map<String, Object> baselineData,
            Map<String, Object> targetData,
            String apiName,
            int topN) {

        // 提取两天的时间序列
        Map<String, Double> baselineByTime = extractApiTimeSeries(baselineData, apiName);
        List<double[]> targetSeries = extractApiSeriesOrdered(targetData, apiName);

        if (targetSeries.isEmpty()) return List.of();

        // 构建基线日按"时分秒"索引（用于同时刻对比）
        Map<String, Double> baselineByHMS = new HashMap<>();
        for (var entry : baselineByTime.entrySet()) {
            String hms = entry.getKey().substring(11); // "HH:mm:ss"
            baselineByHMS.merge(hms, entry.getValue(), Double::sum);
        }

        // 计算基线均值作为兜底
        double baselineAvg = baselineByTime.isEmpty() ? 0 :
                baselineByTime.values().stream().mapToDouble(Double::doubleValue).sum() / baselineByTime.size();

        // 标记异常点：异常日该点 vs 基线日同时刻
        List<Integer> anomalyIndices = new ArrayList<>();
        for (int i = 0; i < targetSeries.size(); i++) {
            double targetVal = targetSeries.get(i)[1];
            String hms = epochToHMS((long) targetSeries.get(i)[0]);
            double baselineVal = baselineByHMS.getOrDefault(hms, baselineAvg);

            // 异常条件：超过基线同时刻 3 倍，且绝对增量 > 2
            double threshold = Math.max(baselineVal * 3, baselineAvg * 2);
            if (targetVal > threshold && targetVal - baselineVal > 2) {
                anomalyIndices.add(i);
            }
        }

        if (anomalyIndices.isEmpty()) return List.of();

        // 合并连续异常点
        List<int[]> rawWindows = mergeConsecutiveIndices(anomalyIndices);

        // 合并间隔 < 5 分钟的相邻窗口
        List<int[]> mergedWindows = mergeCloseWindows(rawWindows);

        // 约束窗口长度并计算得分
        List<TimeWindow> windows = new ArrayList<>();
        for (int[] w : mergedWindows) {
            int start = w[0];
            int end = w[1];

            // 最短 5 分钟：向两端扩展
            if (end - start < MIN_WINDOW_POINTS) {
                int deficit = MIN_WINDOW_POINTS - (end - start);
                int expandLeft = deficit / 2;
                int expandRight = deficit - expandLeft;
                start = Math.max(0, start - expandLeft);
                end = Math.min(targetSeries.size(), end + expandRight);
            }

            // 最长 2 小时：取错误增量最密集的子窗口
            if (end - start > MAX_WINDOW_POINTS) {
                int[] densest = findDensestSubWindow(targetSeries, baselineByHMS, baselineAvg, start, end, MAX_WINDOW_POINTS);
                start = densest[0];
                end = densest[1];
            }

            // 计算窗口指标
            double totalErrors = 0, peak = 0, totalDiff = 0;
            for (int i = start; i < end && i < targetSeries.size(); i++) {
                double val = targetSeries.get(i)[1];
                String hms = epochToHMS((long) targetSeries.get(i)[0]);
                double baseVal = baselineByHMS.getOrDefault(hms, baselineAvg);
                totalErrors += val;
                totalDiff += (val - baseVal);
                peak = Math.max(peak, val);
            }

            int windowLen = end - start;
            double windowAvg = windowLen > 0 ? totalErrors / windowLen : 0;
            double ratio = baselineAvg > 0 ? windowAvg / baselineAvg : windowAvg;

            if (totalDiff > 0) {
                long startTs = (long) targetSeries.get(start)[0];
                long endTs = (long) targetSeries.get(Math.min(end, targetSeries.size()) - 1)[0] + 30;
                windows.add(new TimeWindow(startTs, endTs, totalErrors, peak, Math.round(ratio * 10.0) / 10.0));
            }
        }

        // 按窗口内总错误数降序取 top N
        windows.sort(Comparator.comparingDouble(TimeWindow::totalErrors).reversed());
        return windows.subList(0, Math.min(topN, windows.size()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> extractApiTimeSeries(Map<String, Object> data, String apiName) {
        Map<String, Object> timeData = (Map<String, Object>) data.getOrDefault("time_metric_value",
                data.getOrDefault("data", Map.of()));
        String keyWithPrefix = "error:" + apiName;
        Map<String, Double> series = new TreeMap<>();

        for (var entry : timeData.entrySet()) {
            Map<String, Object> point = (Map<String, Object>) entry.getValue();
            Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
            Object val = values.get(keyWithPrefix);
            if (val == null) val = values.get(apiName);
            double count = val instanceof Number n ? n.doubleValue() : 0;
            series.put(entry.getKey(), count);
        }
        return series;
    }

    @SuppressWarnings("unchecked")
    public static List<double[]> extractApiSeriesOrdered(Map<String, Object> data, String apiName) {
        Map<String, Object> timeData = (Map<String, Object>) data.getOrDefault("time_metric_value",
                data.getOrDefault("data", Map.of()));
        List<String> timestamps = new ArrayList<>(timeData.keySet());
        Collections.sort(timestamps);

        String keyWithPrefix = "error:" + apiName;
        List<double[]> series = new ArrayList<>();

        for (String ts : timestamps) {
            Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
            Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
            Object val = values.get(keyWithPrefix);
            if (val == null) val = values.get(apiName);
            double count = val instanceof Number n ? n.doubleValue() : 0;
            long epoch = LocalDateTime.parse(ts, TS_FMT).atZone(ZONE).toEpochSecond();
            series.add(new double[]{epoch, count});
        }
        return series;
    }

    private static String epochToHMS(long epoch) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch), ZONE)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static List<int[]> mergeConsecutiveIndices(List<Integer> indices) {
        List<int[]> windows = new ArrayList<>();
        int start = indices.get(0);
        int prev = start;
        for (int i = 1; i < indices.size(); i++) {
            if (indices.get(i) - prev > 1) {
                windows.add(new int[]{start, prev + 1});
                start = indices.get(i);
            }
            prev = indices.get(i);
        }
        windows.add(new int[]{start, prev + 1});
        return windows;
    }

    private static List<int[]> mergeCloseWindows(List<int[]> windows) {
        if (windows.size() <= 1) return windows;
        List<int[]> merged = new ArrayList<>();
        int[] current = windows.get(0);
        for (int i = 1; i < windows.size(); i++) {
            int[] next = windows.get(i);
            if (next[0] - current[1] < MERGE_GAP_POINTS) {
                current = new int[]{current[0], next[1]};
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static int[] findDensestSubWindow(List<double[]> series, Map<String, Double> baselineByHMS,
                                              double baselineAvg, int start, int end, int windowSize) {
        double maxDiff = Double.NEGATIVE_INFINITY;
        int bestStart = start;

        // 滑动窗口找增量最大的子窗口
        double currentDiff = 0;
        for (int i = start; i < Math.min(start + windowSize, end); i++) {
            String hms = epochToHMS((long) series.get(i)[0]);
            currentDiff += series.get(i)[1] - baselineByHMS.getOrDefault(hms, baselineAvg);
        }
        maxDiff = currentDiff;

        for (int i = start + 1; i + windowSize <= end; i++) {
            String removeHms = epochToHMS((long) series.get(i - 1)[0]);
            String addHms = epochToHMS((long) series.get(i + windowSize - 1)[0]);
            currentDiff -= (series.get(i - 1)[1] - baselineByHMS.getOrDefault(removeHms, baselineAvg));
            currentDiff += (series.get(i + windowSize - 1)[1] - baselineByHMS.getOrDefault(addHms, baselineAvg));
            if (currentDiff > maxDiff) {
                maxDiff = currentDiff;
                bestStart = i;
            }
        }
        return new int[]{bestStart, bestStart + windowSize};
    }
}
