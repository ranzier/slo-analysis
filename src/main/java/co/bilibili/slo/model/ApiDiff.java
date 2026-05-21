package co.bilibili.slo.model;

import java.util.List;

public record ApiDiff(
        String api,
        double baseline,
        double target,
        double diff,
        double ratio
) {}
