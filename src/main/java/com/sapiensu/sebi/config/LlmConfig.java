package com.sapiensu.sebi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private String provider;

    private ProviderProps gemini = new ProviderProps();

    private ProviderProps anthropic = new ProviderProps();

    @Data
    public static class ProviderProps {
        private String apiKey;
        private String model;
        private int maxTokens;
        private int maxRetries;
        private long retryDelayMs;
    }
}
