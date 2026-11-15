package io.floci.demo.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.floci.demo.util.JsonValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Step 2: calculate pricing, apply the customer-tier discount and add tax.
 */
public class EnrichOrderHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        List<Map<String, Object>> items = JsonValues.asListOfMaps(input.get("items"));
        Map<String, Object> customer = JsonValues.asMap(input.get("customer"));

        BigDecimal subtotal = BigDecimal.ZERO;
        int units = 0;
        for (Map<String, Object> item : items) {
            int qty = JsonValues.integer(item, "qty");
            BigDecimal unitPrice = JsonValues.decimal(item, "unitPrice");
            subtotal = subtotal.add(unitPrice.multiply(BigDecimal.valueOf(qty)));
            units += qty;
        }
        subtotal = money(subtotal);

        BigDecimal discountRate = switch (JsonValues.string(customer, "tier", "STANDARD")) {
            case "GOLD" -> new BigDecimal("0.10");
            case "SILVER" -> new BigDecimal("0.05");
            default -> BigDecimal.ZERO;
        };
        BigDecimal discount = money(subtotal.multiply(discountRate));
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal tax = money(taxable.multiply(TAX_RATE));
        BigDecimal total = money(taxable.add(tax));

        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("subtotal", subtotal);
        pricing.put("discountRate", discountRate);
        pricing.put("discount", discount);
        pricing.put("taxRate", TAX_RATE);
        pricing.put("tax", tax);
        pricing.put("total", total);
        pricing.put("currency", JsonValues.string(input, "currency", "USD"));

        Map<String, Object> enrichment = new LinkedHashMap<>();
        enrichment.put("enrichmentId", UUID.randomUUID().toString());
        enrichment.put("processedAt", Instant.now().toString());
        enrichment.put("unitCount", units);

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("pricing", pricing);
        output.put("enrichment", enrichment);

        if (context != null) {
            context.getLogger().log("Enriched order " + JsonValues.string(input, "orderId")
                    + " subtotal=" + subtotal + " total=" + total);
        }
        return output;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
