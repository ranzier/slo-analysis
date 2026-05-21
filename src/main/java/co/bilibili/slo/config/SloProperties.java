package co.bilibili.slo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "slo")
public class SloProperties {

    private WatcherConfig watcher = new WatcherConfig();
    private BillionsConfig billions = new BillionsConfig();
    private OutputConfig output = new OutputConfig();

    @PostConstruct
    void loadLegacyConfig() {
        Path configPath = Path.of("../slo-agent/config.json");
        if (!configPath.toFile().exists()) {
            configPath = Path.of("config.json");
        }
        if (!configPath.toFile().exists()) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> config = mapper.readValue(configPath.toFile(), Map.class);

            if (isBlank(watcher.getCookie())) {
                watcher.setCookie(config.getOrDefault("WATCHER_COOKIE", ""));
            }
            if (isBlank(billions.getCookie())) {
                billions.setCookie(config.getOrDefault("BILLIONS_COOKIE", ""));
            }
        } catch (IOException e) {
            // config.json not available, rely on Spring properties
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public WatcherConfig getWatcher() { return watcher; }
    public void setWatcher(WatcherConfig watcher) { this.watcher = watcher; }
    public BillionsConfig getBillions() { return billions; }
    public void setBillions(BillionsConfig billions) { this.billions = billions; }
    public OutputConfig getOutput() { return output; }
    public void setOutput(OutputConfig output) { this.output = output; }

    public static class WatcherConfig {
        private String baseUrl = "https://cloud.bilibili.co/api/v1/slo/dashboard/statement/app_detail";
        private String errorPanelUrl = "https://cloud.bilibili.co/api/v1/slo/dashboard/business/app_slo";
        private String cookie = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getErrorPanelUrl() { return errorPanelUrl; }
        public void setErrorPanelUrl(String errorPanelUrl) { this.errorPanelUrl = errorPanelUrl; }
        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
    }

    public static class BillionsConfig {
        private String baseUrl = "https://cloud.bilibili.co/api/v1/billions/log-query/search";
        private String cookie = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
    }

    public static class OutputConfig {
        private String baseDir = "./output";

        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }
}
