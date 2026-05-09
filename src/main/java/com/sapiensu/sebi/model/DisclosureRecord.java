package com.sapiensu.sebi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DisclosureRecord {

    private String sourceFilename;

    private String rawText;

    private String normalisedText;

    private boolean directorChange;

    private List<ExtractionResult> extractions;

    private ProcessingStatus status;

    private String failureReason;
}
