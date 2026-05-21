package co.bilibili.slo.model;

import java.util.List;

public record AnomalyDay(
        String date,
        long serviceErr,
        List<String> reasons,
        long stime,
        long etime
) {}
