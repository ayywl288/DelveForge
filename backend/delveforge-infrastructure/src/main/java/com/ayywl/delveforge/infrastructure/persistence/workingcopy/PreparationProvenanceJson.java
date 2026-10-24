package com.ayywl.delveforge.infrastructure.persistence.workingcopy;

import com.ayywl.delveforge.domain.evolution.PreparationProvenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 旧行没有准备记录时保持 P=A；新记录不允许以解析失败降级为旧语义。 */
public final class PreparationProvenanceJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PreparationProvenanceJson() {
    }

    public static String write(PreparationProvenance provenance) {
        try {
            return JSON.writeValueAsString(provenance);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode preparation provenance", failure);
        }
    }

    public static PreparationProvenance read(String source, String prepared, String value) {
        if (prepared == null && value == null) {
            return PreparationProvenance.unchanged(source);
        }
        try {
            PreparationProvenance result = JSON.readValue(value, PreparationProvenance.class);
            if (!result.originalRevision().equals(source) || !result.preparedRevision().equals(prepared)) {
                throw new IllegalArgumentException("Persisted preparation revision mismatch");
            }
            return result;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid persisted preparation provenance", failure);
        }
    }
}
