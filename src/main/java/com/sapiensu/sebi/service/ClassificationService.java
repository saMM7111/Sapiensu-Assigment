package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.client.LlmClient;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import com.sapiensu.sebi.rules.RuleEngine;
import com.sapiensu.sebi.rules.RuleResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final RuleEngine ruleEngine;

    @Value("classpath:prompts/classify.txt")
    private Resource classifyPromptResource;

    @Value("classpath:prompts/classify_cautious.txt")
    private Resource classifyPromptCautiousResource;

    private String classifyPromptTemplate;
    private String classifyPromptCautious;

    @PostConstruct
    public void loadPrompts() throws Exception {
        classifyPromptTemplate = classifyPromptResource
                .getContentAsString(StandardCharsets.UTF_8);
        classifyPromptCautious = classifyPromptCautiousResource
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public DisclosureRecord classify(DisclosureRecord record) {
        if (record.getStatus() == ProcessingStatus.FAILED) return record;

        if (record.getChunks() == null || record.getChunks().isEmpty()) {
            log.warn("{}: no chunks available, skipping classification",
                record.getSourceFilename());
            record.setDirectorChange(false);
            return record;
        }

        List<String> chunks = record.getChunks();
        int total    = chunks.size();
        int skipped  = 0;
        int llmCalls = 0;

        for (int i = 0; i < total; i++) {
            String chunk = chunks.get(i);

            RuleResult gate = ruleEngine.evaluate(chunk, i + 1, total);

            if (!gate.shouldProcess()) {
                skipped++;
                continue;
            }

            String prompt = gate.hasCaution()
                ? classifyPromptCautious.replace("{text}", chunk)
                : classifyPromptTemplate.replace("{text}", chunk);

            llmCalls++;

            try {
                String response = llmClient.complete(prompt);
                String cleaned  = stripFences(response);
                JsonNode json   = objectMapper.readTree(cleaned);

                if (json.get("is_director_change").asBoolean()) {
                    log.info("{}: director change confirmed in chunk {}/{}. " +
                        "Skipped {}/{} chunks via rules. LLM calls used: {}",
                        record.getSourceFilename(), i + 1, total,
                        skipped, total, llmCalls);
                    record.setDirectorChange(true);
                    return record;
                }

            } catch (Exception e) {
                log.warn("Classification failed on chunk {}/{} of {}: {}",
                    i + 1, total, record.getSourceFilename(), e.getMessage());
            }
        }

        log.info("{}: no director change found. Total chunks: {}, " +
            "Skipped by rules: {}, LLM calls made: {}",
            record.getSourceFilename(), total, skipped, llmCalls);
        record.setDirectorChange(false);
        return record;
    }

    private String stripFences(String text) {
        if (text == null) return null;

    
        String cleaned = text
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;
    }
}