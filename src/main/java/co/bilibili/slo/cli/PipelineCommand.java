package co.bilibili.slo.cli;

import co.bilibili.slo.pipeline.PipelineOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "pipeline", description = "SLO 异常自动化分析 Pipeline")
public class PipelineCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "应用服务名", arity = "0..1")
    private String appPath;

    @Option(names = "--top-days", defaultValue = "2", description = "分析最严重的前 N 天 (默认: 2)")
    private int topDays;

    @Option(names = "--top-apis", defaultValue = "3", description = "每天分析前 N 个异常接口 (默认: 3)")
    private int topApis;

    @Option(names = "--days", defaultValue = "7", description = "SLO 查询天数 (默认: 7)")
    private int days;

    @Option(names = "--log-limit", defaultValue = "1000", description = "每个接口日志上限 (默认: 1000)")
    private int logLimit;

    @Autowired
    private PipelineOrchestrator orchestrator;

    @Override
    public Integer call() {
        if (appPath == null || appPath.isBlank()) {
            appPath = System.console() != null
                    ? System.console().readLine("请输入应用服务名 (如 open.bangumi.view-gateway): ")
                    : "";
            if (appPath == null || appPath.isBlank()) {
                System.err.println("错误: 必须提供应用服务名");
                return 1;
            }
        }
        orchestrator.run(appPath, topDays, topApis, days);
        return 0;
    }
}
