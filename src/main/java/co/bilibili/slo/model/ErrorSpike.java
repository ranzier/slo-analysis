package co.bilibili.slo.model;

public record ErrorSpike(
        String api,
        String start,
        String end,
        double totalErrors,
        double maxErrorsPerPoint,
        String windowStart,
        String windowEnd
) {}
