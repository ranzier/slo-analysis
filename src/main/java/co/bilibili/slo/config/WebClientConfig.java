package co.bilibili.slo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    @Bean
    public WebClient watcherWebClient(SloProperties props) {
        return WebClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader("cookie", props.getWatcher().getCookie())
                .defaultHeader("sec-fetch-dest", "empty")
                .defaultHeader("sec-fetch-mode", "cors")
                .defaultHeader("sec-fetch-site", "same-origin")
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    @Bean
    public WebClient billionsWebClient(SloProperties props) {
        return WebClient.builder()
                .baseUrl(props.getBillions().getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader("cookie", props.getBillions().getCookie())
                .defaultHeader("origin", "https://cloud.bilibili.co")
                .defaultHeader("sec-fetch-dest", "empty")
                .defaultHeader("sec-fetch-mode", "cors")
                .defaultHeader("sec-fetch-site", "same-origin")
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }
}
