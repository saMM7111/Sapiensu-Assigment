package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.client.LlmClient;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ExtractionResult;
import com.sapiensu.sebi.model.ProcessingStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityExtractionService {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/extract.txt")
    private Resource extractPromptResource;

    private String extractPromptTemplate;

    @PostConstruct
    public void loadPrompt() throws Exception {
        extractPromptTemplate = extractPromptResource
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public DisclosureRecord extract(DisclosureRecord record) {
        if (!record.isDirectorChange()) {
            record.setExtractions(Collections.emptyList());
            return record;
        }

        String prompt = extractPromptTemplate
                .replace("{text}", record.getNormalisedText());

        try {
            String response = llmClient.complete(prompt);
            String cleaned = stripFences(response);

            List<ExtractionResult> results = objectMapper.readValue(
                    cleaned,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, ExtractionResult.class)
            );

            results.forEach(r -> r.setSourceFilename(record.getSourceFilename()));

            results = results.stream()
                    .filter(r -> {
                        if (r.getDirectorName() == null || r.getDirectorName().isBlank()) {
                            log.warn("Dropping extraction from {} - missing director_name",
                                    record.getSourceFilename());
                            return false;
                        }
                        if (r.getChangeType() == null) {
                            log.warn("Dropping extraction from {} - missing change_type for {}",
                                    record.getSourceFilename(), r.getDirectorName());
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            record.setExtractions(results);
            log.info("Extracted {} director change(s) from {}",
                    results.size(), record.getSourceFilename());

        } catch (Exception e) {
            log.error("Extraction failed for {}: {}",
                    record.getSourceFilename(), e.getMessage());
            record.setExtractions(Collections.emptyList());
            record.setStatus(ProcessingStatus.EXTRACTION_FAILED);
        }

        return record;
    }

    private String stripFences(String text) {
        return text.replaceAll("(?s)```json\\s*|```", "").trim();
    }
}
