package com.sapiensu.sebi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProcessingOutput {

    private List<ExtractionResult> extractions;

    private Summary summary;

    @Data
    @Builder
    public static class Summary {

        @JsonProperty("total_documents_processed")
        private int totalDocumentsProcessed;

        @JsonProperty("director_change_documents_identified")
        private int directorChangeDocumentsIdentified;

        @JsonProperty("total_director_changes_extracted")
        private int totalDirectorChangesExtracted;

        @JsonProperty("documents_that_failed_processing")
        private List<String> documentsFailedProcessing;
    }
}
