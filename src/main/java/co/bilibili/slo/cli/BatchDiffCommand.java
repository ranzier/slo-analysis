package co.bilibili.slo.cli;

import co.bilibili.slo.pipeline.BatchDiffOrchestrator;
import co.bilibili.slo.util.DateParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "batch-diff", description = "多天逐天对比：找出每天相对基线日错误增量显著的接口并对比日志")
public class BatchDiffCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "应用服务名", arity = "0..1")
    private String appPath;

    @Option(names = "--baseline", description = "基线日期 (如 '5月12', '2026-05-12')")
    private String baseline;

    @Option(names = "--targets", description = "异常日期列表，逗号分隔 (如 '5月14,5月16')，不指定则默认最近7天")
    private String targets;

    @Option(names = "--min-diff", defaultValue = "50", description = "最小错误增量 (默认: 50)")
    private double minDiff;

    @Option(names = "--min-ratio", defaultValue = "1.5", description = "最小增长倍数 (默认: 1.5)")
    private double minRatio;

    @Autowired
    private BatchDiffOrchestrator orchestrator;

    @Override
    public Integer call() {
        if (appPath == null || appPath.isBlank()) {
            appPath = readLine("请输入应用服务名 (如 open.bangumi.view-gateway): ");
            if (appPath.isBlank()) { System.err.println("错误: 必须提供应用服务名"); return 1; }
        }
        if (baseline == null || baseline.isBlank()) {
            baseline = readLine("请输入基线日期 (如 5月12): ");
            if (baseline.isBlank()) { System.err.println("错误: 必须提供基线日期"); return 1; }
        }

        long[] baselineRange = DateParser.parseDayRange(baseline);

        List<long[]> targetRanges;
        if (targets != null && !targets.isBlank()) {
            targetRanges = DateParser.parseMultipleDayRanges(targets);
        } else {
            targetRanges = DateParser.recentDayRanges(7);
        }

        orchestrator.run(appPath, baselineRange[0], baselineRange[1], targetRanges, minDiff, minRatio);
        return 0;
    }

    private String readLine(String prompt) {
        if (System.console() != null) {
            String line = System.console().readLine(prompt);
            return line != null ? line.strip() : "";
        }
        return "";
    }
}
