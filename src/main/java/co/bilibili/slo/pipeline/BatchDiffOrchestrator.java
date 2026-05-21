package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.ApiTimeWindowDetector;
import co.bilibili.slo.analysis.ApiTimeWindowDetector.TimeWindow;
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
public class BatchDiffOrchestrator {

    private final SloErrorCrawlerService errorCrawler;
    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final PipelineCompareOrchestrator compareOrchestrator;
    private final IncreasedApiOrchestrator increasedApiOrchestrator;
    private final OutputWriter outputWriter;

    public BatchDiffOrchestrator(SloErrorCrawlerService errorCrawler,
                                 BillionsLogCrawlerService logCrawler,
                                 LogAggregator logAggregator,
                                 PipelineCompareOrchestrator compareOrchestrator,
                                 IncreasedApiOrchestrator increasedApiOrchestrator,
                                 OutputWriter outputWriter) {
        this.errorCrawler = errorCrawler;
        this.logCrawler = logCrawler;
        this.logAggregator = logAggregator;
        this.compareOrchestrator = compareOrchestrator;
        this.increasedApiOrchestrator = increasedApiOrchestrator;
        this.outputWriter = outputWriter;
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, long baselineStart, long baselineEnd,
                    List<long[]> targetRanges, double minDiff, double minRatio) {
        try {
            String baselineDate = DateParser.epochToDate(baselineStart);
            Path outputBase = outputWriter.createRunDirectory("batch_diff", appPath);

            log("应用: %s", appPath);
            log("基线日: %s", baselineDate);
            log("异常日数量: %d", targetRanges.size());

            // 获取基线日错误面板（只拉一次）
            log("获取基线日错误面板数据...");
            Map<String, Object> baselineData = errorCrawler.fetchErrorData(appPath, baselineStart, baselineEnd);
            Map<String, Object> baselineAgg = compareOrchestrator.aggregateErrorPanel(baselineData);
            log("  基线日数据已就绪");

            List<Map<String, Object>> summaryList = new ArrayList<>();
            // 缓存：同一接口的基线日日志和聚合结果只拉取一次
            Map<String, List<LogEntry>> baselineLogsCache = new HashMap<>();
            Map<String, Map<String, Object>> baselineAggCache = new HashMap<>();

            for (long[] targetRange : targetRanges) {
                long targetStart = targetRange[0];
                long targetEnd = targetRange[1];
                String targetDate = DateParser.epochToDate(targetStart);
                Path dayDir = outputBase.resolve(targetDate);

                log("\n--- 处理异常日: %s ---", targetDate);

                // 获取该天错误面板
                Map<String, Object> targetData;
                try {
                    targetData = errorCrawler.fetchErrorData(appPath, targetStart, targetEnd);
                } catch (Exception e) {
                    log("  获取错误面板失败: %s，跳过", e.getMessage());
                    continue;
                }

                Map<String, Object> targetAgg = compareOrchestrator.aggregateErrorPanel(targetData);
                CompareResult result = compareOrchestrator.compare(baselineAgg, targetAgg);

                // 输出总量对比
                log("  错误总量: 基线=%s, 异常=%s, 增量=%s, 倍数=%s",
                        result.totalDiff().get("baseline_total"), result.totalDiff().get("target_total"),
                        result.totalDiff().get("diff"), result.totalDiff().get("ratio"));

                // 筛选增量接口
                List<ApiDiff> increased = result.apiDiff().stream()
                        .filter(a -> a.diff() >= minDiff && a.ratio() >= minRatio)
                        .toList();

                if (increased.isEmpty()) {
                    log("  未找到增量显著的接口，跳过");
                    continue;
                }

                log("  发现 %d 个增量接口:", increased.size());
                for (ApiDiff api : increased) {
                    String ratioStr = api.ratio() == Double.MAX_VALUE ? "新增" : String.format("%.1fx", api.ratio());
                    log("    %-50s 基线:%-8.0f 异常:%-8.0f 增量:%+.0f  %s", api.api(), api.baseline(), api.target(), api.diff(), ratioStr);
                }

                // 输出错误码分布变化
                List<Map<String, Object>> codeDiffs = result.errorCodeDiff().stream()
                        .filter(m -> Math.abs(((Number) m.get("diff")).doubleValue()) > 0)
                        .toList();
                if (!codeDiffs.isEmpty()) {
                    log("  错误码变化:");
                    for (Map<String, Object> cd : codeDiffs) {
                        log("    错误码 %-10s 基线:%-8s 异常:%-8s 增量:%s", cd.get("error_code"), cd.get("baseline"), cd.get("target"), cd.get("diff"));
                    }
                }

                outputWriter.writeJson(dayDir.resolve("increased_apis.json"), increased);

                // 对每个增量接口检测异常时间段
                for (ApiDiff apiInfo : increased) {
                    List<TimeWindow> windows = ApiTimeWindowDetector.detectTopWindows(
                            baselineData, targetData, apiInfo.api(), 3);
                    if (!windows.isEmpty()) {
                        log("  [%s] 异常时间段:", apiInfo.api());
                        for (int wi = 0; wi < windows.size(); wi++) {
                            TimeWindow w = windows.get(wi);
                            log("    窗口%d: %s ~ %s  错误数:%.0f  峰值:%.0f  倍率:%.1f",
                                    wi + 1, DateParser.epochToDatetime(w.start()), DateParser.epochToDatetime(w.end()),
                                    w.totalErrors(), w.peakErrors(), w.ratio());
                        }
                        Path apiDir = dayDir.resolve(ApiNameParser.safeDirName(apiInfo.api()));
                        outputWriter.writeJson(apiDir.resolve("time_windows.json"), windows);
                    }
                }

                // 对每个增量接口采集日志
                for (ApiDiff apiInfo : increased) {
                    String[] parsed = ApiNameParser.parse(apiInfo.api());
                    String apiPath = parsed[0];
                    String retCode = parsed[1];
                    Path apiDir = dayDir.resolve(ApiNameParser.safeDirName(apiInfo.api()));

                    String query = String.format("_server_path = '%s' AND ret = '%s'", apiPath, retCode);
                    String cacheKey = apiInfo.api();

                    // 基线日日志：从缓存取或首次拉取
                    List<LogEntry> baselineLogs = baselineLogsCache.get(cacheKey);
                    if (baselineLogs == null) {
                        log("  [%s] 采集基线日日志...", apiInfo.api());
                        try {
                            baselineLogs = logCrawler.searchLogsSampled(appPath, query, baselineStart, baselineEnd, 48, 50);
                        } catch (Exception e) {
                            log("    基线日日志采集失败: %s，跳过", e.getMessage());
                            continue;
                        }
                        if (baselineLogs.isEmpty()) {
                            log("    基线日无日志，跳过");
                            continue;
                        }
                        baselineLogsCache.put(cacheKey, baselineLogs);
                        baselineAggCache.put(cacheKey, logAggregator.aggregateLogs(baselineLogs, 60));
                    } else {
                        log("  [%s] 基线日日志已缓存，跳过拉取", apiInfo.api());
                    }
                    outputWriter.writeJson(apiDir.resolve("baseline_logs.json"), baselineLogs);

                    // 采集异常日日志
                    log("  [%s] 采集异常日日志...", apiInfo.api());
                    List<LogEntry> targetLogs;
                    try {
                        targetLogs = logCrawler.searchLogsSampled(appPath, query, targetStart, targetEnd, 48, 50);
                    } catch (Exception e) {
                        log("    异常日日志采集失败: %s，跳过", e.getMessage());
                        continue;
                    }
                    if (targetLogs.isEmpty()) {
                        log("    异常日无日志，跳过");
                        continue;
                    }
                    outputWriter.writeJson(apiDir.resolve("target_logs.json"), targetLogs);

                    // 聚合对比
                    Map<String, Object> baselineLogAgg = baselineAggCache.get(cacheKey);
                    Map<String, Object> targetLogAgg = logAggregator.aggregateLogs(targetLogs, 60);
                    outputWriter.writeJson(apiDir.resolve("baseline_aggregate.json"), baselineLogAgg);
                    outputWriter.writeJson(apiDir.resolve("target_aggregate.json"), targetLogAgg);

                    Map<String, Object> diffResult = increasedApiOrchestrator.diffAggregates(baselineLogAgg, targetLogAgg);
                    outputWriter.writeJson(apiDir.resolve("diff_result.json"), diffResult);
                    log("  [%s] 对比完成", apiInfo.api());

                    // 按异常时间段爬取详细日志（加权采样）
                    List<TimeWindow> windows = ApiTimeWindowDetector.detectTopWindows(
                            baselineData, targetData, apiInfo.api(), 3);
                    List<double[]> apiSeries = ApiTimeWindowDetector.extractApiSeriesOrdered(targetData, apiInfo.api());
                    for (int wi = 0; wi < windows.size(); wi++) {
                        TimeWindow w = windows.get(wi);
                        Path windowDir = apiDir.resolve("window_" + (wi + 1));

                        log("  [%s] 窗口%d: 爬取详细日志 (%s ~ %s)...", apiInfo.api(), wi + 1,
                                DateParser.epochToDatetime(w.start()), DateParser.epochToDatetime(w.end()));

                        // 提取窗口范围内的时间序列子集用于加权
                        List<double[]> windowSeries = apiSeries.stream()
                                .filter(p -> (long) p[0] >= w.start() && (long) p[0] < w.end())
                                .toList();

                        List<LogEntry> windowLogs;
                        try {
                            windowLogs = logCrawler.searchLogsSampledWeighted(
                                    appPath, query, w.start(), w.end(), 10, 500, windowSeries);
                        } catch (Exception e) {
                            log("    窗口%d 日志采集失败: %s，跳过", wi + 1, e.getMessage());
                            continue;
                        }
                        if (windowLogs.isEmpty()) {
                            log("    窗口%d 无日志，跳过", wi + 1);
                            continue;
                        }
                        outputWriter.writeJson(windowDir.resolve("logs.json"), windowLogs);
                        Map<String, Object> windowAgg = logAggregator.aggregateLogs(windowLogs, 60);
                        outputWriter.writeJson(windowDir.resolve("aggregate.json"), windowAgg);
                        log("    窗口%d: 获取 %d 条日志", wi + 1, windowLogs.size());
                    }
                }

                summaryList.add(Map.of(
                        "date", targetDate,
                        "increased_count", increased.size(),
                        "apis", increased.stream().map(ApiDiff::api).toList()
                ));
            }

            outputWriter.writeJson(outputBase.resolve("summary.json"), summaryList);
            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("BatchDiff 执行失败: " + e.getMessage(), e);
        }
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}
