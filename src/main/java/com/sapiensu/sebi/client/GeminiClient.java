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
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiClient implements LlmClient {

    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestTemplate restTemplate;
    private final LlmConfig.ProviderProps props;

    public GeminiClient(RestTemplate restTemplate, LlmConfig config) {
        this.restTemplate = restTemplate;
        this.props = config.getGemini();
    }

    @Override
    public String complete(String userPrompt) {
        String url = BASE_URL + props.getModel()
                + ":generateContent?key=" + props.getApiKey();

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", userPrompt)))
                ),
                "generationConfig", Map.of(
                        "maxOutputTokens", props.getMaxTokens(),
                        "temperature", 0.1
                )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int attempt = 0; attempt <= props.getMaxRetries(); attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        url,
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class
                );
                return extractGeminiText(response.getBody());

            } catch (HttpClientErrorException.TooManyRequests e) {
                retryOrThrow(attempt, "Gemini rate limited", e);
            } catch (HttpServerErrorException e) {
                retryOrThrow(attempt, "Gemini server error " + e.getStatusCode(), e);
            } catch (ResourceAccessException e) {
                retryOrThrow(attempt, "Gemini network error", e);
            } catch (HttpClientErrorException e) {
                throw new RuntimeException(
                        "Gemini API error " + e.getStatusCode()
                                + ": " + e.getResponseBodyAsString(),
                        e
                );
            }
        }

        throw new RuntimeException("Gemini completion failed after all retries");
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
    private String extractGeminiText(Map<?, ?> body) {
        if (body == null || !body.containsKey("candidates")) {
            throw new RuntimeException("Gemini response missing candidates");
        }

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) body.get("candidates");
        if (candidates.isEmpty()) {
            throw new RuntimeException("Gemini response has no candidates");
        }

        Map<String, Object> content =
                (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("Gemini response missing content parts");
        }

        return (String) parts.get(0).get("text");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
