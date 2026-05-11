package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.client.LlmClient;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import com.sapiensu.sebi.rules.RuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassificationServiceTest {

    @Mock
    private LlmClient llmClient;

    private ClassificationService service;

    @BeforeEach
    void setUp() {
        // RuleEngine is a real instance — not mocked
        // Tests need real rule evaluation to verify end-to-end behaviour
        RuleEngine ruleEngine = new RuleEngine();

        service = new ClassificationService(llmClient, new ObjectMapper(), ruleEngine);

        ReflectionTestUtils.setField(service, "classifyPromptTemplate",
                "Classify this disclosure. Respond JSON only.\n---\n{text}\n---");
        ReflectionTestUtils.setField(service, "classifyPromptCautious",
                "Classify carefully. Respond JSON only.\n---\n{text}\n---");
    }

    // helper — builds a record WITH chunks populated
    // chunks are required now because ClassificationService iterates chunks,
    // not normalisedText directly
    private DisclosureRecord record(String text) {
        return DisclosureRecord.builder()
                .sourceFilename("test.pdf")
                .normalisedText(text)
                .chunks(List.of(text))   // ← single chunk containing the full text
                .status(ProcessingStatus.SUCCESS)
                .build();
    }

    @Test
    void classifiesDirectorResignationAsTrue() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"is_director_change\":true,\"confidence\":\"high\",\"reason\":\"Director resigned\"}");

        // text must pass the rule engine gate — use real director resignation language
        DisclosureRecord result = service.classify(
                record("Mr. Kumar resigned from the Board of Directors w.e.f. 1st March 2024"));

        assertThat(result.isDirectorChange()).isTrue();
    }

    @Test
    void classifiesCfoChangeAsFalse() {
        // CFO text will be skipped by rule engine Gate 2 (no strong signal)
        // so LLM is never called — result is false from rule engine alone
        DisclosureRecord result = service.classify(
                record("New CFO appointed to manage financial operations"));

        assertThat(result.isDirectorChange()).isFalse();
    }

    @Test
    void defaultsToFalseOnMalformedResponse() {
        when(llmClient.complete(anyString())).thenReturn("not valid json");

        // text must pass rule engine to reach the LLM
        DisclosureRecord result = service.classify(
                record("Mr. Sharma resigned from the Board w.e.f. today"));

        assertThat(result.isDirectorChange()).isFalse();
        assertThat(result.getStatus()).isNotEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void skipsApiCallForAlreadyFailedRecord() {
        DisclosureRecord failed = DisclosureRecord.builder()
                .sourceFilename("broken.pdf")
                .status(ProcessingStatus.FAILED)
                .build();

        service.classify(failed);

        verify(llmClient, never()).complete(anyString());
    }

    @Test
    void stripsMarkdownFencesFromResponse() {
        when(llmClient.complete(anyString()))
                .thenReturn("```json\n{\"is_director_change\":true,\"confidence\":\"high\",\"reason\":\"test\"}\n```");

        DisclosureRecord result = service.classify(
                record("Mr. Patel appointed as Independent Director of the Company DIN: 12345678"));

        assertThat(result.isDirectorChange()).isTrue();
    }

    @Test
    void skipsChunkThatFailsRuleEngineGate() {
        // financial results text — should be skipped by rule engine, LLM never called
        DisclosureRecord result = service.classify(
                record("Unaudited financial results for Q3 FY2024 revenue increased 12 percent"));

        assertThat(result.isDirectorChange()).isFalse();
        verify(llmClient, never()).complete(anyString());
    }

    @Test
    void handlesMultipleChunksAndFindsChangeInSecondChunk() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"is_director_change\":true,\"confidence\":\"high\",\"reason\":\"Director appointed\"}");

        // first chunk has no director content — skipped by rule engine
        // second chunk has a strong signal — passes to LLM
        DisclosureRecord record = DisclosureRecord.builder()
                .sourceFilename("multi.pdf")
                .chunks(List.of(
                        "Quarterly financial results revenue grew 15 percent this quarter",
                        "Mr. Raj Nagesh appointed as Independent Director DIN: 02157439"
                ))
                .status(ProcessingStatus.SUCCESS)
                .build();

        DisclosureRecord result = service.classify(record);

        assertThat(result.isDirectorChange()).isTrue();
    }
}