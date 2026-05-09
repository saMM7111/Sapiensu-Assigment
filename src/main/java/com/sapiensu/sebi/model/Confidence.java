package com.sapiensu.sebi.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Confidence {

    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    Confidence(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Confidence fromValue(String value) {
        for (Confidence c : values()) {
            if (c.value.equalsIgnoreCase(value))
                return c;
        }

        return LOW;
    }
}
