package co.bilibili.slo.cli;

import co.bilibili.slo.pipeline.PipelineDayOrchestrator;
import co.bilibili.slo.util.DateParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "day", description = "指定应用+日期的 SLO 异常分析")
public class PipelineDayCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "应用服务名", arity = "0..1")
    private String appPath;

    @Option(names = "--date", description = "日期 (如 '5月14', '05-14', '2026-05-14')")
    private String date;

    @Option(names = "--top-apis", defaultValue = "3", description = "每个时间段分析前 N 个接口 (默认: 3)")
    private int topApis;

    @Autowired
    private PipelineDayOrchestrator orchestrator;

    @Override
    public Integer call() {
        if (appPath == null || appPath.isBlank()) {
            appPath = readLine("请输入应用服务名 (如 open.bangumi.view-gateway): ");
            if (appPath.isBlank()) { System.err.println("错误: 必须提供应用服务名"); return 1; }
        }
        if (date == null || date.isBlank()) {
            date = readLine("请输入日期 (如 5月18): ");
            if (date.isBlank()) { System.err.println("错误: 必须提供日期"); return 1; }
        }

        long[] range = DateParser.parseDayRange(date);
        orchestrator.run(appPath, range[0], range[1], topApis);
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
