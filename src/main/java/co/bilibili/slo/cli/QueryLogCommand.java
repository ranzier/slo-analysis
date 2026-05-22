package co.bilibili.slo.cli;

import co.bilibili.slo.pipeline.QueryLogOrchestrator;
import co.bilibili.slo.util.DateParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "query", description = "指定应用+时间段+接口，查询日志并聚合")
public class QueryLogCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "应用服务名", arity = "0..1")
    private String appPath;

    @Option(names = "--api", description = "接口名称 (如 '/path/Method:-504')")
    private String api;

    @Option(names = "--start", description = "开始时间 (如 '2026-05-18 14:00:00')")
    private String start;

    @Option(names = "--end", description = "结束时间 (如 '2026-05-18 15:00:00')")
    private String end;

    @Option(names = "--limit", defaultValue = "200", description = "日志采样总数 (默认: 200)")
    private int limit;

    @Option(names = "--windows", defaultValue = "10", description = "采样时间窗口数 (默认: 10)")
    private int windows;

    @Option(names = "--bucket", defaultValue = "60", description = "聚合时间桶秒数 (默认: 60)")
    private int bucket;

    @Autowired
    private QueryLogOrchestrator orchestrator;

    @Override
    public Integer call() {
        if (appPath == null || appPath.isBlank()) {
            appPath = readLine("请输入应用服务名 (如 open.bangumi.view-gateway): ");
            if (appPath.isBlank()) { System.err.println("错误: 必须提供应用服务名"); return 1; }
        }
        if (api == null || api.isBlank()) {
            api = readLine("请输入接口名称 (如 /path/Method:-504): ");
            if (api.isBlank()) { System.err.println("错误: 必须提供接口名称"); return 1; }
        }
        if (start == null || start.isBlank()) {
            start = readLine("请输入开始时间 (如 2026-05-18 14:00:00): ");
            if (start.isBlank()) { System.err.println("错误: 必须提供开始时间"); return 1; }
        }
        if (end == null || end.isBlank()) {
            end = readLine("请输入结束时间 (如 2026-05-18 15:00:00): ");
            if (end.isBlank()) { System.err.println("错误: 必须提供结束时间"); return 1; }
        }

        long startTs = DateParser.parseToEpochSecond(start);
        long endTs = DateParser.parseToEpochSecond(end);

        if (endTs <= startTs) {
            System.err.println("错误: 结束时间必须晚于开始时间");
            return 1;
        }

        orchestrator.run(appPath, api, startTs, endTs, windows, limit, bucket);
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
