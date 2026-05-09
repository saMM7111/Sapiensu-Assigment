package com.sapiensu.sebi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityExtractionServiceTest {

    @Mock
    private LlmClient llmClient;

    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new EntityExtractionService(llmClient, mapper);
        ReflectionTestUtils.setField(service, "extractPromptTemplate",
                "Extract director changes. Respond JSON only.\n---\n{text}\n---");
    }

    private DisclosureRecord directorChangeRecord() {
        return DisclosureRecord.builder()
                .sourceFilename("test.pdf")
                .normalisedText("text")
                .directorChange(true)
                .status(ProcessingStatus.SUCCESS)
                .build();
    }

    @Test
    void extractsSingleDirectorChange() {
        when(llmClient.complete(anyString())).thenReturn(
                "[{\"company_name\":\"Acme Ltd\",\"stock_ticker\":\"ACME\"," +
                "\"director_name\":\"Priya Sharma\",\"change_type\":\"resignation\"," +
                "\"effective_date\":\"2024-03-01\",\"reason_stated\":null," +
                "\"extraction_confidence\":\"high\"}]");

        DisclosureRecord result = service.extract(directorChangeRecord());

        assertThat(result.getExtractions()).hasSize(1);
        assertThat(result.getExtractions().get(0).getDirectorName()).isEqualTo("Priya Sharma");
        assertThat(result.getExtractions().get(0).getSourceFilename()).isEqualTo("test.pdf");
    }

    @Test
    void extractsMultipleChangesFromOneDocument() {
        when(llmClient.complete(anyString())).thenReturn(
                "[{\"company_name\":\"Acme Ltd\",\"stock_ticker\":null," +
                "\"director_name\":\"Ravi Kumar\",\"change_type\":\"resignation\"," +
                "\"effective_date\":null,\"reason_stated\":null," +
                "\"extraction_confidence\":\"medium\"}," +
                "{\"company_name\":\"Acme Ltd\",\"stock_ticker\":null," +
                "\"director_name\":\"Sunita Rao\",\"change_type\":\"appointment\"," +
                "\"effective_date\":\"2024-04-01\",\"reason_stated\":null," +
                "\"extraction_confidence\":\"high\"}]");

        DisclosureRecord result = service.extract(directorChangeRecord());

        assertThat(result.getExtractions()).hasSize(2);
    }

    @Test
    void returnsEmptyListForNonDirectorChangeDocument() {
        DisclosureRecord record = DisclosureRecord.builder()
                .sourceFilename("financial.pdf")
                .normalisedText("text")
                .directorChange(false)
                .status(ProcessingStatus.SUCCESS)
                .build();

        DisclosureRecord result = service.extract(record);

        assertThat(result.getExtractions()).isEmpty();
    }

    @Test
    void setsExtractionFailedStatusOnParseError() {
        when(llmClient.complete(anyString())).thenReturn("completely broken response {{");

        DisclosureRecord result = service.extract(directorChangeRecord());

        assertThat(result.getStatus()).isEqualTo(ProcessingStatus.EXTRACTION_FAILED);
        assertThat(result.getExtractions()).isEmpty();
    }

    @Test
    void handlesNullEffectiveDateWithoutCrashing() {
        when(llmClient.complete(anyString())).thenReturn(
                "[{\"company_name\":\"Test Corp\",\"stock_ticker\":null," +
                "\"director_name\":\"John Doe\",\"change_type\":\"appointment\"," +
                "\"effective_date\":null,\"reason_stated\":null," +
                "\"extraction_confidence\":\"low\"}]");

        DisclosureRecord result = service.extract(directorChangeRecord());

        assertThat(result.getExtractions()).hasSize(1);
        assertThat(result.getExtractions().get(0).getEffectiveDate()).isNull();
    }

    @Test
    void dropsExtractionWithMissingDirectorName() {
        when(llmClient.complete(anyString())).thenReturn(
                "[{\"company_name\":\"Test Corp\",\"stock_ticker\":null," +
                "\"director_name\":\"\",\"change_type\":\"appointment\"," +
                "\"effective_date\":null,\"reason_stated\":null," +
                "\"extraction_confidence\":\"low\"}]");

        DisclosureRecord result = service.extract(directorChangeRecord());

        assertThat(result.getExtractions()).isEmpty();
    }
}
