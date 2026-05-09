package com.sapiensu.sebi.orchestrator;

import com.sapiensu.sebi.config.ProcessingConfig;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingOutput;
import com.sapiensu.sebi.model.ProcessingStatus;
import com.sapiensu.sebi.service.ClassificationService;
import com.sapiensu.sebi.service.EntityExtractionService;
import com.sapiensu.sebi.service.OutputAggregatorService;
import com.sapiensu.sebi.service.PdfIngestionService;
import com.sapiensu.sebi.service.TextNormalisationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessingOrchestratorTest {

    private PdfIngestionService ingestionService;
    private TextNormalisationService normalisationService;
    private ClassificationService classificationService;
    private EntityExtractionService extractionService;
    private OutputAggregatorService aggregatorService;
    private ProcessingConfig config;

    @BeforeEach
    void setUp() {
        ingestionService = mock(PdfIngestionService.class);
        normalisationService = mock(TextNormalisationService.class);
        classificationService = mock(ClassificationService.class);
        extractionService = mock(EntityExtractionService.class);
        aggregatorService = new OutputAggregatorService();

        config = new ProcessingConfig();
        config.setConcurrency(2);
    }

    @Test
    void runsPipelineForAllPaths() {
        ProcessingOrchestrator orchestrator = new ProcessingOrchestrator(
                ingestionService,
                normalisationService,
                classificationService,
                extractionService,
                aggregatorService,
                config
        );

        DisclosureRecord record = DisclosureRecord.builder()
                .sourceFilename("doc.pdf")
                .directorChange(false)
                .status(ProcessingStatus.SUCCESS)
                .build();

        when(ingestionService.ingest(Path.of("doc.pdf"))).thenReturn(record);
        when(normalisationService.normalise(record)).thenReturn(record);
        when(classificationService.classify(record)).thenReturn(record);
        when(extractionService.extract(record)).thenReturn(record);

        ProcessingOutput output = orchestrator.run(List.of(Path.of("doc.pdf")));

        assertThat(output.getSummary().getTotalDocumentsProcessed()).isEqualTo(1);
        assertThat(output.getSummary().getDirectorChangeDocumentsIdentified()).isEqualTo(0);
    }

    @Test
    void includesFailedDocsInSummary() {
        ProcessingOrchestrator orchestrator = new ProcessingOrchestrator(
                ingestionService,
                normalisationService,
                classificationService,
                extractionService,
                aggregatorService,
                config
        );

        DisclosureRecord failed = DisclosureRecord.builder()
                .sourceFilename("broken.pdf")
                .status(ProcessingStatus.FAILED)
                .build();

        when(ingestionService.ingest(Path.of("broken.pdf"))).thenReturn(failed);
        when(normalisationService.normalise(failed)).thenReturn(failed);
        when(classificationService.classify(failed)).thenReturn(failed);
        when(extractionService.extract(failed)).thenReturn(failed);

        ProcessingOutput output = orchestrator.run(List.of(Path.of("broken.pdf")));

        assertThat(output.getSummary().getDocumentsFailedProcessing()).contains("broken.pdf");
    }
}
