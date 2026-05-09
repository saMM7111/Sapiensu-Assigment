package com.sapiensu.sebi.client;

import com.sapiensu.sebi.config.LlmConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
public class AnthropicClient implements LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final RestTemplate restTemplate;
    private final LlmConfig.ProviderProps props;

    public AnthropicClient(RestTemplate restTemplate, LlmConfig config) {
        this.restTemplate = restTemplate;
        this.props = config.getAnthropic();
    }

    @Override
    public String complete(String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "max_tokens", props.getMaxTokens(),
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", props.getApiKey());
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int attempt = 0; attempt <= props.getMaxRetries(); attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        API_URL,
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class
                );
                return extractAnthropicText(response.getBody());

            } catch (HttpClientErrorException.TooManyRequests e) {
                retryOrThrow(attempt, "Anthropic rate limited", e);
            } catch (HttpServerErrorException e) {
                retryOrThrow(attempt, "Anthropic server error " + e.getStatusCode(), e);
            } catch (ResourceAccessException e) {
                retryOrThrow(attempt, "Anthropic network error", e);
            } catch (HttpClientErrorException e) {
                throw new RuntimeException(
                        "Anthropic API error " + e.getStatusCode()
                                + ": " + e.getResponseBodyAsString(),
                        e
                );
            }
        }

        throw new RuntimeException("Anthropic completion failed after all retries");
    }

    private void retryOrThrow(int attempt, String reason, Exception e) {
        if (attempt < props.getMaxRetries()) {
            long waitMs = props.getRetryDelayMs() * (attempt + 1);
            log.warn("{}. Attempt {}/{}. Waiting {}ms",
                    reason, attempt + 1, props.getMaxRetries(), waitMs);
            sleep(waitMs);
        } else {
            throw new RuntimeException(reason + " - exceeded all retries", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAnthropicText(Map<?, ?> body) {
        if (body == null || !body.containsKey("content")) {
            throw new RuntimeException("Anthropic response missing content");
        }

        List<Map<String, Object>> content =
                (List<Map<String, Object>>) body.get("content");
        if (content.isEmpty()) {
            throw new RuntimeException("Anthropic response has empty content");
        }

        Object text = content.get(0).get("text");
        if (text == null) {
            throw new RuntimeException("Anthropic response missing text field");
        }

        return text.toString();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
