package co.bilibili.slo.cli;

import co.bilibili.slo.pipeline.IncreasedApiOrchestrator;
import co.bilibili.slo.util.DateParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "diff", description = "找出错误增量接口并对比基线日/异常日日志")
public class FindIncreasedApisCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "应用服务名", arity = "0..1")
    private String appPath;

    @Option(names = "--baseline", description = "基线日期 (如 '5月5', '2026-05-05')")
    private String baseline;

    @Option(names = "--target", description = "异常日期 (如 '5月14', '2026-05-14')")
    private String target;

    @Option(names = "--min-diff", defaultValue = "50", description = "最小错误增量 (默认: 50)")
    private double minDiff;

    @Option(names = "--min-ratio", defaultValue = "1.5", description = "最小增长倍数 (默认: 1.5)")
    private double minRatio;

    @Autowired
    private IncreasedApiOrchestrator orchestrator;

    @Override
    public Integer call() {
        if (appPath == null || appPath.isBlank()) {
            appPath = readLine("请输入应用服务名 (如 open.bangumi.view-gateway): ");
            if (appPath.isBlank()) { System.err.println("错误: 必须提供应用服务名"); return 1; }
        }
        if (baseline == null || baseline.isBlank()) {
            baseline = readLine("请输入基线日期 (如 5月5): ");
            if (baseline.isBlank()) { System.err.println("错误: 必须提供基线日期"); return 1; }
        }
        if (target == null || target.isBlank()) {
            target = readLine("请输入异常日期 (如 5月14): ");
            if (target.isBlank()) { System.err.println("错误: 必须提供异常日期"); return 1; }
        }

        long[] baselineRange = DateParser.parseDayRange(baseline);
        long[] targetRange = DateParser.parseDayRange(target);
        orchestrator.run(appPath, baselineRange[0], baselineRange[1],
                targetRange[0], targetRange[1], minDiff, minRatio);
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
