package com.sapiensu.sebi.service;

import com.sapiensu.sebi.config.ProcessingConfig;
import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextNormalisationService {

    private final ProcessingConfig config;

    public DisclosureRecord normalise(DisclosureRecord record) {
        if (record.getStatus() == ProcessingStatus.FAILED) {
            return record;
        }

        if (record.getRawText() == null) {
            record.setNormalisedText("");
            return record;
        }

        String cleaned = record.getRawText()
                .replaceAll("\\r\\n|\\r", "\n")
                .replaceAll("[\\u00A0\\u2000-\\u200B\\u2028\\u2029\\u202F\\u205F\\u3000]", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .trim();

        int limit = config.getTextTruncationChars();
        if (cleaned.length() > limit) {
            log.warn("Truncating {} from {} chars to {} chars",
                    record.getSourceFilename(), cleaned.length(), limit);
            cleaned = cleaned.substring(0, limit)
                    + "\n[TRUNCATED - remaining content omitted]";
        }

        record.setNormalisedText(cleaned);
        return record;
    }
}
