package com.sapiensu.sebi.rules;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RuleEngine {

    private static final List<Pattern> MINIMUM_PRESENCE = List.of(
        Pattern.compile("(?i)\\bdirector\\b"),
        Pattern.compile("(?i)\\bboard\\b"),
        Pattern.compile("(?i)\\bcessation\\b"),
        Pattern.compile("(?i)\\bappointment\\b"),
        Pattern.compile("(?i)\\bresignation\\b"),
        Pattern.compile("(?i)\\bremoval\\b")
    );

    private static final List<Pattern> STRONG_SIGNALS = List.of(

            // existing patterns
            Pattern.compile(
                    "(?i)resign(ed|ation|s)?\\s+(from|as|of)\\s+(the\\s+)?(board|director)"),

            Pattern.compile(
                    "(?i)appoint(ed|ment)?\\s+(of|as)\\s+.{0,60}(director|board)"),

            Pattern.compile(
                    "(?i)cessation\\s+of\\s+(directorship|office\\s+of\\s+director)"),

            Pattern.compile(
                    "(?i)re-?appoint(ed|ment)?\\s+as\\s+(managing\\s+|independent\\s+)?director"),

            Pattern.compile(
                    "(?i)remov(ed|al)\\s+(of|from)\\s+(the\\s+)?board"),

            Pattern.compile(
                    "(?i)nominat(ed|ion)\\s+(of|as)\\s+.{0,40}director"),

            Pattern.compile(
                    "(?i)pursuant\\s+to\\s+regulation\\s+30.{0,80}(director|board)"),

            Pattern.compile(
                    "(?i)w\\.?e\\.?f\\.?.{0,30}(director|board)"),

            Pattern.compile(
                    "(?i)(vacates?|vacating)\\s+(office|position)\\s+.{0,30}director"),

            Pattern.compile(
                    "(?i)inducted?\\s+(on|to|into)\\s+(the\\s+)?board"),

            // NEW — postal ballot and regularisation language
            Pattern.compile(
                    "(?i)appointment\\s+and\\s+regularis(ation|e)\\s+of\\s+.{0,80}director"),

            Pattern.compile(
                    "(?i)regularis(ation|e)\\s+of.{0,60}director"),

            // NEW — independent/managing/additional director appointment
            Pattern.compile(
                    "(?i)as\\s+an?\\s+(independent|non.?executive|managing|whole.?time|additional)\\s+director"),

            // NEW — appointment/resignation with "director of the company" ending
            Pattern.compile(
                    "(?i)(appoint|resign|remov|ceas).{0,120}director\\s+of\\s+the\\s+company"),

            // NEW — DIN number (Director Identification Number — exclusive to board directors)
            Pattern.compile(
                    "(?i)\\bDIN\\b\\s*[:\\-]?\\s*\\d{8}")
    );

    private static final List<Pattern> EXCLUSION_SIGNALS = List.of(
        Pattern.compile("(?i)chief\\s+financial\\s+officer"),
        Pattern.compile("(?i)\\bCFO\\b"),
        Pattern.compile("(?i)company\\s+secretary"),
        Pattern.compile("(?i)quarterly\\s+(financial\\s+)?results"),
        Pattern.compile("(?i)unaudited\\s+(financial\\s+)?results"),
        Pattern.compile("(?i)trading\\s+window"),
        Pattern.compile("(?i)\\bdividend\\b"),
        Pattern.compile("(?i)\\bbuyback\\b"),
        Pattern.compile("(?i)\\bAGM\\b"),
        Pattern.compile("(?i)\\bEGM\\b")
    );

    public RuleResult evaluate(String chunk, int chunkIndex, int totalChunks) {

        boolean hasPresence = MINIMUM_PRESENCE.stream()
            .anyMatch(p -> p.matcher(chunk).find());

        if (!hasPresence) {
            log.debug("Chunk {}/{}: Gate 1 SKIP — no director keywords",
                chunkIndex, totalChunks);
            return RuleResult.skip("No director-related keywords");
        }

        List<String> hits = STRONG_SIGNALS.stream()
            .filter(p -> p.matcher(chunk).find())
            .map(Pattern::pattern)
            .toList();

        if (hits.isEmpty()) {
            log.debug("Chunk {}/{}: Gate 2 SKIP — keywords present but no strong pattern",
                chunkIndex, totalChunks);
            return RuleResult.skip("Keywords present but no strong signal pattern matched");
        }

        boolean hasExclusion = EXCLUSION_SIGNALS.stream()
            .anyMatch(p -> p.matcher(chunk).find());

        if (hasExclusion) {
            log.info("Chunk {}/{}: PASS_WITH_CAUTION — strong signal + exclusion signal",
                chunkIndex, totalChunks);
            return RuleResult.passWithCaution(hits,
                "CFO/CS or non-director signal alongside strong signal");
        }

        log.info("Chunk {}/{}: PASS — {} strong signal(s) matched",
            chunkIndex, totalChunks, hits.size());
        return RuleResult.pass(hits);
    }
}
