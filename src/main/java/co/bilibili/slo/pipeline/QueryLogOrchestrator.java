package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.LogAggregator;
import co.bilibili.slo.crawler.BillionsLogCrawlerService;
import co.bilibili.slo.io.OutputWriter;
import co.bilibili.slo.model.LogEntry;
import co.bilibili.slo.util.ApiNameParser;
import co.bilibili.slo.util.DateParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class QueryLogOrchestrator {

    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final OutputWriter outputWriter;

    public QueryLogOrchestrator(BillionsLogCrawlerService logCrawler, LogAggregator logAggregator,
                                OutputWriter outputWriter) {
        this.logCrawler = logCrawler;
        this.logAggregator = logAggregator;
        this.outputWriter = outputWriter;
    }

    public void run(String appPath, String api, long startTs, long endTs, int windows, int limit, int bucketSeconds) {
        try {
            String startStr = DateParser.epochToDatetime(startTs);
            String endStr = DateParser.epochToDatetime(endTs);

            log("应用: %s", appPath);
            log("接口: %s", api);
            log("时间段: %s ~ %s", startStr, endStr);

            String dirName = ApiNameParser.safeDirName(api) + "_" + startStr.substring(11, 16).replace(":", "");
            Path outputBase = outputWriter.createRunDirectory("query", appPath + "_" + dirName);

            String[] parsed = ApiNameParser.parse(api);
            String apiPath = parsed[0];
            String retCode = parsed[1];

            String query = ApiNameParser.buildLogQuery(appPath, apiPath, retCode);
            log("Step 1: 查询日志 (query=%s)...", query);

            List<LogEntry> logs = logCrawler.searchLogsSampled(appPath, query, startTs, endTs, windows, limit);
            if (logs.isEmpty()) {
                log("  未查询到日志，流程结束");
                return;
            }
            outputWriter.writeJson(outputBase.resolve("logs.json"), logs);
            log("  获取 %d 条日志", logs.size());

            log("Step 2: 聚合日志指标...");
            Map<String, Object> aggResult = logAggregator.aggregateLogs(logs, bucketSeconds);
            outputWriter.writeJson(outputBase.resolve("aggregate.json"), aggResult);
            log("  聚合完成，已保存");

            log("\n完成! 结果目录: %s", outputBase);
        } catch (Exception e) {
            throw new RuntimeException("Query 执行失败: " + e.getMessage(), e);
        }
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
