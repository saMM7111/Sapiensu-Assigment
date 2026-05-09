package com.sapiensu.sebi.orchestrator;

import com.sapiensu.sebi.config.ProcessingConfig;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingOutput;
import com.sapiensu.sebi.service.ClassificationService;
import com.sapiensu.sebi.service.EntityExtractionService;
import com.sapiensu.sebi.service.OutputAggregatorService;
import com.sapiensu.sebi.service.PdfIngestionService;
import com.sapiensu.sebi.service.TextNormalisationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingOrchestrator {

    private final PdfIngestionService ingestionService;
    private final TextNormalisationService normalisationService;
    private final ClassificationService classificationService;
    private final EntityExtractionService extractionService;
    private final OutputAggregatorService aggregatorService;
    private final ProcessingConfig config;

    public ProcessingOutput run(List<Path> pdfPaths) {
        log.info("Starting pipeline: {} documents, concurrency={}",
                pdfPaths.size(), config.getConcurrency());

        ForkJoinPool pool = new ForkJoinPool(config.getConcurrency());

        try {
            List<DisclosureRecord> records = pool.submit(() ->
                    pdfPaths.parallelStream()
                            .map(this::processSingle)
                            .collect(Collectors.toList())
            ).get();

            return aggregatorService.aggregate(records);

        } catch (Exception e) {
            throw new RuntimeException("Pipeline execution failed: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    private DisclosureRecord processSingle(Path path) {
        DisclosureRecord record = ingestionService.ingest(path);
        record = normalisationService.normalise(record);
        record = classificationService.classify(record);
        record = extractionService.extract(record);
        return record;
    }
}
