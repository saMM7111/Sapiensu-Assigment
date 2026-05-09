package com.sapiensu.sebi.service;

import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ExtractionResult;
import com.sapiensu.sebi.model.ProcessingOutput;
import com.sapiensu.sebi.model.ProcessingStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class OutputAggregatorService {

    public ProcessingOutput aggregate(List<DisclosureRecord> records) {

        List<ExtractionResult> allExtractions = records.stream()
                .filter(r -> r.getExtractions() != null)
                .flatMap(r -> r.getExtractions().stream())
                .collect(Collectors.toList());

        List<String> failed = records.stream()
                .filter(r -> r.getStatus() == ProcessingStatus.FAILED
                        || r.getStatus() == ProcessingStatus.EXTRACTION_FAILED)
                .map(DisclosureRecord::getSourceFilename)
                .collect(Collectors.toList());

        long directorChangeDocs = records.stream()
                .filter(DisclosureRecord::isDirectorChange)
                .count();

        ProcessingOutput.Summary summary = ProcessingOutput.Summary.builder()
                .totalDocumentsProcessed(records.size())
                .directorChangeDocumentsIdentified((int) directorChangeDocs)
                .totalDirectorChangesExtracted(allExtractions.size())
                .documentsFailedProcessing(failed)
                .build();

        return ProcessingOutput.builder()
                .extractions(allExtractions)
                .summary(summary)
                .build();
    }
}
