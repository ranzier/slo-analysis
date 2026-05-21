package co.bilibili.slo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SloAgentApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(
                SpringApplication.run(SloAgentApplication.class, args)
        ));
    }
}
