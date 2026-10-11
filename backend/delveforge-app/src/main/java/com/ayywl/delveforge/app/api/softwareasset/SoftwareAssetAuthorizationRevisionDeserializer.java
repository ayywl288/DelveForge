package com.ayywl.delveforge.app.api.softwareasset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/** 授权预期版本只接受 JSON 整数，防止有损转换替用户指定另一个可更新版本。 */
public final class SoftwareAssetAuthorizationRevisionDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            try {
                return Long.parseLong(parser.getText());
            } catch (NumberFormatException outOfRange) {
                throw InvalidFormatException.from(parser, "Authorization revision is outside the 64-bit range",
                        parser.getText(), Long.class);
            }
        }
        throw InvalidFormatException.from(parser, "Authorization revision must be a JSON integer",
                parser.getText(), Long.class);
    }
}
