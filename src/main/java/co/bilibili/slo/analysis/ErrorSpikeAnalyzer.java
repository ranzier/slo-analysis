package co.bilibili.slo.analysis;

import co.bilibili.slo.model.ErrorSpike;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ErrorSpikeAnalyzer {

    @SuppressWarnings("unchecked")
    public List<ErrorSpike> analyzeErrorSpikes(Map<String, Object> data, double thresholdMultiplier,
                                               int maxSpikes, int topApis,
                                               String detectStart, String detectEnd) {
        Map<String, Object> timeData = (Map<String, Object>) data.getOrDefault("time_metric_value",
                data.getOrDefault("data", Map.of()));
        List<String> timestamps = new ArrayList<>(timeData.keySet());
        Collections.sort(timestamps);

        if (timestamps.isEmpty()) {
            return List.of();
        }

        // 用全量数据计算基线
        List<Double> allTotals = new ArrayList<>();
        for (String ts : timestamps) {
            Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
            Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
            double total = values.values().stream().mapToDouble(this::toDouble).sum();
            allTotals.add(total);
        }

        double avgTotal = allTotals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double threshold = avgTotal > 0 ? avgTotal * thresholdMultiplier : 1;

        // 构建检测范围内的时间序列
        List<Map.Entry<String, Double>> detectSeries = new ArrayList<>();
        for (String ts : timestamps) {
            if (detectStart != null && ts.compareTo(detectStart) < 0) continue;
            if (detectEnd != null && ts.compareTo(detectEnd) > 0) continue;
            Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
            Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
            double total = values.values().stream().mapToDouble(this::toDouble).sum();
            detectSeries.add(Map.entry(ts, total));
        }

        // 找出连续超阈值的时间段
        List<Map<String, Object>> rawSpikes = new ArrayList<>();
        boolean inSpike = false;
        String spikeStart = null;
        double spikeMax = 0;

        for (var entry : detectSeries) {
            String ts = entry.getKey();
            double val = entry.getValue();
            if (val > threshold) {
                if (!inSpike) {
                    inSpike = true;
                    spikeStart = ts;
                    spikeMax = val;
                } else {
                    spikeMax = Math.max(spikeMax, val);
                }
            } else {
                if (inSpike) {
                    rawSpikes.add(Map.of("start", spikeStart, "end", ts, "max_total", spikeMax));
                    inSpike = false;
                }
            }
        }
        if (inSpike && !detectSeries.isEmpty()) {
            rawSpikes.add(Map.of("start", spikeStart, "end", detectSeries.get(detectSeries.size() - 1).getKey(), "max_total", spikeMax));
        }

        rawSpikes.sort(Comparator.comparingDouble(s -> -toDouble(s.get("max_total"))));
        rawSpikes = rawSpikes.subList(0, Math.min(rawSpikes.size(), maxSpikes));

        if (rawSpikes.isEmpty()) {
            return List.of();
        }

        // 对每个时间段找 Top N 接口并精确定位核心区间
        List<ErrorSpike> results = new ArrayList<>();
        for (Map<String, Object> spike : rawSpikes) {
            String wStart = (String) spike.get("start");
            String wEnd = (String) spike.get("end");

            Map<String, Double> apiTotals = new HashMap<>();
            for (String ts : timestamps) {
                if (ts.compareTo(wStart) < 0 || ts.compareTo(wEnd) > 0) continue;
                Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
                Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
                for (var e : values.entrySet()) {
                    String apiName = e.getKey().startsWith("error:") ? e.getKey().substring(6) : e.getKey();
                    apiTotals.merge(apiName, toDouble(e.getValue()), Double::sum);
                }
            }

            List<Map.Entry<String, Double>> topApisList = apiTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(topApis)
                    .toList();

            for (var apiEntry : topApisList) {
                String apiName = apiEntry.getKey();
                double totalErrors = apiEntry.getValue();

                // 收集该接口在窗口内的时间序列
                List<Map.Entry<String, Double>> apiSeries = new ArrayList<>();
                for (String ts : timestamps) {
                    if (ts.compareTo(wStart) < 0 || ts.compareTo(wEnd) > 0) continue;
                    Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
                    Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
                    String key = "error:" + apiName;
                    double count = toDouble(values.getOrDefault(key, 0));
                    apiSeries.add(Map.entry(ts, count));
                }

                if (apiSeries.isEmpty()) continue;

                double apiMax = apiSeries.stream().mapToDouble(Map.Entry::getValue).max().orElse(0);
                if (apiMax <= 0) continue;

                double cutoff = apiMax * 0.2;
                List<Map.Entry<String, Double>> corePoints = apiSeries.stream()
                        .filter(e -> e.getValue() >= cutoff)
                        .toList();

                String apiStart, apiEnd;
                if (!corePoints.isEmpty()) {
                    apiStart = corePoints.get(0).getKey();
                    apiEnd = corePoints.get(corePoints.size() - 1).getKey();
                } else {
                    apiStart = wStart;
                    apiEnd = wEnd;
                }

                results.add(new ErrorSpike(
                        apiName, apiStart, apiEnd,
                        Math.round(totalErrors * 10.0) / 10.0,
                        Math.round(apiMax * 10.0) / 10.0,
                        wStart, wEnd
                ));
            }
        }

        return results;
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
        return 0;
    }
}
