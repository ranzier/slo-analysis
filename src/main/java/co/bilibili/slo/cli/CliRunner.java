package co.bilibili.slo.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final SloAgentCommand sloAgentCommand;
    private final IFactory factory;
    private int exitCode;

    public CliRunner(SloAgentCommand sloAgentCommand, IFactory factory) {
        this.sloAgentCommand = sloAgentCommand;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        exitCode = new CommandLine(sloAgentCommand, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
