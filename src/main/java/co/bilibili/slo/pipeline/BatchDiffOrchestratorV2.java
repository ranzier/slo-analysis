package co.bilibili.slo.pipeline;

import co.bilibili.slo.analysis.ApiTimeWindowDetector;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Component("batchDiffOrchestratorV2")
public class BatchDiffOrchestratorV2 {

    private final SloErrorCrawlerService errorCrawler;
    private final BillionsLogCrawlerService logCrawler;
    private final LogAggregator logAggregator;
    private final PipelineCompareOrchestrator compareOrchestrator;
    private final IncreasedApiOrchestrator increasedApiOrchestrator;
    private final OutputWriter outputWriter;

    public BatchDiffOrchestratorV2(SloErrorCrawlerService errorCrawler,
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

    public void run(String appPath, long baselineStart, long baselineEnd,
                    List<long[]> targetRanges, double minDiff, double minRatio) {
        run(appPath, baselineStart, baselineEnd, targetRanges, minDiff, minRatio, null);
    }

    @SuppressWarnings("unchecked")
    public void run(String appPath, long baselineStart, long baselineEnd,
                    List<long[]> targetRanges, double minDiff, double minRatio,
                    List<String> filterApis) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            String baselineDate = DateParser.epochToDate(baselineStart);
            Path outputBase = outputWriter.getOrCreateAppDirectory("batch_diffv2", appPath);

            log("应用: %s", appPath);
            log("基线日: %s", baselineDate);
            log("异常日数量: %d", targetRanges.size());

            // Step 1: 获取基线日错误面板
            log("Step 1: 获取基线日错误面板数据...");
            Map<String, Object> baselineData = errorCrawler.fetchErrorData(appPath, baselineStart, baselineEnd);
            Map<String, Object> baselineAgg = compareOrchestrator.aggregateErrorPanel(baselineData);
            log("  基线日数据已就绪");

            // Step 2: 从基线面板提取出错接口列表
            log("Step 2: 提取基线日出错接口...");
            Map<String, Double> apiTotals = (Map<String, Double>) baselineAgg.get("api_totals");
            List<String> baselineApis = apiTotals.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Comparator.comparingDouble(e -> -e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            List<String> apisToFetch;
            if (baselineApis.size() <= 5) {
                apisToFetch = baselineApis;
            } else {
                apisToFetch = baselineApis.subList(0, Math.min(8, baselineApis.size()));
            }
            log("  基线日共 %d 个出错接口，选取 %d 个爬取日志", baselineApis.size(), apisToFetch.size());
            for (String api : apisToFetch) {
                log("    - %s (错误数: %.0f)", api, apiTotals.get(api));
            }

            // Step 3: 并行预爬基线日志（已有缓存则跳过）
            Path baselineDir = outputBase.resolve("baseline");
            Map<String, List<LogEntry>> baselineLogsCache = new ConcurrentHashMap<>();
            Map<String, Map<String, Object>> baselineAggCache = new ConcurrentHashMap<>();

            List<String> apisNeedCrawl = new ArrayList<>();
            for (String api : apisToFetch) {
                Path apiDir = baselineDir.resolve(ApiNameParser.safeDirName(api));
                Path aggFile = apiDir.resolve("aggregate.json");
                if (Files.exists(aggFile)) {
                    Map<String, Object> cachedAgg = outputWriter.readJson(aggFile);
                    if (cachedAgg != null) {
                        baselineAggCache.put(api, cachedAgg);
                        log("  [%s] 基线日志已有缓存，跳过爬取", api);
                        continue;
                    }
                }
                apisNeedCrawl.add(api);
            }

            if (apisNeedCrawl.isEmpty()) {
                log("Step 3: 基线日志全部命中缓存，跳过爬取");
            } else {
                log("Step 3: 并行爬取基线日日志（%d/%d 个需爬取）...", apisNeedCrawl.size(), apisToFetch.size());
                CompletableFuture<?>[] baselineFutures = apisNeedCrawl.stream()
                        .map(api -> CompletableFuture.runAsync(() -> {
                            try {
                                String[] parsed = ApiNameParser.parse(api);
                                String apiPath = parsed[0];
                                String retCode = parsed[1];
                                String query = ApiNameParser.buildLogQuery(appPath, apiPath, retCode);
                                Path apiDir = baselineDir.resolve(ApiNameParser.safeDirName(api));

                                log("  [%s] 爬取基线日志...", api);
                                List<LogEntry> logs = logCrawler.searchLogsSampled(
                                        appPath, query, baselineStart, baselineEnd, 48, 50);
                                if (logs.isEmpty()) {
                                    log("  [%s] 基线日无日志", api);
                                    return;
                                }
                                baselineLogsCache.put(api, logs);
                                outputWriter.writeJson(apiDir.resolve("logs.json"), logs);

                                Map<String, Object> agg = logAggregator.aggregateLogs(logs, 60);
                                baselineAggCache.put(api, agg);
                                outputWriter.writeJson(apiDir.resolve("aggregate.json"), agg);
                                log("  [%s] 基线日志完成: %d 条", api, logs.size());
                            } catch (Exception e) {
                                log("  [%s] 基线日志爬取失败: %s", api, e.getMessage());
                            }
                        }, executor))
                        .toArray(CompletableFuture[]::new);

                CompletableFuture.allOf(baselineFutures).join();
            }
            log("  基线日志就绪，共 %d 个接口有聚合数据", baselineAggCache.size());

            // Step 4: 遍历异常日
            List<Map<String, Object>> summaryList = new ArrayList<>();

            for (long[] targetRange : targetRanges) {
                long targetStart = targetRange[0];
                long targetEnd = targetRange[1];
                String targetDate = DateParser.epochToDate(targetStart);
                Path dayDir = outputBase.resolve(targetDate);

                // 检查该异常日是否已有完整数据
                Path increasedApisFile = dayDir.resolve("increased_apis.json");
                if (Files.exists(increasedApisFile)) {
                    log("\n--- 异常日 %s 已有数据，跳过 ---", targetDate);
                    continue;
                }

                log("\n--- Step 4: 处理异常日: %s ---", targetDate);

                Map<String, Object> targetData;
                try {
                    targetData = errorCrawler.fetchErrorData(appPath, targetStart, targetEnd);
                } catch (Exception e) {
                    log("  获取错误面板失败: %s，跳过", e.getMessage());
                    continue;
                }

                Map<String, Object> targetAgg = compareOrchestrator.aggregateErrorPanel(targetData);
                CompareResult result = compareOrchestrator.compare(baselineAgg, targetAgg);

                log("  错误总量: 基线=%s, 异常=%s, 增量=%s, 倍数=%s",
                        result.totalDiff().get("baseline_total"), result.totalDiff().get("target_total"),
                        result.totalDiff().get("diff"), result.totalDiff().get("ratio"));

                // 筛选增量接口
                List<ApiDiff> increased;
                if (filterApis != null && !filterApis.isEmpty()) {
                    increased = result.apiDiff().stream()
                            .filter(a -> filterApis.stream().anyMatch(f -> a.api().contains(f)))
                            .toList();
                } else {
                    increased = result.apiDiff().stream()
                            .filter(a -> a.diff() >= minDiff && a.ratio() >= minRatio)
                            .toList();
                }

                if (increased.isEmpty()) {
                    log("  未找到增量显著的接口，跳过");
                    continue;
                }

                log("  发现 %d 个增量接口:", increased.size());
                for (ApiDiff api : increased) {
                    String ratioStr = api.ratio() == Double.MAX_VALUE ? "新增" : String.format("%.1fx", api.ratio());
                    log("    %-50s 基线:%-8.0f 异常:%-8.0f 增量:%+.0f  %s", api.api(), api.baseline(), api.target(), api.diff(), ratioStr);
                }

                // 错误码分布变化
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

                // 检测异常时间段
                for (ApiDiff apiInfo : increased) {
                    List<ApiTimeWindowDetector.TimeWindow> windows = ApiTimeWindowDetector.detectTopWindows(
                            baselineData, targetData, apiInfo.api(), 3);
                    if (!windows.isEmpty()) {
                        log("  [%s] 异常时间段:", apiInfo.api());
                        for (int wi = 0; wi < windows.size(); wi++) {
                            ApiTimeWindowDetector.TimeWindow w = windows.get(wi);
                            log("    窗口%d: %s ~ %s  错误数:%.0f  峰值:%.0f  倍率:%.1f",
                                    wi + 1, DateParser.epochToDatetime(w.start()), DateParser.epochToDatetime(w.end()),
                                    w.totalErrors(), w.peakErrors(), w.ratio());
                        }
                        Path apiDir = dayDir.resolve(ApiNameParser.safeDirName(apiInfo.api()));
                        outputWriter.writeJson(apiDir.resolve("time_windows.json"), windows);
                    }
                }

                // 并行采集异常日日志（基线从缓存读取）
                final Map<String, Object> finalBaselineData = baselineData;
                final Map<String, Object> finalTargetData = targetData;
                CompletableFuture<?>[] futures = increased.stream()
                        .map(apiInfo -> CompletableFuture.runAsync(() ->
                                processApi(appPath, apiInfo, dayDir,
                                        targetStart, targetEnd, baselineAggCache,
                                        finalBaselineData, finalTargetData), executor))
                        .toArray(CompletableFuture[]::new);

                CompletableFuture.allOf(futures).join();

                summaryList.add(Map.of(
                        "date", targetDate,
                        "increased_count", increased.size(),
                        "apis", increased.stream().map(ApiDiff::api).toList()
                ));
            }

            outputWriter.writeJson(outputBase.resolve("summary.json"), summaryList);
            log("\n完成! 结果目录: %s", outputBase);

        } catch (Exception e) {
            throw new RuntimeException("BatchDiffV2 执行失败: " + e.getMessage(), e);
        } finally {
            executor.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private void processApi(String appPath, ApiDiff apiInfo, Path dayDir,
                            long targetStart, long targetEnd,
                            Map<String, Map<String, Object>> baselineAggCache,
                            Map<String, Object> baselineData, Map<String, Object> targetData) {
        try {
            String[] parsed = ApiNameParser.parse(apiInfo.api());
            String apiPath = parsed[0];
            String retCode = parsed[1];
            Path apiDir = dayDir.resolve(ApiNameParser.safeDirName(apiInfo.api()));

            String query = ApiNameParser.buildLogQuery(appPath, apiPath, retCode);

            // 异常日采集
            log("  [%s] 采集异常日日志...", apiInfo.api());
            List<LogEntry> targetLogs;
            try {
                targetLogs = logCrawler.searchLogsSampled(appPath, query, targetStart, targetEnd, 48, 50);
            } catch (Exception e) {
                log("  [%s] 异常日日志采集失败: %s，跳过", apiInfo.api(), e.getMessage());
                return;
            }

            if (targetLogs.isEmpty()) {
                log("  [%s] 异常日无日志，跳过", apiInfo.api());
                return;
            }

            outputWriter.writeJson(apiDir.resolve("target_logs.json"), targetLogs);

            // 聚合异常日
            Map<String, Object> targetLogAgg = logAggregator.aggregateLogs(targetLogs, 60);
            outputWriter.writeJson(apiDir.resolve("target_aggregate.json"), targetLogAgg);

            // 从缓存读取基线聚合，做 diff 对比
            Map<String, Object> baselineLogAgg = baselineAggCache.get(apiInfo.api());
            if (baselineLogAgg != null) {
                Map<String, Object> diffResult = increasedApiOrchestrator.diffAggregates(baselineLogAgg, targetLogAgg);
                outputWriter.writeJson(apiDir.resolve("diff_result.json"), diffResult);
                log("  [%s] 对比完成", apiInfo.api());
            } else {
                log("  [%s] 基线无缓存（新增接口），仅输出异常日聚合", apiInfo.api());
            }

            // 按异常时间段爬取详细日志（加权采样）
            List<ApiTimeWindowDetector.TimeWindow> windows = ApiTimeWindowDetector.detectTopWindows(
                    baselineData, targetData, apiInfo.api(), 3);
            List<double[]> apiSeries = ApiTimeWindowDetector.extractApiSeriesOrdered(targetData, apiInfo.api());
            for (int wi = 0; wi < windows.size(); wi++) {
                ApiTimeWindowDetector.TimeWindow w = windows.get(wi);
                Path windowDir = apiDir.resolve("window_" + (wi + 1));

                log("  [%s] 窗口%d: 爬取详细日志 (%s ~ %s)...", apiInfo.api(), wi + 1,
                        DateParser.epochToDatetime(w.start()), DateParser.epochToDatetime(w.end()));

                List<double[]> windowSeries = apiSeries.stream()
                        .filter(p -> (long) p[0] >= w.start() && (long) p[0] < w.end())
                        .toList();

                List<LogEntry> windowLogs;
                try {
                    windowLogs = logCrawler.searchLogsSampledWeighted(
                            appPath, query, w.start(), w.end(), 10, 500, windowSeries);
                } catch (Exception e) {
                    log("  [%s] 窗口%d 日志采集失败: %s，跳过", apiInfo.api(), wi + 1, e.getMessage());
                    continue;
                }
                if (windowLogs.isEmpty()) {
                    log("  [%s] 窗口%d 无日志，跳过", apiInfo.api(), wi + 1);
                    continue;
                }
                outputWriter.writeJson(windowDir.resolve("logs.json"), windowLogs);
                Map<String, Object> windowAgg = logAggregator.aggregateLogs(windowLogs, 60);
                outputWriter.writeJson(windowDir.resolve("aggregate.json"), windowAgg);
                log("  [%s] 窗口%d: 获取 %d 条日志", apiInfo.api(), wi + 1, windowLogs.size());
            }
        } catch (Exception e) {
            log("  [%s] 处理失败: %s", apiInfo.api(), e.getMessage());
        }
    }

    private void log(String format, Object... args) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s] %s%n", time, String.format(format, args));
    }
}