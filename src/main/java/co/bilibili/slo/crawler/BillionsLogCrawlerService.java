package co.bilibili.slo.crawler;

import co.bilibili.slo.config.SloProperties;
import co.bilibili.slo.model.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BillionsLogCrawlerService {

    private static final Logger log = LoggerFactory.getLogger(BillionsLogCrawlerService.class);

    private final WebClient webClient;

    public BillionsLogCrawlerService(WebClient billionsWebClient) {
        this.webClient = billionsWebClient;
    }

    @SuppressWarnings("unchecked")
    public List<LogEntry> searchLogs(String appId, String query, long startTs, long endTs, int limit) {
        List<LogEntry> allLogs = new ArrayList<>();
        int page = 1;
        int pageSize = Math.min(limit, 200);

        //log.info("[BillionsQuery] appId={}, query='{}', from={}, to={}, limit={}", appId, query, startTs, endTs, limit);

        while (allLogs.size() < limit) {
            Map<String, Object> payload = Map.of(
                    "appId", appId,
                    "query", query,
                    "from", String.valueOf(startTs),
                    "to", String.valueOf(endTs),
                    "page", page,
                    "pageSize", pageSize,
                    "orderDesc", true,
                    "orderField", "",
                    "caseInsensitive", false,
                    "querySourceMode", "AUTO"
            );

            Map<String, Object> result = webClient.post()
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (result == null) {
                throw new RuntimeException("API 返回空响应");
            }
            Integer code = (Integer) result.get("code");
            if (code == null || code != 0) {
                throw new RuntimeException("API 返回错误: code=" + code + ", message=" + result.get("message"));
            }

            Map<String, Object> data = (Map<String, Object>) result.getOrDefault("data", Map.of());
            List<Map<String, Object>> logs = (List<Map<String, Object>>) data.getOrDefault("logs", List.of());
            if (logs.isEmpty()) break;

            for (Map<String, Object> logMap : logs) {
                allLogs.add(new LogEntry(logMap));
            }
            System.out.printf("  第 %d 页: 获取 %d 条 (累计 %d 条)%n", page, logs.size(), allLogs.size());

            if (logs.size() < pageSize) break;
            page++;
        }

        return allLogs.subList(0, Math.min(allLogs.size(), limit));
    }

    public List<LogEntry> searchLogsSampled(String appId, String query,
                                            long startTs, long endTs,
                                            int windows, int perWindow) {
        long duration = endTs - startTs;
        if (duration <= 0) {
            return searchLogs(appId, query, startTs, endTs, perWindow);
        }

        double windowSize = (double) duration / windows;
        List<LogEntry> allLogs = new ArrayList<>();

        for (int i = 0; i < windows; i++) {
            long wStart = startTs + (long) (i * windowSize);
            long wEnd = startTs + (long) ((i + 1) * windowSize);

            try {
                List<LogEntry> logs = searchLogs(appId, query, wStart, wEnd, perWindow);
                if (!logs.isEmpty()) {
                    allLogs.addAll(logs);
                    System.out.printf("  窗口 %d/%d (%d~%d): %d 条%n", i + 1, windows, wStart, wEnd, logs.size());
                }
            } catch (Exception e) {
                // 查询超时时，拆分为两个子窗口重试
                long wMid = (wStart + wEnd) / 2;
                int halfLimit = perWindow / 2;
                System.out.printf("  窗口 %d/%d (%d~%d): 查询超时，拆分重试%n", i + 1, windows, wStart, wEnd);
                try {
                    List<LogEntry> logs1 = searchLogs(appId, query, wStart, wMid, halfLimit);
                    allLogs.addAll(logs1);
                    System.out.printf("    子窗口1 (%d~%d): %d 条%n", wStart, wMid, logs1.size());
                } catch (Exception e1) {
                    System.out.printf("    子窗口1 (%d~%d): 仍失败(%s)，跳过%n", wStart, wMid, e1.getMessage());
                }
                try {
                    List<LogEntry> logs2 = searchLogs(appId, query, wMid, wEnd, halfLimit);
                    allLogs.addAll(logs2);
                    System.out.printf("    子窗口2 (%d~%d): %d 条%n", wMid, wEnd, logs2.size());
                } catch (Exception e2) {
                    System.out.printf("    子窗口2 (%d~%d): 仍失败(%s)，跳过%n", wMid, wEnd, e2.getMessage());
                }
            }
        }

        return allLogs;
    }

    /**
     * 按错误密度加权采样：错误多的时间段分配更多采样配额。
     *
     * @param errorSeries 30s 粒度的错误时间序列 [epoch, errorCount]
     * @param totalLimit  总采样条数上限
     */
    public List<LogEntry> searchLogsSampledWeighted(String appId, String query,
                                                    long startTs, long endTs,
                                                    int windows, int totalLimit,
                                                    List<double[]> errorSeries) {
        if (errorSeries == null || errorSeries.isEmpty()) {
            int perWindow = Math.max(10, totalLimit / Math.max(windows, 1));
            return searchLogsSampled(appId, query, startTs, endTs, windows, perWindow);
        }

        long duration = endTs - startTs;
        if (duration <= 0) {
            return searchLogs(appId, query, startTs, endTs, totalLimit);
        }

        double windowSize = (double) duration / windows;

        // 计算每个窗口的错误总量
        double[] windowErrors = new double[windows];
        double totalErrors = 0;
        for (double[] point : errorSeries) {
            long epoch = (long) point[0];
            if (epoch < startTs || epoch >= endTs) continue;
            int idx = (int) ((epoch - startTs) / windowSize);
            if (idx >= windows) idx = windows - 1;
            windowErrors[idx] += point[1];
            totalErrors += point[1];
        }

        if (totalErrors == 0) {
            int perWindow = Math.max(10, totalLimit / Math.max(windows, 1));
            return searchLogsSampled(appId, query, startTs, endTs, windows, perWindow);
        }

        // 按错误比例分配配额，保证有错误的窗口至少 10 条
        int minPerWindow = 10;
        int[] quotas = new int[windows];
        int activeWindows = 0;
        for (int i = 0; i < windows; i++) {
            if (windowErrors[i] > 0) activeWindows++;
        }
        int reservedForMin = activeWindows * minPerWindow;
        int distributable = Math.max(0, totalLimit - reservedForMin);

        for (int i = 0; i < windows; i++) {
            if (windowErrors[i] > 0) {
                quotas[i] = minPerWindow + (int) Math.round(distributable * windowErrors[i] / totalErrors);
            }
        }

        List<LogEntry> allLogs = new ArrayList<>();
        for (int i = 0; i < windows; i++) {
            if (quotas[i] <= 0) continue;
            long wStart = startTs + (long) (i * windowSize);
            long wEnd = startTs + (long) ((i + 1) * windowSize);

            try {
                List<LogEntry> logs = searchLogs(appId, query, wStart, wEnd, quotas[i]);
                if (!logs.isEmpty()) {
                    allLogs.addAll(logs);
                    System.out.printf("  加权窗口 %d/%d (%d~%d): 配额%d, 实际%d条%n",
                            i + 1, windows, wStart, wEnd, quotas[i], logs.size());
                }
            } catch (Exception e) {
                long wMid = (wStart + wEnd) / 2;
                int halfQuota = quotas[i] / 2;
                System.out.printf("  加权窗口 %d/%d (%d~%d): 查询超时，拆分重试%n", i + 1, windows, wStart, wEnd);
                try {
                    List<LogEntry> logs1 = searchLogs(appId, query, wStart, wMid, halfQuota);
                    allLogs.addAll(logs1);
                    System.out.printf("    子窗口1 (%d~%d): %d 条%n", wStart, wMid, logs1.size());
                } catch (Exception e1) {
                    System.out.printf("    子窗口1 (%d~%d): 仍失败(%s)，跳过%n", wStart, wMid, e1.getMessage());
                }
                try {
                    List<LogEntry> logs2 = searchLogs(appId, query, wMid, wEnd, halfQuota);
                    allLogs.addAll(logs2);
                    System.out.printf("    子窗口2 (%d~%d): %d 条%n", wMid, wEnd, logs2.size());
                } catch (Exception e2) {
                    System.out.printf("    子窗口2 (%d~%d): 仍失败(%s)，跳过%n", wMid, wEnd, e2.getMessage());
                }
            }
        }

        return allLogs;
    }
}
