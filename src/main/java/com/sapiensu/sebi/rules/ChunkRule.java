package com.sapiensu.sebi.rules;

@FunctionalInterface
public interface ChunkRule {
    boolean matches(String chunkText);
}
