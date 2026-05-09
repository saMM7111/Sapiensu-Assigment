package com.sapiensu.sebi.service;

import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Slf4j
@Service
public class PdfIngestionService {

    public DisclosureRecord ingest(Path pdfPath) {
        String filename = pdfPath.getFileName().toString();
        log.info("Ingesting: {}", filename);

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String rawText = stripper.getText(doc);

            if (rawText == null || rawText.isBlank()) {
                log.warn("No text extracted from {} — likely a scanned image PDF", filename);
                return DisclosureRecord.builder()
                        .sourceFilename(filename)
                        .status(ProcessingStatus.FAILED)
                        .failureReason("No text extracted — likely a scanned image PDF")
                        .build();
            }

            if (rawText.trim().length() < 50) {
                log.warn("Very short text ({} chars) from {} — possibly a partial scan",
                        rawText.trim().length(), filename);
            }

            return DisclosureRecord.builder()
                    .sourceFilename(filename)
                    .rawText(rawText)
                    .status(ProcessingStatus.SUCCESS)
                    .build();

        } catch (Exception e) {
            log.error("Failed to ingest {}: {}", filename, e.getMessage());
            return DisclosureRecord.builder()
                    .sourceFilename(filename)
                    .status(ProcessingStatus.FAILED)
                    .failureReason(e.getMessage())
                    .build();
        }
    }
}
