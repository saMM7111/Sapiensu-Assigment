package com.sapiensu.sebi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties
public class SebiProcessorApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(SebiProcessorApplication.class, args);
    }

    private static void loadDotEnv() {
        Path envFile = findEnvFile();
        if (envFile == null) {
            System.out.println("[dotenv] No .env file found — using OS environment variables only.");
            return;
        }
        System.out.println("[dotenv] Loading: " + envFile.toAbsolutePath());
        try {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
               
                if (System.getenv(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (IOException e) {
            System.err.println("[dotenv] Failed to read .env: " + e.getMessage());
        }
    }

    private static Path findEnvFile() {
       
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
            if (dir == null) break;
        }

        try {
            Path classDir = Paths.get(
                    SebiProcessorApplication.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()
            );
            dir = classDir.toAbsolutePath();
            for (int i = 0; i < 6; i++) {
                Path candidate = dir.resolve(".env");
                if (Files.isRegularFile(candidate)) return candidate;
                dir = dir.getParent();
                if (dir == null) break;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return new RestTemplate(factory);
    }
}
