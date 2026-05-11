package com.sapiensu.sebi.rules;

import lombok.Getter;
import java.util.List;

@Getter
public class RuleResult {

    public enum Decision { PASS, PASS_WITH_CAUTION, SKIP }

    private final Decision decision;
    private final List<String> matchedPatterns;
    private final String reason;

    private RuleResult(Decision decision, List<String> matchedPatterns, String reason) {
        this.decision        = decision;
        this.matchedPatterns = matchedPatterns;
        this.reason          = reason;
    }

    public static RuleResult pass(List<String> matched) {
        return new RuleResult(Decision.PASS, matched, "Strong signal matched");
    }

    public static RuleResult passWithCaution(List<String> matched, String reason) {
        return new RuleResult(Decision.PASS_WITH_CAUTION, matched, reason);
    }

    public static RuleResult skip(String reason) {
        return new RuleResult(Decision.SKIP, List.of(), reason);
    }

    public boolean shouldProcess() {
        return decision == Decision.PASS || decision == Decision.PASS_WITH_CAUTION;
    }

    public boolean hasCaution() {
        return decision == Decision.PASS_WITH_CAUTION;
    }
}
