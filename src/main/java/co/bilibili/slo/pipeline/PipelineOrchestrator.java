package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.*;
import co.bilibili.slo.crawler.*;
import co.bilibili.slo.io.OutputWriter;
import co.bilibili.slo.model.AnomalyDay;
import co.bilibili.slo.model.ErrorSpike;
import co.bilibili.slo.model.LogEntry;
import co.bilibili.slo.util.ApiNameParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PipelineOrchestrator {

    private final SloCrawlerService sloCrawler;
    private final AnomalyDetector anomalyDetector;
    private final SloErrorCrawlerService errorCrawler;
    private final ErrorSpikeAnalyzer spikeAnalyzer;
    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final OutputWriter outputWriter;

    public PipelineOrchestrator(SloCrawlerService sloCrawler, AnomalyDetector anomalyDetector,
                                SloErrorCrawlerService errorCrawler, ErrorSpikeAnalyzer spikeAnalyzer,
                                BillionsLogCrawlerService logCrawler, LogAggregator logAggregator,
                                OutputWriter outputWriter) {
        this.sloCrawler = sloCrawler;
        this.anomalyDetector = anomalyDetector;
        this.errorCrawler = errorCrawler;
        this.spikeAnalyzer = spikeAnalyzer;
        this.logCrawler = logCrawler;
        this.logAggregator = logAggregator;
        this.outputWriter = outputWriter;
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, int topDays, int topApis, int days) {
        try {
            Path outputBase = outputWriter.createRunDirectory("pipeline", appPath);

            // Step 1: 获取 SLO 报表
            log("Step 1: 获取 %s 近 %d 天 SLO 报表...", appPath, days);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
            long stime = todayStart.minusDays(days).atZone(ZoneId.systemDefault()).toEpochSecond();
            long etime = todayStart.atZone(ZoneId.systemDefault()).toEpochSecond();

            Map<String, Object> sloData = sloCrawler.fetchSlo(appPath, stime, etime);
            outputWriter.writeJson(outputBase.resolve("slo_report.json"), sloData);
            log("  SLO 数据已保存");

            // Step 2: 检测异常天
            log("Step 2: 检测异常天...");
            List<AnomalyDay> anomalies = anomalyDetector.detectAnomalies(sloData, 3.0, topDays);
            if (anomalies.isEmpty()) {
                log("  未检测到异常天，流程结束");
                return;
            }

            // 补充 stime/etime
            Map<String, Object> dailyData = (Map<String, Object>) sloData.getOrDefault("data", Map.of());
            List<AnomalyDay> topAnomalies = new ArrayList<>();
            for (AnomalyDay a : anomalies) {
                String dateKey = a.date().replace("/", "-") + " 00:00:00";
                if (dailyData.containsKey(dateKey)) {
                    Map<String, Object> entry = (Map<String, Object>) dailyData.get(dateKey);
                    Map<String, Object> meta = (Map<String, Object>) entry.get("meta");
                    long anomalyStime = ((Number) meta.get("stime")).longValue();
                    long anomalyEtime = ((Number) meta.get("etime")).longValue();
                    topAnomalies.add(new AnomalyDay(a.date(), a.serviceErr(), a.reasons(), anomalyStime, anomalyEtime));
                }
            }

            outputWriter.writeJson(outputBase.resolve("anomaly_days.json"), topAnomalies);
            log("  检测到 %d 天异常: %s", topAnomalies.size(),
                    topAnomalies.stream().map(AnomalyDay::date).toList());

            List<Map<String, Object>> allReports = new ArrayList<>();

            for (AnomalyDay anomaly : topAnomalies) {
                String dateStr = anomaly.date();
                Path dateDir = outputBase.resolve(dateStr.replace("/", "-"));

                // Step 3: 获取错误数面板数据
                log("Step 3: [%s] 获取错误数面板数据...", dateStr);
                Map<String, Object> errorData;
                try {
                    errorData = errorCrawler.fetchErrorData(appPath, anomaly.stime(), anomaly.etime());
                } catch (Exception e) {
                    log("  获取失败: %s，跳过该天", e.getMessage());
                    continue;
                }
                outputWriter.writeJson(dateDir.resolve("error_data.json"), errorData);

                // Step 4: 分析异常接口
                log("Step 4: [%s] 分析异常接口...", dateStr);
                List<ErrorSpike> spikes = spikeAnalyzer.analyzeErrorSpikes(
                        errorData, 5.0, 5, topApis, null, null);
                if (spikes.isEmpty()) {
                    log("  未检测到异常接口，跳过");
                    continue;
                }
                outputWriter.writeJson(dateDir.resolve("error_spikes.json"), spikes);

                // 按时间段分组处理
                Map<String, List<ErrorSpike>> grouped = new LinkedHashMap<>();
                for (ErrorSpike s : spikes) {
                    String key = s.start() + "|" + s.end();
                    grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
                }

                for (var entry : grouped.entrySet()) {
                    List<ErrorSpike> windowSpikes = entry.getValue();
                    String windowStart = windowSpikes.get(0).start();
                    String windowEnd = windowSpikes.get(0).end();
                    log("  异常时间段: %s ~ %s", windowStart, windowEnd);

                    for (ErrorSpike spike : windowSpikes) {
                        String[] parsed = ApiNameParser.parse(spike.api());
                        String apiPath = parsed[0];
                        String retCode = parsed[1];
                        String dirName = ApiNameParser.safeDirName(spike.api()) + "_" +
                                spike.start().substring(11, 16).replace(":", "");
                        Path apiDir = dateDir.resolve(dirName);

                        long startTs = parseDateTime(spike.start());
                        long endTs = parseDateTime(spike.end());

                        // Step 5: 查询日志
                        log("Step 5: [%s] 查询日志: %s ret=%s...", dateStr, apiPath, retCode);
                        String query = ApiNameParser.buildLogQuery(appPath, apiPath, retCode);
                        List<LogEntry> logs;
                        try {
                            logs = logCrawler.searchLogsSampled(appPath, query, startTs, endTs, 10, 200);
                        } catch (Exception e) {
                            log("  日志查询失败: %s，跳过", e.getMessage());
                            continue;
                        }
                        if (logs.isEmpty()) {
                            log("  未查询到日志，跳过");
                            continue;
                        }
                        outputWriter.writeJson(apiDir.resolve("logs.json"), logs);
                        log("  获取 %d 条日志", logs.size());

                        // Step 6: 聚合日志
                        log("Step 6: [%s] 聚合日志指标...", dateStr);
                        Map<String, Object> aggResult = logAggregator.aggregateLogs(logs, 60);
                        outputWriter.writeJson(apiDir.resolve("aggregate.json"), aggResult);

                        allReports.add(Map.of(
                                "date", dateStr, "api", spike.api(),
                                "time_range", spike.start() + " ~ " + spike.end(),
                                "aggregate_path", apiDir.resolve("aggregate.json").toString()
                        ));
                    }
                }
            }

            // 生成汇总
            StringBuilder summary = new StringBuilder();
            summary.append(String.format("# SLO 异常分析汇总\n\n- 应用: %s\n- 查询范围: 近 %d 天\n\n", appPath, days));
            if (!allReports.isEmpty()) {
                for (var r : allReports) {
                    summary.append(String.format("## %s - %s\n\n时间段: %s\n\n---\n\n",
                            r.get("date"), r.get("api"), r.get("time_range")));
                }
            } else {
                summary.append("未生成分析报告（可能日志为空）\n");
            }
            outputWriter.writeMarkdown(outputBase.resolve("summary.md"), summary.toString());

            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("Pipeline 执行失败: " + e.getMessage(), e);
        }
    }

    private long parseDateTime(String dtStr) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.parse(dtStr, fmt).atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
