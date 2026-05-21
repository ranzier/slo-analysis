package co.bilibili.slo.model;

import java.util.List;
import java.util.Map;

public record CompareResult(
        Map<String, Object> totalDiff,
        List<ApiDiff> apiDiff,
        List<Map<String, Object>> errorCodeDiff,
        List<Map<String, Object>> hotMinutes
) {}
