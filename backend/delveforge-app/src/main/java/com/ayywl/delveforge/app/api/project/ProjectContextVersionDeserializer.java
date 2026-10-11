package com.ayywl.delveforge.app.api.project;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/** 不允许浮点、字符串或溢出值被转换为另一个 CAS 版本。 */
public final class ProjectContextVersionDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            try {
                long value = Long.parseLong(parser.getText());
                if (value >= 0 && value < Long.MAX_VALUE) {
                    return value;
                }
            } catch (NumberFormatException outOfRange) {
                // 统一返回协议输入错误，不将溢出版本用于条件更新。
            }
        }
        throw InvalidFormatException.from(parser, "Context version must be a nonnegative JSON integer",
                parser.getText(), Long.class);
    }
}
