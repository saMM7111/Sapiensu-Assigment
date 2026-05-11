package com.sapiensu.sebi.service;

import com.sapiensu.sebi.model.DisclosureRecord;
import com.sapiensu.sebi.model.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextNormalisationService {


    private static final int CHUNK_SIZE    = 10_000;
    private static final int CHUNK_OVERLAP = 500;

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

        record.setNormalisedText(cleaned);

        record.setChunks(chunkText(cleaned));
        log.info("{}: {} chars split into {} chunk(s)",
            record.getSourceFilename(), cleaned.length(), record.getChunks().size());

        return record;
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= CHUNK_SIZE) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start + (CHUNK_SIZE / 2)) {
                    end = lastNewline;
                }
            }

            chunks.add(text.substring(start, end));

            int nextStart = end - CHUNK_OVERLAP;
            if (nextStart <= start) {         // ← safety guard against infinite loop
                nextStart = start + CHUNK_SIZE;
            }
            start = nextStart;
        }

        return chunks;
    }
}
