package co.bilibili.slo.crawler;

import co.bilibili.slo.config.SloProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class SloCrawlerService {

    private final WebClient webClient;
    private final SloProperties props;

    public SloCrawlerService(WebClient watcherWebClient, SloProperties props) {
        this.webClient = watcherWebClient;
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchSlo(String appPath, int nodeId, String zone,
                                         String period, long stime, long etime) {
        Map<String, Object> result = webClient.get()
                .uri(props.getWatcher().getBaseUrl(), uriBuilder -> uriBuilder
                        .queryParam("node_id", nodeId)
                        .queryParam("zone", zone)
                        .queryParam("period", period)
                        .queryParam("stime", stime)
                        .queryParam("etime", etime)
                        .queryParam("api_desc", "")
                        .queryParam("app_path", appPath)
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

    public Map<String, Object> fetchSlo(String appPath, long stime, long etime) {
        return fetchSlo(appPath, 834788, "all", "date", stime, etime);
    }
}
