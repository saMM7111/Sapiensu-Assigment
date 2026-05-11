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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ClassificationServiceCautiousPromptTest {

    @Mock
    private LlmClient llmClient;

    private ClassificationService service;


    private static final String CAUTIOUS_PROMPT_MARKER = "carefully";
    private static final String STANDARD_PROMPT_MARKER = "Classify this disclosure";

    @BeforeEach
    void setUp() {
        RuleEngine ruleEngine = new RuleEngine();
        service = new ClassificationService(llmClient, new ObjectMapper(), ruleEngine);


        ReflectionTestUtils.setField(service, "classifyPromptTemplate",
                "Classify this disclosure. Respond JSON only.\n---\n{text}\n---");
        
        ReflectionTestUtils.setField(service, "classifyPromptCautious",
                "Classify carefully. Focus on board directors only, not CFO or CS. Respond JSON only.\n---\n{text}\n---");
    }

    private DisclosureRecord record(String text) {
        return DisclosureRecord.builder()
                .sourceFilename("test.pdf")
                .normalisedText(text)
                .chunks(List.of(text))
                .status(ProcessingStatus.SUCCESS)
                .build();
    }

    @Test
    void routesToCautiousPromptWhenDinAndCfoAppearTogether() {

        when(llmClient.complete(contains(CAUTIOUS_PROMPT_MARKER)))
                .thenReturn("{\"is_director_change\":false,\"confidence\":\"high\",\"reason\":\"CFO change only\"}");

        DisclosureRecord result = service.classify(
                record("CFO Mr. Rajan steps down effective immediately. " +
                        "Mr. Sharma appointed as Independent Director DIN: 02345678"));

        assertThat(result.isDirectorChange()).isFalse();

        verify(llmClient).complete(contains(CAUTIOUS_PROMPT_MARKER));

        verify(llmClient, never()).complete(contains(STANDARD_PROMPT_MARKER));
    }


    @Test
    void cautiousPromptReturnsTrueWhenDirectorChangeIsReal() {

        when(llmClient.complete(contains(CAUTIOUS_PROMPT_MARKER)))
                .thenReturn("{\"is_director_change\":true,\"confidence\":\"high\"," +
                        "\"reason\":\"Board director appointed, CFO change is separate\"}");

        DisclosureRecord result = service.classify(
                record("The Board announces: (1) Mr. Mehta, CFO, resigns. " +
                        "(2) Ms. Priya Iyer appointed as Non-Executive Independent Director " +
                        "DIN: 07654321 w.e.f. 1st April 2024"));

        assertThat(result.isDirectorChange()).isTrue();
        verify(llmClient).complete(contains(CAUTIOUS_PROMPT_MARKER));
    }


    @Test
    void routesToCautiousPromptWhenCompanySecretaryAndDirectorAppearTogether() {
        when(llmClient.complete(contains(CAUTIOUS_PROMPT_MARKER)))
                .thenReturn("{\"is_director_change\":true,\"confidence\":\"medium\"," +
                        "\"reason\":\"Director resignation confirmed\"}");

        DisclosureRecord result = service.classify(
                record("Mr. Kapoor, Company Secretary, appointed. " +
                        "Mr. Verma resigned from the Board of Directors DIN: 01234567 " +
                        "w.e.f. 15th March 2024"));

        assertThat(result.isDirectorChange()).isTrue();
        verify(llmClient).complete(contains(CAUTIOUS_PROMPT_MARKER));
        verify(llmClient, never()).complete(contains(STANDARD_PROMPT_MARKER));
    }

    @Test
    void purelyCfoChunkWithNoDinIsRejectedByRuleEngineAlone() {

        DisclosureRecord result = service.classify(
                record("Mr. Anand Kumar appointed as Chief Financial Officer " +
                        "effective 1st January 2024"));

        assertThat(result.isDirectorChange()).isFalse();
        verify(llmClient, never()).complete(contains(CAUTIOUS_PROMPT_MARKER));
        verify(llmClient, never()).complete(contains(STANDARD_PROMPT_MARKER));
    }


    @Test
    void cautiousPromptMalformedResponseDefaultsToFalse() {
        when(llmClient.complete(contains(CAUTIOUS_PROMPT_MARKER)))
                .thenReturn("not valid json at all");

        DisclosureRecord result = service.classify(
                record("CFO resignation. Mr. Singh appointed as Independent Director " +
                        "DIN: 09876543"));

        assertThat(result.isDirectorChange()).isFalse();
        assertThat(result.getStatus()).isNotEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void skipsAllPromptsForAlreadyFailedRecord() {
        DisclosureRecord failed = DisclosureRecord.builder()
                .sourceFilename("broken.pdf")
                .status(ProcessingStatus.FAILED)
                .build();

        service.classify(failed);

        verify(llmClient, never()).complete(contains(CAUTIOUS_PROMPT_MARKER));
        verify(llmClient, never()).complete(contains(STANDARD_PROMPT_MARKER));
    }
}