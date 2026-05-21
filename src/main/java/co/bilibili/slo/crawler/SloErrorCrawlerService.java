package co.bilibili.slo.crawler;

import co.bilibili.slo.config.SloProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class SloErrorCrawlerService {

    private final WebClient webClient;
    private final SloProperties props;

    public SloErrorCrawlerService(WebClient watcherWebClient, SloProperties props) {
        this.webClient = watcherWebClient;
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchErrorData(String appPath, long stime, long etime,
                                               String zone, String protocol, String env) {
        Map<String, Object> result = webClient.get()
                .uri(props.getWatcher().getErrorPanelUrl(), uriBuilder -> uriBuilder
                        .queryParam("app", appPath)
                        .queryParam("env", env)
                        .queryParam("zone", zone)
                        .queryParam("stime", stime)
                        .queryParam("etime", etime)
                        .queryParam("protocol", protocol)
                        .queryParam("metric", "error")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (result == null) {
            throw new RuntimeException("API 返回空响应");
        }
        Integer code = (Integer) result.get("code");
        if (code == null || code != 0) {
            throw new RuntimeException("API 返回错误: code=" + code + ", message=" + result.get("message"));
        }
        return (Map<String, Object>) result.get("data");
    }

    public Map<String, Object> fetchErrorData(String appPath, long stime, long etime) {
        return fetchErrorData(appPath, stime, etime, "all", "GRPC", "prod");
    }
}
