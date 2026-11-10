package com.sanjeev.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Reads a JSON STRING and nothing else. Jackson normally turns the number 41.0 into the text
 * "41.0" without complaint; for a price we want the type rule to be exact, because a number token
 * is the kind of value that other tools parse as a binary double long before it reaches us.
 *
 * <p>A wrong type is reported as an ordinary "wrong type" error naming the field (HTTP 400).
 */
final class StrictStringDeserializer extends StdDeserializer<String> {

    StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return parser.getText();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}
