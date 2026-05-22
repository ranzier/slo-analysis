package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.ErrorSpikeAnalyzer;
import co.bilibili.slo.analysis.LogAggregator;
import co.bilibili.slo.crawler.BillionsLogCrawlerService;
import co.bilibili.slo.crawler.SloErrorCrawlerService;
import co.bilibili.slo.io.OutputWriter;
import co.bilibili.slo.model.ErrorSpike;
import co.bilibili.slo.model.LogEntry;
import co.bilibili.slo.util.ApiNameParser;
import co.bilibili.slo.util.DateParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class PipelineDayOrchestrator {

    private final SloErrorCrawlerService errorCrawler;
    private final ErrorSpikeAnalyzer spikeAnalyzer;
    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final OutputWriter outputWriter;

    public PipelineDayOrchestrator(SloErrorCrawlerService errorCrawler, ErrorSpikeAnalyzer spikeAnalyzer,
                                   BillionsLogCrawlerService logCrawler, LogAggregator logAggregator,
                                   OutputWriter outputWriter) {
        this.errorCrawler = errorCrawler;
        this.spikeAnalyzer = spikeAnalyzer;
        this.logCrawler = logCrawler;
        this.logAggregator = logAggregator;
        this.outputWriter = outputWriter;
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, long dayStart, long dayEnd, int topApis) {
        try {
            String dateStr = DateParser.epochToDate(dayStart);
            Path outputBase = outputWriter.createRunDirectory("pipeline_day", appPath + "_" + dateStr);

            log("应用: %s", appPath);
            log("日期: %s", dateStr);

            // Step 1: 获取全天错误数面板数据
            log("Step 1: 获取全天错误数面板数据...");
            Map<String, Object> errorData = errorCrawler.fetchErrorData(appPath, dayStart, dayEnd);
            outputWriter.writeJson(outputBase.resolve("error_data.json"), errorData);
            log("  错误数面板数据已保存");

            // Step 2: 分析异常接口
            log("Step 2: 分析异常时间段和接口...");
            List<ErrorSpike> spikes = spikeAnalyzer.analyzeErrorSpikes(errorData, 5.0, 5, topApis, null, null);
            if (spikes.isEmpty()) {
                log("  未检测到异常接口，流程结束");
                return;
            }
            outputWriter.writeJson(outputBase.resolve("error_spikes.json"), spikes);

            for (ErrorSpike spike : spikes) {
                log("  - %s  错误总数: %.0f  核心区间: %s ~ %s", spike.api(), spike.totalErrors(), spike.start(), spike.end());
            }

            // Step 3 & 4: 对每个接口查询日志并聚合
            for (ErrorSpike spike : spikes) {
                String[] parsed = ApiNameParser.parse(spike.api());
                String apiPath = parsed[0];
                String retCode = parsed[1];
                String dirName = ApiNameParser.safeDirName(spike.api()) + "_" +
                        spike.start().substring(11, 16).replace(":", "");
                Path apiDir = outputBase.resolve(dirName);

                long spikeStart = parseDateTime(spike.start());
                long spikeEnd = parseDateTime(spike.end());

                log("Step 3: 查询日志: %s ret=%s (%s ~ %s)...", apiPath, retCode, spike.start(), spike.end());
                String query = ApiNameParser.buildLogQuery(appPath, apiPath, retCode);
                List<LogEntry> logs;
                try {
                    logs = logCrawler.searchLogsSampled(appPath, query, spikeStart, spikeEnd, 10, 200);
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

                log("Step 4: 聚合日志指标...");
                Map<String, Object> aggResult = logAggregator.aggregateLogs(logs, 60);
                outputWriter.writeJson(apiDir.resolve("aggregate.json"), aggResult);
                log("  聚合完成，已保存");
            }

            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("Pipeline 执行失败: " + e.getMessage(), e);
        }
    }

    private long parseDateTime(String dtStr) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.parse(dtStr, fmt).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
