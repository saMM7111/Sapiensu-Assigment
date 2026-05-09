package com.sapiensu.sebi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "processing")
public class ProcessingConfig {

    private String inputDir;

    private String outputDir;

    private String outputFilename;

    private int concurrency;

    private int textTruncationChars;
}
