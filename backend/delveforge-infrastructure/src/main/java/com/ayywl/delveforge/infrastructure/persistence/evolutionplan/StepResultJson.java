package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Step 检查点 Value Object 的 JSON 读写；不编码源码、补丁或完整进程输出。 */
final class StepResultJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode Step checkpoint", failure);
        }
    }

    static <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot decode stored Step checkpoint", failure);
        }
    }
}
