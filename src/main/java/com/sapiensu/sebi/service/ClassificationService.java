package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.client.LlmClient;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/classify.txt")
    private Resource classifyPromptResource;

    private String classifyPromptTemplate;

    @PostConstruct
    public void loadPrompt() throws Exception {
        classifyPromptTemplate = classifyPromptResource
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public DisclosureRecord classify(DisclosureRecord record) {
        if (record.getStatus() == ProcessingStatus.FAILED) {
            return record;
        }

        String prompt = classifyPromptTemplate
                .replace("{text}", record.getNormalisedText());

        try {
            String response = llmClient.complete(prompt);
            String cleaned = stripFences(response);
            JsonNode json = objectMapper.readTree(cleaned);

            boolean isDirectorChange = json.get("is_director_change").asBoolean();
            record.setDirectorChange(isDirectorChange);
            log.info("Classified {} => director_change={}",
                    record.getSourceFilename(), isDirectorChange);
        } catch (Exception e) {
            log.warn("Classification parse failed for {}, defaulting to false: {}",
                    record.getSourceFilename(), e.getMessage());
            record.setDirectorChange(false);
        }

        return record;
    }

    private String stripFences(String text) {
        return text.replaceAll("(?s)```json\\s*|```", "").trim();
    }
}
