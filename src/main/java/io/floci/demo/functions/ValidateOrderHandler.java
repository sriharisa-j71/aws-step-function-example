package io.floci.demo.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.floci.demo.util.JsonValues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 1: validate the incoming order and shape the payload for the Choice state.
 */
public class ValidateOrderHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        List<String> errors = new ArrayList<>();

        String orderId = JsonValues.string(input, "orderId");
        if (orderId == null || orderId.isBlank()) {
            errors.add("orderId is required");
        }

        List<Map<String, Object>> items = JsonValues.asListOfMaps(input.get("items"));
        if (items.isEmpty()) {
            errors.add("items must contain at least one line");
        }

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String sku = JsonValues.string(item, "sku");
            if (sku == null || sku.isBlank()) {
                errors.add("items[" + i + "].sku is required");
            }
            if (JsonValues.integer(item, "qty") <= 0) {
                errors.add("items[" + i + "].qty must be greater than zero");
            }
            if (JsonValues.decimal(item, "unitPrice").signum() < 0) {
                errors.add("items[" + i + "].unitPrice must not be negative");
            }
        }

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.putIfAbsent("currency", "USD");
        output.putIfAbsent("customer", Map.of("id", "guest", "tier", "STANDARD"));

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("valid", errors.isEmpty());
        validation.put("errors", errors);
        validation.put("validatedAt", Instant.now().toString());
        validation.put("itemCount", items.size());
        output.put("validation", validation);

        if (context != null) {
            context.getLogger().log("Validated order " + orderId + " valid=" + errors.isEmpty()
                    + " errors=" + errors);
        }
        return output;
    }
}
