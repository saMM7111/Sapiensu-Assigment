package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.client.LlmClient;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        service = new ClassificationService(llmClient, new ObjectMapper());
        ReflectionTestUtils.setField(service, "classifyPromptTemplate",
                "Classify this disclosure. Respond JSON only.\n---\n{text}\n---");
    }

    private DisclosureRecord record(String text) {
        return DisclosureRecord.builder()
                .sourceFilename("test.pdf")
                .normalisedText(text)
                .status(ProcessingStatus.SUCCESS)
                .build();
    }

    @Test
    void classifiesDirectorResignationAsTrue() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"is_director_change\":true,\"confidence\":\"high\",\"reason\":\"Director resigned\"}");

        DisclosureRecord result = service.classify(record("Mr. Kumar resigned from the Board"));

        assertThat(result.isDirectorChange()).isTrue();
    }

    @Test
    void classifiesCfoChangeAsFalse() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"is_director_change\":false,\"confidence\":\"high\",\"reason\":\"CFO is not a board director\"}");

        DisclosureRecord result = service.classify(record("New CFO appointed"));

        assertThat(result.isDirectorChange()).isFalse();
    }

    @Test
    void defaultsToFalseOnMalformedResponse() {
        when(llmClient.complete(anyString())).thenReturn("not valid json");

        DisclosureRecord result = service.classify(record("some text"));

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

        DisclosureRecord result = service.classify(record("text"));

        assertThat(result.isDirectorChange()).isTrue();
    }
}
