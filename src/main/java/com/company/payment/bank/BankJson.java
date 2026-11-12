package com.company.payment.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Map;

/** Case-insensitive field lookup in bank JSON (ICICI mixes RESPONSE / Response / response). */
public final class BankJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private BankJson() {}

    /** @return the field's text, or null if the body is not JSON or the field is absent */
    public static String field(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equalsIgnoreCase(key)) return e.getValue().asText();
            }
        } catch (Exception ignored) {
            // not JSON: treated as "field absent"
        }
        return null;
    }
}
