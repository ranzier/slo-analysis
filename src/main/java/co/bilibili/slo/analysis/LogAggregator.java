package co.bilibili.slo.analysis;

import co.bilibili.slo.model.LogEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogAggregator {

    private static final Pattern PARAMS_PATTERN = Pattern.compile("(\\w+):\\s*\"?([^\"\\s,]+)\"?");
    private static final Pattern GRPC_ERROR_PATTERN = Pattern.compile("StatusRuntimeException:\\s*(\\w+)");
    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @SuppressWarnings("unchecked")
    public Map<String, Object> aggregateLogs(List<LogEntry> logs, int bucketSeconds) {
        // Dimension accumulators
        Map<String, DimAccum> byErrorClass = new LinkedHashMap<>();
        Map<String, DimAccum> byErrorDesc = new LinkedHashMap<>();
        Map<String, DimAccum> byServerPath = new LinkedHashMap<>();
        Map<String, DimAccum> byPeerService = new LinkedHashMap<>();
        Map<String, DimAccum> byHost = new LinkedHashMap<>();
        Map<String, DimAccum> byTargetIp = new LinkedHashMap<>();
        Map<String, DimAccum> byZone = new LinkedHashMap<>();
        Map<String, DimAccum> byBusinessParam = new LinkedHashMap<>();
        Map<String, DimAccum> byRequestSize = new LinkedHashMap<>();
        Map<String, DimAccum> byTime = new LinkedHashMap<>();

        List<Double> allTsValues = new ArrayList<>();
        Set<String> allTraces = new HashSet<>();
        Set<String> allMids = new HashSet<>();
        Set<String> allHosts = new HashSet<>();
        Set<String> allTargetIps = new HashSet<>();

        int totalLogs = logs.size();

        for (LogEntry log : logs) {
            String traceId = log.getString("log.trace_id");
            String mid = log.getString("mid").isEmpty() ? log.getString("auth-mid") : log.getString("mid");
            String hostName = log.getString("host.name");
            String targetIp = log.getString("target.ip");
            String targetZone = log.getString("target.zone");
            String serverPath = log.getString("server.path");
            if (serverPath.isEmpty()) serverPath = log.getString("_server_path");
            String downstreamPath = log.getString("path");
            String peerService = log.getString("peer.service");
            int statusCode = log.getInt("status.code");
            int ret = log.getInt("ret");
            double ts = log.getDouble("ts");
            long timestamp = log.getLong("timestamp");
            String grpcError = extractGrpcError(log.getString("error"));
            String errorType = classifyError(statusCode, ret, grpcError);
            String statusDesc = log.getString("status.description");
            Map<String, String> params = extractParams(log);
            int requestArraySize = extractRequestArraySize(log);

            allTsValues.add(ts);
            allTraces.add(traceId);
            allMids.add(mid);
            allHosts.add(hostName);
            allTargetIps.add(targetIp);

            // 1. 错误类型
            getAccum(byErrorClass, errorType).add(traceId, mid, hostName, targetIp, ts);

            // 2. 错误描述
            if (!statusDesc.isEmpty()) {
                getAccum(byErrorDesc, statusDesc).add(traceId, mid, hostName, targetIp, ts);
            }

            // 3. 入口接口
            DimAccum sp = getAccum(byServerPath, serverPath);
            sp.add(traceId, mid, hostName, targetIp, ts);
            sp.extras.computeIfAbsent("downstream_services", k -> new HashSet<>());
            ((Set<String>) sp.extras.get("downstream_services")).add(peerService);
            sp.extras.merge("error_types:" + errorType, 1, (a, b) -> (int) a + (int) b);

            // 4. 下游服务
            String psKey = peerService + "|" + downstreamPath;
            DimAccum ps = getAccum(byPeerService, psKey);
            ps.add(traceId, mid, hostName, targetIp, ts);
            ps.extras.computeIfAbsent("error_descs", k -> new HashMap<String, Integer>());
            ((Map<String, Integer>) ps.extras.get("error_descs")).merge(statusDesc, 1, Integer::sum);

            // 5. Gateway 实例
            getAccum(byHost, hostName).add(traceId, mid, hostName, targetIp, ts);

            // 6. 下游 IP
            String tipKey = targetIp + "|" + targetZone + "|" + peerService;
            getAccum(byTargetIp, tipKey).add(traceId, mid, hostName, targetIp, ts);

            // 7. 可用区
            getAccum(byZone, targetZone).add(traceId, mid, hostName, targetIp, ts);

            // 8. 业务数据
            String seasonId = params.getOrDefault("season_id", "");
            String epId = params.getOrDefault("ep_id", "");
            String aid = params.getOrDefault("aid", "");
            String bizKey = !seasonId.isEmpty() ? seasonId : !epId.isEmpty() ? epId : aid;
            if (!bizKey.isEmpty()) {
                String paramType = !seasonId.isEmpty() ? "season_id" : !epId.isEmpty() ? "ep_id" : "aid";
                getAccum(byBusinessParam, paramType + "=" + bizKey).add(traceId, mid, hostName, targetIp, ts);
            }

            // 9. 请求体大小
            if (requestArraySize > 0) {
                String sizeBucket = requestArraySize <= 10 ? "1-10" : requestArraySize <= 50 ? "11-50" : "50+";
                getAccum(byRequestSize, sizeBucket).add(traceId, mid, hostName, targetIp, ts);
            }

            // 10. 时间桶
            String tb = timeBucket(timestamp, bucketSeconds);
            DimAccum tbAccum = getAccum(byTime, tb);
            tbAccum.add(traceId, mid, hostName, targetIp, ts);
            tbAccum.extras.merge("error_types:" + errorType, 1, (a, b) -> (int) a + (int) b);
            tbAccum.extras.merge("zone:" + targetZone, 1, (a, b) -> (int) a + (int) b);
            if (!peerService.isEmpty()) {
                tbAccum.extras.merge("peer:" + peerService, 1, (a, b) -> (int) a + (int) b);
            }
        }

        return buildOutput(totalLogs, allTsValues, allTraces, allMids, allHosts, allTargetIps,
                byErrorClass, byErrorDesc, byServerPath, byPeerService, byHost, byTargetIp, byZone,
                byBusinessParam, byRequestSize, byTime);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildOutput(int totalLogs, List<Double> allTsValues,
                                            Set<String> allTraces, Set<String> allMids,
                                            Set<String> allHosts, Set<String> allTargetIps,
                                            Map<String, DimAccum> byErrorClass,
                                            Map<String, DimAccum> byErrorDesc,
                                            Map<String, DimAccum> byServerPath,
                                            Map<String, DimAccum> byPeerService,
                                            Map<String, DimAccum> byHost,
                                            Map<String, DimAccum> byTargetIp,
                                            Map<String, DimAccum> byZone,
                                            Map<String, DimAccum> byBusinessParam,
                                            Map<String, DimAccum> byRequestSize,
                                            Map<String, DimAccum> byTime) {
        Map<String, Object> tsStats = computeStats(allTsValues);
        Map<String, Object> output = new LinkedHashMap<>();

        // 1. 全局汇总
        Map<String, Object> globalSummary = new LinkedHashMap<>();
        globalSummary.put("total_logs", totalLogs);
        globalSummary.put("unique_trace_count", allTraces.size());
        globalSummary.put("unique_mid_count", allMids.size());
        globalSummary.put("unique_host_count", allHosts.size());
        globalSummary.put("unique_target_ip_count", allTargetIps.size());
        globalSummary.put("avg_ts", tsStats.get("avg_ts"));
        globalSummary.put("p95_ts", tsStats.get("p95_ts"));
        globalSummary.put("max_ts", tsStats.get("max_ts"));
        output.put("1_全局汇总", globalSummary);

        // 2. 错误类型分布
        List<Map<String, Object>> errorTypes = new ArrayList<>();
        byErrorClass.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("error_type", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    errorTypes.add(row);
                });
        output.put("2_错误类型分布", errorTypes);

        // 3. 错误描述分布
        List<Map<String, Object>> errorDescs = new ArrayList<>();
        byErrorDesc.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("description", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    errorDescs.add(row);
                });
        output.put("3_错误描述分布", errorDescs);

        // 4. 入口接口影响
        List<Map<String, Object>> serverPaths = new ArrayList<>();
        byServerPath.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(3)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("server_path", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    Set<String> ds = (Set<String>) e.getValue().extras.getOrDefault("downstream_services", Set.of());
                    row.put("downstream_services", new ArrayList<>(new TreeSet<>(ds)));
                    serverPaths.add(row);
                });
        output.put("4_入口接口影响", serverPaths);

        // 5. 下游服务失败
        List<Map<String, Object>> peerServices = new ArrayList<>();
        byPeerService.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(5)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|", 2);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("peer_service", parts[0]);
                    row.put("downstream_path", parts.length > 1 ? parts[1] : "");
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    row.put("avg_ts", round4(e.getValue().tsValues.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    row.put("max_ts", e.getValue().tsValues.stream().mapToDouble(Double::doubleValue).max().orElse(0));
                    row.put("unique_target_ip_count", e.getValue().targetIps.size());
                    Map<String, Integer> descs = (Map<String, Integer>) e.getValue().extras.getOrDefault("error_descs", Map.of());
                    List<String> topErrors = descs.entrySet().stream()
                            .sorted(Comparator.comparingInt(d -> -d.getValue()))
                            .limit(3)
                            .map(Map.Entry::getKey)
                            .toList();
                    row.put("top_errors", topErrors);
                    peerServices.add(row);
                });
        output.put("5_下游服务失败", peerServices);

        // 6. 实例维度_gateway
        List<Map<String, Object>> hosts = new ArrayList<>();
        byHost.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("host_name", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    hosts.add(row);
                });
        output.put("6_实例维度_gateway", hosts);

        // 7. 实例维度_下游IP
        List<Map<String, Object>> targetIps = new ArrayList<>();
        byTargetIp.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(5)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|", 3);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("target_ip", parts[0]);
                    row.put("target_zone", parts.length > 1 ? parts[1] : "");
                    row.put("peer_service", parts.length > 2 ? parts[2] : "");
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    targetIps.add(row);
                });
        output.put("7_实例维度_下游IP", targetIps);

        // 8. 可用区维度
        List<Map<String, Object>> zones = new ArrayList<>();
        byZone.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("zone", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("pct", round1(e.getValue().count * 100.0 / totalLogs));
                    zones.add(row);
                });
        output.put("8_可用区维度", zones);

        // 9. 业务数据维度
        List<Map<String, Object>> bizParams = new ArrayList<>();
        byBusinessParam.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().count))
                .limit(5)
                .forEach(e -> {
                    String[] parts = e.getKey().split("=", 2);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("param_type", parts[0]);
                    row.put("param_value", parts.length > 1 ? parts[1] : "");
                    row.put("count", e.getValue().count);
                    bizParams.add(row);
                });
        output.put("9_业务数据维度", bizParams);

        // 10. 请求体大小维度
        if (!byRequestSize.isEmpty()) {
            List<Map<String, Object>> reqSizes = new ArrayList<>();
            for (String bucket : List.of("1-10", "11-50", "50+")) {
                DimAccum accum = byRequestSize.get(bucket);
                if (accum != null) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bucket", bucket);
                    row.put("count", accum.count);
                    row.put("pct", round1(accum.count * 100.0 / totalLogs));
                    row.put("avg_ts", round4(accum.tsValues.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    reqSizes.add(row);
                }
            }
            output.put("10_请求体大小维度", reqSizes);
        }

        // 11. 时间桶趋势
        List<Map<String, Object>> allBuckets = new ArrayList<>();
        byTime.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("time_bucket", e.getKey());
                    row.put("count", e.getValue().count);
                    row.put("unique_mid_count", e.getValue().mids.size());
                    String topZone = findTopExtra(e.getValue().extras, "zone:");
                    if (!topZone.isEmpty()) {
                        int zoneCount = (int) e.getValue().extras.get("zone:" + topZone);
                        row.put("top_zone", topZone);
                        row.put("top_zone_pct", round1(zoneCount * 100.0 / e.getValue().count));
                    }
                    String topPeer = findTopExtra(e.getValue().extras, "peer:");
                    if (!topPeer.isEmpty()) {
                        int peerCount = (int) e.getValue().extras.get("peer:" + topPeer);
                        row.put("top_peer_service", topPeer);
                        row.put("top_peer_pct", round1(peerCount * 100.0 / e.getValue().count));
                    }
                    allBuckets.add(row);
                });
        List<Map<String, Object>> topBuckets = allBuckets.stream()
                .sorted(Comparator.comparingInt(r -> -(int) r.get("count")))
                .limit(5)
                .sorted(Comparator.comparing(r -> (String) r.get("time_bucket")))
                .toList();
        output.put("11_时间桶趋势", topBuckets);

        return output;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String classifyError(int statusCode, int ret, String grpcError) {
        if (statusCode == 4 || ret == -504) return "超时(DEADLINE_EXCEEDED)";
        if (statusCode == 14 || ret == -503) return "不可用(UNAVAILABLE)";
        if (statusCode == 13 || ret == -500) return "内部错误(INTERNAL)";
        if (statusCode == 8) return "资源耗尽(RESOURCE_EXHAUSTED)";
        if ("UNKNOWN".equals(grpcError)) return "未知错误(UNKNOWN)";
        return String.format("其他(code=%d,ret=%d)", statusCode, ret);
    }

    private String extractGrpcError(String error) {
        Matcher m = GRPC_ERROR_PATTERN.matcher(error);
        return m.find() ? m.group(1) : "";
    }

    private Map<String, String> extractParams(LogEntry log) {
        Map<String, String> result = new HashMap<>();
        String paramsStr = log.getString("parameters");
        Matcher m = PARAMS_PATTERN.matcher(paramsStr);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }

        String argsStr = log.getString("args");
        if (!argsStr.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> argsObj = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(argsStr, Map.class);
                Object contentInfo = argsObj.get("contentInfo");
                if (contentInfo instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.containsKey("seasonId")) {
                            result.putIfAbsent("season_id", map.get("seasonId").toString());
                        }
                    }
                } else if (contentInfo instanceof Map<?, ?> map) {
                    Object si = map.get("seasonInfo");
                    if (si instanceof Map<?, ?> siMap && siMap.containsKey("sid")) {
                        result.putIfAbsent("season_id", siMap.get("sid").toString());
                    }
                }
                if (argsObj.containsKey("oids") && argsObj.get("oids") instanceof List<?> oids) {
                    result.put("oid_count", String.valueOf(oids.size()));
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private String timeBucket(long timestampMs, int bucketSeconds) {
        long ts = timestampMs / 1000;
        long bucketed = (ts / bucketSeconds) * bucketSeconds;
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(bucketed), ZoneId.systemDefault())
                .format(BUCKET_FORMAT);
    }

    private DimAccum getAccum(Map<String, DimAccum> map, String key) {
        return map.computeIfAbsent(key, k -> new DimAccum());
    }

    private String findTopExtra(Map<String, Object> extras, String prefix) {
        String topKey = "";
        int maxCount = 0;
        for (var entry : extras.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                int count = (int) entry.getValue();
                if (count > maxCount) {
                    maxCount = count;
                    topKey = entry.getKey().substring(prefix.length());
                }
            }
        }
        return topKey;
    }

    private Map<String, Object> computeStats(List<Double> tsList) {
        if (tsList.isEmpty()) {
            return Map.of("avg_ts", 0.0, "p95_ts", 0.0, "max_ts", 0.0);
        }
        List<Double> sorted = new ArrayList<>(tsList);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        double p95 = sorted.get(Math.max(0, Math.min(p95Index, sorted.size() - 1)));
        return Map.of(
                "avg_ts", Math.round(avg * 10000.0) / 10000.0,
                "p95_ts", p95,
                "max_ts", sorted.get(sorted.size() - 1)
        );
    }

    private int extractRequestArraySize(LogEntry log) {
        String argsStr = log.getString("args");
        if (argsStr.isEmpty()) return 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> argsObj = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(argsStr, Map.class);
            for (Object val : argsObj.values()) {
                if (val instanceof List<?> list) {
                    return list.size();
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    static class DimAccum {
        int count = 0;
        Set<String> traces = new HashSet<>();
        Set<String> mids = new HashSet<>();
        Set<String> hosts = new HashSet<>();
        Set<String> targetIps = new HashSet<>();
        List<Double> tsValues = new ArrayList<>();
        Map<String, Object> extras = new HashMap<>();

        void add(String trace, String mid, String host, String targetIp, double ts) {
            count++;
            traces.add(trace);
            mids.add(mid);
            hosts.add(host);
            targetIps.add(targetIp);
            tsValues.add(ts);
        }
    }
}
