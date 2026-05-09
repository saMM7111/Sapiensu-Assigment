package com.sapiensu.sebi.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapiensu.sebi.config.ProcessingConfig;
import com.sapiensu.sebi.model.ProcessingOutput;
import com.sapiensu.sebi.orchestrator.ProcessingOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineRunner implements CommandLineRunner {

    private final ProcessingOrchestrator orchestrator;
    private final ProcessingConfig config;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        Path inputDir = Paths.get(config.getInputDir());

        if (!Files.exists(inputDir)) {
            log.error("Input directory not found: {}", inputDir.toAbsolutePath());
            return;
        }

        List<Path> pdfs = Files.list(inputDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                .sorted()
                .collect(Collectors.toList());

        if (pdfs.isEmpty()) {
            log.warn("No PDFs found in {}", inputDir.toAbsolutePath());
            return;
        }

        log.info("Found {} PDF files. Starting pipeline...", pdfs.size());

        ProcessingOutput output = orchestrator.run(pdfs);

        Path outputDir = Paths.get(config.getOutputDir());
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(config.getOutputFilename());

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputFile.toFile(), output);

        writeProcessingReport(outputDir, output);

        log.info("=========================================");
        log.info("Pipeline complete.");
        log.info("Documents processed   : {}",
                output.getSummary().getTotalDocumentsProcessed());
        log.info("Director change docs  : {}",
                output.getSummary().getDirectorChangeDocumentsIdentified());
        log.info("Total extractions     : {}",
                output.getSummary().getTotalDirectorChangesExtracted());
        log.info("Failed documents      : {}",
                output.getSummary().getDocumentsFailedProcessing().size());
        log.info("Output written to     : {}", outputFile.toAbsolutePath());
        log.info("=========================================");
    }

    private void writeProcessingReport(Path outputDir, ProcessingOutput output) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("SEBI Disclosure Processing Report\n");
            sb.append("=================================\n\n");
            sb.append(String.format("Total documents processed:          %d%n",
                    output.getSummary().getTotalDocumentsProcessed()));
            sb.append(String.format("Director change docs identified:    %d%n",
                    output.getSummary().getDirectorChangeDocumentsIdentified()));
            sb.append(String.format("Total director changes extracted:   %d%n",
                    output.getSummary().getTotalDirectorChangesExtracted()));
            sb.append(String.format("Failed documents:                   %d%n",
                    output.getSummary().getDocumentsFailedProcessing().size()));

            if (!output.getSummary().getDocumentsFailedProcessing().isEmpty()) {
                sb.append("\nFailed Documents:\n");
                output.getSummary().getDocumentsFailedProcessing()
                        .forEach(f -> sb.append("  - ").append(f).append("\n"));
            }

            sb.append("\nPer-Document Extractions:\n");
            sb.append("-".repeat(60)).append("\n");
            output.getExtractions().forEach(e -> {
                sb.append(String.format("  File: %s%n", e.getSourceFilename()));
                sb.append(String.format("    Company:    %s%n", e.getCompanyName()));
                sb.append(String.format("    Director:   %s%n", e.getDirectorName()));
                sb.append(String.format("    Change:     %s%n", e.getChangeType()));
                sb.append(String.format("    Date:       %s%n", e.getEffectiveDate()));
                sb.append(String.format("    Reason:     %s%n", e.getReasonStated()));
                sb.append(String.format("    Confidence: %s%n", e.getExtractionConfidence()));
                sb.append("\n");
            });

            Path reportFile = outputDir.resolve("processing_report.txt");
            Files.writeString(reportFile, sb.toString());
            log.info("Processing report written to: {}", reportFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not write processing report: {}", e.getMessage());
        }
    }
}
