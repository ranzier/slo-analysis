package co.bilibili.slo.pipeline;

import co.bilibili.slo.crawler.SloErrorCrawlerService;
import co.bilibili.slo.io.OutputWriter;
import co.bilibili.slo.model.ApiDiff;
import co.bilibili.slo.model.CompareResult;
import co.bilibili.slo.util.DateParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PipelineCompareOrchestrator {

    private final SloErrorCrawlerService errorCrawler;
    private final OutputWriter outputWriter;

    public PipelineCompareOrchestrator(SloErrorCrawlerService errorCrawler, OutputWriter outputWriter) {
        this.errorCrawler = errorCrawler;
        this.outputWriter = outputWriter;
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, long baselineStart, long baselineEnd,
                    long targetStart, long targetEnd) {
        try {
            String baselineDate = DateParser.epochToDate(baselineStart);
            String targetDate = DateParser.epochToDate(targetStart);
            Path outputBase = outputWriter.createRunDirectory("pipeline_compare",
                    appPath + "_" + baselineDate + "_vs_" + targetDate);

            log("应用: %s", appPath);
            log("基线日: %s", baselineDate);
            log("异常日: %s", targetDate);

            // Step 1: 获取基线日错误面板
            log("Step 1: 获取基线日错误面板数据...");
            Map<String, Object> baselineData = errorCrawler.fetchErrorData(appPath, baselineStart, baselineEnd);
            outputWriter.writeJson(outputBase.resolve("baseline_error_data.json"), baselineData);
            log("  基线日数据已就绪");

            // Step 2: 获取异常日错误面板
            log("Step 2: 获取异常日错误面板数据...");
            Map<String, Object> targetData = errorCrawler.fetchErrorData(appPath, targetStart, targetEnd);
            outputWriter.writeJson(outputBase.resolve("target_error_data.json"), targetData);
            log("  异常日数据已保存");

            // Step 3: 对比分析
            log("Step 3: 对比分析...");
            Map<String, Object> baselineAgg = aggregateErrorPanel(baselineData);
            Map<String, Object> targetAgg = aggregateErrorPanel(targetData);
            CompareResult result = compare(baselineAgg, targetAgg);
            outputWriter.writeJson(outputBase.resolve("compare_result.json"), result);

            printReport(baselineDate, targetDate, appPath, result);
            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("Pipeline 执行失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> aggregateErrorPanel(Map<String, Object> data) {
        Map<String, Object> timeData = (Map<String, Object>) data.getOrDefault("time_metric_value",
                data.getOrDefault("data", Map.of()));
        List<String> timestamps = new ArrayList<>(timeData.keySet());
        Collections.sort(timestamps);

        Map<String, Double> apiTotals = new HashMap<>();
        Map<String, Double> totalByMinute = new HashMap<>();

        for (String ts : timestamps) {
            Map<String, Object> point = (Map<String, Object>) timeData.get(ts);
            Map<String, Object> values = (Map<String, Object>) point.getOrDefault("values", Map.of());
            String minuteKey = ts.substring(0, 16);
            for (var entry : values.entrySet()) {
                String apiName = entry.getKey().startsWith("error:") ? entry.getKey().substring(6) : entry.getKey();
                double count = toDouble(entry.getValue());
                apiTotals.merge(apiName, count, Double::sum);
                totalByMinute.merge(minuteKey, count, Double::sum);
            }
        }

        double totalErrors = apiTotals.values().stream().mapToDouble(Double::doubleValue).sum();
        return Map.of(
                "api_totals", apiTotals,
                "total_errors", totalErrors,
                "total_by_minute", totalByMinute
        );
    }

    @SuppressWarnings("unchecked")
    public CompareResult compare(Map<String, Object> baseline, Map<String, Object> target) {
        Map<String, Double> bTotals = (Map<String, Double>) baseline.get("api_totals");
        Map<String, Double> tTotals = (Map<String, Double>) target.get("api_totals");

        Set<String> allApis = new TreeSet<>();
        allApis.addAll(bTotals.keySet());
        allApis.addAll(tTotals.keySet());

        List<ApiDiff> apiDiff = new ArrayList<>();
        for (String api : allApis) {
            double bCount = bTotals.getOrDefault(api, 0.0);
            double tCount = tTotals.getOrDefault(api, 0.0);
            double diff = tCount - bCount;
            double ratio = bCount > 0 ? tCount / bCount : (tCount > 0 ? Double.MAX_VALUE : 1.0);
            apiDiff.add(new ApiDiff(api, round1(bCount), round1(tCount), round1(diff), round2(ratio)));
        }
        apiDiff.sort(Comparator.comparingDouble(ApiDiff::diff).reversed());

        // 错误码分布
        Map<String, Double> bCodes = groupByErrorCode(bTotals);
        Map<String, Double> tCodes = groupByErrorCode(tTotals);
        Set<String> allCodes = new TreeSet<>();
        allCodes.addAll(bCodes.keySet());
        allCodes.addAll(tCodes.keySet());
        List<Map<String, Object>> codeDiff = new ArrayList<>();
        for (String code : allCodes) {
            double bC = bCodes.getOrDefault(code, 0.0);
            double tC = tCodes.getOrDefault(code, 0.0);
            codeDiff.add(Map.of("error_code", code, "baseline", round1(bC), "target", round1(tC), "diff", round1(tC - bC)));
        }
        codeDiff.sort(Comparator.comparingDouble(m -> -(double) m.get("diff")));

        // 时间分布
        Map<String, Double> bMinutes = (Map<String, Double>) baseline.get("total_by_minute");
        Map<String, Double> tMinutes = (Map<String, Double>) target.get("total_by_minute");
        double bAvgPerMin = bMinutes.isEmpty() ? 0 : bMinutes.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);

        List<Map<String, Object>> hotMinutes = new ArrayList<>();
        for (var entry : new TreeMap<>(tMinutes).entrySet()) {
            if (entry.getValue() > bAvgPerMin * 2 && entry.getValue() > 2) {
                hotMinutes.add(Map.of("time", entry.getKey(), "count", round1(entry.getValue()), "baseline_avg", round2(bAvgPerMin)));
            }
        }

        Map<String, Object> totalDiff = Map.of(
                "baseline_total", round1((double) baseline.get("total_errors")),
                "target_total", round1((double) target.get("total_errors")),
                "diff", round1((double) target.get("total_errors") - (double) baseline.get("total_errors")),
                "ratio", round2((double) baseline.get("total_errors") > 0
                        ? (double) target.get("total_errors") / (double) baseline.get("total_errors") : 0)
        );

        return new CompareResult(totalDiff, apiDiff, codeDiff, hotMinutes);
    }

    private Map<String, Double> groupByErrorCode(Map<String, Double> totals) {
        Map<String, Double> codes = new HashMap<>();
        for (var entry : totals.entrySet()) {
            int idx = entry.getKey().lastIndexOf(':');
            String code = idx != -1 ? entry.getKey().substring(idx) : "unknown";
            codes.merge(code, entry.getValue(), Double::sum);
        }
        return codes;
    }

    private void printReport(String baselineDate, String targetDate, String appPath, CompareResult result) {
        System.out.printf("%n%s%n 基线对比分析: %s%n 基线日: %s  异常日: %s%n%s%n",
                "=".repeat(70), appPath, baselineDate, targetDate, "=".repeat(70));

        System.out.println("\n--- 接口增量 Top 10 ---");
        result.apiDiff().stream().limit(10).filter(a -> a.diff() > 0).forEach(row ->
                System.out.printf("  %-55s 基线: %-8.0f 异常: %-8.0f 增量: %+.0f (%.1fx)%n",
                        row.api(), row.baseline(), row.target(), row.diff(), row.ratio()));
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
