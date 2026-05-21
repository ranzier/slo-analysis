package co.bilibili.slo.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "slo-agent", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "SLO 异常自动化分析工具",
        subcommands = {
                PipelineCommand.class,
                PipelineDayCommand.class,
                PipelineCompareCommand.class,
                FindIncreasedApisCommand.class,
                BatchDiffCommand.class,
        })
public class SloAgentCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("使用 --help 查看可用命令");
    }
}
