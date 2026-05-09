package com.sapiensu.sebi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ExtractionResult {

    @JsonProperty("source_filename")
    private String sourceFilename;

    @JsonProperty("company_name")
    private String companyName;

    @JsonProperty("stock_ticker")
    private String stockTicker;

    @JsonProperty("director_name")
    private String directorName;

    @JsonProperty("change_type")
    private ChangeType changeType;

    @JsonProperty("effective_date")
    private LocalDate effectiveDate;

    @JsonProperty("reason_stated")
    private String reasonStated;

    @JsonProperty("extraction_confidence")
    private Confidence extractionConfidence;
}
