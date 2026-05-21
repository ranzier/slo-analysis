package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.LogAggregator;
import co.bilibili.slo.crawler.BillionsLogCrawlerService;
import co.bilibili.slo.crawler.SloErrorCrawlerService;
import co.bilibili.slo.io.OutputWriter;
import co.bilibili.slo.model.ApiDiff;
import co.bilibili.slo.model.CompareResult;
import co.bilibili.slo.model.LogEntry;
import co.bilibili.slo.util.ApiNameParser;
import co.bilibili.slo.util.DateParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class IncreasedApiOrchestrator {

    private final SloErrorCrawlerService errorCrawler;
    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final PipelineCompareOrchestrator compareOrchestrator;
    private final OutputWriter outputWriter;

    public IncreasedApiOrchestrator(SloErrorCrawlerService errorCrawler,
                                    BillionsLogCrawlerService logCrawler,
                                    LogAggregator logAggregator,
                                    PipelineCompareOrchestrator compareOrchestrator,
                                    OutputWriter outputWriter) {
        this.errorCrawler = errorCrawler;
        this.logCrawler = logCrawler;
        this.logAggregator = logAggregator;
        this.compareOrchestrator = compareOrchestrator;
        this.outputWriter = outputWriter;
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, long baselineStart, long baselineEnd,
                    long targetStart, long targetEnd, double minDiff, double minRatio) {
        try {
            String baselineDate = DateParser.epochToDate(baselineStart);
            String targetDate = DateParser.epochToDate(targetStart);
            Path outputBase = outputWriter.createRunDirectory("pipeline_diff",
                    appPath + "_" + baselineDate + "_vs_" + targetDate);

            log("应用: %s", appPath);
            log("基线日: %s", baselineDate);
            log("异常日: %s", targetDate);

            // Step 1: 获取错误面板数据并对比
            log("获取基线日错误面板数据...");
            Map<String, Object> baselineData = errorCrawler.fetchErrorData(appPath, baselineStart, baselineEnd);
            log("获取异常日错误面板数据...");
            Map<String, Object> targetData = errorCrawler.fetchErrorData(appPath, targetStart, targetEnd);

            Map<String, Object> baselineAgg = compareOrchestrator.aggregateErrorPanel(baselineData);
            Map<String, Object> targetAgg = compareOrchestrator.aggregateErrorPanel(targetData);
            CompareResult result = compareOrchestrator.compare(baselineAgg, targetAgg);

            // 找出增量显著的接口
            List<ApiDiff> increased = result.apiDiff().stream()
                    .filter(a -> a.diff() >= minDiff && a.ratio() >= minRatio)
                    .toList();

            if (increased.isEmpty()) {
                log("未找到错误数显著增加的接口 (阈值: 增量>=%.0f, 倍数>=%.1fx)", minDiff, minRatio);
                return;
            }

            System.out.printf("%n%s%n 错误数显著增加的接口 (基线日 %s vs 异常日 %s)%n%s%n%n",
                    "=".repeat(70), baselineDate, targetDate, "=".repeat(70));
            for (ApiDiff api : increased) {
                String ratioStr = api.ratio() == Double.MAX_VALUE ? "新增" : String.format("%.1fx", api.ratio());
                System.out.printf("  %-55s 基线: %-10.0f 异常: %-10.0f 增量: %+.0f  %s%n",
                        api.api(), api.baseline(), api.target(), api.diff(), ratioStr);
            }

            outputWriter.writeJson(outputBase.resolve("increased_apis.json"), increased);

            // Step 2: 对每个增量接口采集日志并对比
            for (ApiDiff apiInfo : increased) {
                String[] parsed = ApiNameParser.parse(apiInfo.api());
                String apiPath = parsed[0];
                String retCode = parsed[1];
                Path apiDir = outputBase.resolve(ApiNameParser.safeDirName(apiInfo.api()));

                String query = String.format("_server_path = '%s' AND ret = '%s'", apiPath, retCode);

                // 采集基线日日志
                log("\n[%s] 采集基线日日志...", apiInfo.api());
                List<LogEntry> baselineLogs;
                try {
                    baselineLogs = logCrawler.searchLogsSampled(appPath, query, baselineStart, baselineEnd, 48, 50);
                } catch (Exception e) {
                    log("  基线日日志采集失败: %s，跳过", e.getMessage());
                    continue;
                }
                if (baselineLogs.isEmpty()) {
                    log("  基线日无日志，跳过");
                    continue;
                }
                outputWriter.writeJson(apiDir.resolve("baseline_logs.json"), baselineLogs);
                log("  基线日获取 %d 条日志", baselineLogs.size());

                // 采集异常日日志
                log("[%s] 采集异常日日志...", apiInfo.api());
                List<LogEntry> targetLogs;
                try {
                    targetLogs = logCrawler.searchLogsSampled(appPath, query, targetStart, targetEnd, 48, 50);
                } catch (Exception e) {
                    log("  异常日日志采集失败: %s，跳过", e.getMessage());
                    continue;
                }
                if (targetLogs.isEmpty()) {
                    log("  异常日无日志，跳过");
                    continue;
                }
                outputWriter.writeJson(apiDir.resolve("target_logs.json"), targetLogs);
                log("  异常日获取 %d 条日志", targetLogs.size());

                // 分别聚合
                log("[%s] 聚合对比...", apiInfo.api());
                Map<String, Object> baselineLogAgg = logAggregator.aggregateLogs(baselineLogs, 60);
                Map<String, Object> targetLogAgg = logAggregator.aggregateLogs(targetLogs, 60);
                outputWriter.writeJson(apiDir.resolve("baseline_aggregate.json"), baselineLogAgg);
                outputWriter.writeJson(apiDir.resolve("target_aggregate.json"), targetLogAgg);

                // 逐维度对比
                Map<String, Object> diffResult = diffAggregates(baselineLogAgg, targetLogAgg);
                outputWriter.writeJson(apiDir.resolve("diff_result.json"), diffResult);
            }

            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("Pipeline 执行失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> diffAggregates(Map<String, Object> baselineAgg, Map<String, Object> targetAgg) {
        Map<String, Object> diff = new LinkedHashMap<>();

        // 全局对比
        Map<String, Object> bSummary = (Map<String, Object>) baselineAgg.get("1_全局汇总");
        Map<String, Object> tSummary = (Map<String, Object>) targetAgg.get("1_全局汇总");
        diff.put("全局对比", Map.of(
                "baseline_logs", bSummary.get("total_logs"),
                "target_logs", tSummary.get("total_logs"),
                "baseline_avg_ts", bSummary.get("avg_ts"),
                "target_avg_ts", tSummary.get("avg_ts"),
                "baseline_hosts", bSummary.get("unique_host_count"),
                "target_hosts", tSummary.get("unique_host_count"),
                "baseline_target_ips", bSummary.get("unique_target_ip_count"),
                "target_target_ips", tSummary.get("unique_target_ip_count")
        ));

        // 下游服务对比
        Map<String, Map<String, Object>> bDs = extractDownstream(baselineAgg);
        Map<String, Map<String, Object>> tDs = extractDownstream(targetAgg);
        Set<String> allDs = new TreeSet<>();
        allDs.addAll(bDs.keySet());
        allDs.addAll(tDs.keySet());
        List<Map<String, Object>> dsDiff = new ArrayList<>();
        for (String key : allDs) {
            double bPct = bDs.containsKey(key) ? toDouble(bDs.get(key).get("pct")) : 0;
            double tPct = tDs.containsKey(key) ? toDouble(tDs.get(key).get("pct")) : 0;
            dsDiff.add(Map.of("service", key, "baseline_pct", bPct, "target_pct", tPct,
                    "pct_change", Math.round((tPct - bPct) * 10.0) / 10.0));
        }
        dsDiff.sort(Comparator.comparingDouble(m -> -Math.abs(toDouble(m.get("pct_change")))));
        diff.put("下游服务变化", dsDiff.subList(0, Math.min(dsDiff.size(), 10)));

        // 可用区对比
        Map<String, Double> bZone = extractZone(baselineAgg);
        Map<String, Double> tZone = extractZone(targetAgg);
        Set<String> allZones = new TreeSet<>();
        allZones.addAll(bZone.keySet());
        allZones.addAll(tZone.keySet());
        List<Map<String, Object>> zoneDiff = new ArrayList<>();
        for (String z : allZones) {
            double bP = bZone.getOrDefault(z, 0.0);
            double tP = tZone.getOrDefault(z, 0.0);
            zoneDiff.add(Map.of("zone", z, "baseline_pct", bP, "target_pct", tP,
                    "change", Math.round((tP - bP) * 10.0) / 10.0));
        }
        diff.put("可用区变化", zoneDiff);

        return diff;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> extractDownstream(Map<String, Object> agg) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        List<Map<String, Object>> items = (List<Map<String, Object>>) agg.getOrDefault("4_下游服务失败", List.of());
        for (Map<String, Object> item : items) {
            String key = item.get("peer_service") + "|" + item.get("downstream_path");
            result.put(key, item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> extractZone(Map<String, Object> agg) {
        Map<String, Double> result = new HashMap<>();
        List<Map<String, Object>> items = (List<Map<String, Object>>) agg.getOrDefault("7_可用区维度", List.of());
        for (Map<String, Object> item : items) {
            result.put((String) item.get("zone"), toDouble(item.get("pct")));
        }
        return result;
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
