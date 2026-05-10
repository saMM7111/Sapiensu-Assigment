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
@ConditionalOnProperty(name = "llm.provider", havingValue = "groq")
public class GroqClient implements LlmClient {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final RestTemplate restTemplate;
    private final LlmConfig.ProviderProps props;

    public GroqClient(RestTemplate restTemplate, LlmConfig config) {
        this.restTemplate = restTemplate;
        this.props = config.getGroq();
    }

    @Override
    public String complete(String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "max_tokens", props.getMaxTokens(),
                "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(props.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int attempt = 0; attempt <= props.getMaxRetries(); attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        API_URL,
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class
                );
                return extractGroqText(response.getBody());

            } catch (HttpClientErrorException.TooManyRequests e) {
                retryOrThrow(attempt, "Groq rate limited", e);
            } catch (HttpServerErrorException e) {
                retryOrThrow(attempt, "Groq server error " + e.getStatusCode(), e);
            } catch (ResourceAccessException e) {
                retryOrThrow(attempt, "Groq network error", e);
            } catch (HttpClientErrorException e) {
                throw new RuntimeException(
                        "Groq API error " + e.getStatusCode()
                                + ": " + e.getResponseBodyAsString(),
                        e
                );
            }
        }

        throw new RuntimeException("Groq completion failed after all retries");
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
    private String extractGroqText(Map<?, ?> body) {
        if (body == null || !body.containsKey("choices")) {
            throw new RuntimeException("Groq response missing choices");
        }

        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) body.get("choices");
        if (choices.isEmpty()) {
            throw new RuntimeException("Groq response has no choices");
        }

        Map<String, Object> message =
                (Map<String, Object>) choices.get(0).get("message");
        if (message == null || !message.containsKey("content")) {
            throw new RuntimeException("Groq response missing message content");
        }

        return message.get("content").toString();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
