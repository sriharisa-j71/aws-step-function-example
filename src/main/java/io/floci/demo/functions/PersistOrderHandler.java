package io.floci.demo.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.floci.demo.util.JsonValues;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step 3: persist the priced order to DynamoDB, also running inside Floci.
 */
public class PersistOrderHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final DynamoDbClient DYNAMODB = buildClient();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String orderId = JsonValues.string(input, "orderId");
        Map<String, Object> validation = JsonValues.asMap(input.get("validation"));
        Map<String, Object> customer = JsonValues.asMap(input.get("customer"));
        Map<String, Object> pricing = JsonValues.asMap(input.get("pricing"));

        String table = JsonValues.env("ORDERS_TABLE", "orders");
        String persistedAt = Instant.now().toString();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("orderId", AttributeValue.fromS(orderId));
        item.put("status", AttributeValue.fromS("PERSISTED"));
        item.put("customerId", AttributeValue.fromS(JsonValues.string(customer, "id", "guest")));
        item.put("tier", AttributeValue.fromS(JsonValues.string(customer, "tier", "STANDARD")));
        item.put("currency", AttributeValue.fromS(JsonValues.string(pricing, "currency", "USD")));
        item.put("itemCount", AttributeValue.fromN(JsonValues.integer(validation, "itemCount") + ""));
        item.put("subtotal", AttributeValue.fromN(number(pricing, "subtotal")));
        item.put("discount", AttributeValue.fromN(number(pricing, "discount")));
        item.put("tax", AttributeValue.fromN(number(pricing, "tax")));
        item.put("total", AttributeValue.fromN(number(pricing, "total")));
        item.put("processedAt", AttributeValue.fromS(JsonValues.string(
                JsonValues.asMap(input.get("enrichment")), "processedAt", persistedAt)));
        item.put("persistedAt", AttributeValue.fromS(persistedAt));

        DYNAMODB.putItem(PutItemRequest.builder().tableName(table).item(item).build());

        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("status", "PERSISTED");
        persistence.put("table", table);
        persistence.put("orderId", orderId);
        persistence.put("total", number(pricing, "total"));
        persistence.put("currency", JsonValues.string(pricing, "currency", "USD"));
        persistence.put("persistedAt", persistedAt);

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("persistence", persistence);

        if (context != null) {
            context.getLogger().log("Persisted order " + orderId + " to " + table);
        }
        return output;
    }

    private static String number(Map<String, Object> source, String key) {
        BigDecimal value = JsonValues.decimal(source, key);
        return value.toPlainString();
    }

    private static DynamoDbClient buildClient() {
        String endpoint = JsonValues.env("AWS_ENDPOINT_URL", "http://floci:4566");
        String region = JsonValues.env("AWS_REGION", "us-east-1");
        String accessKey = JsonValues.env("AWS_ACCESS_KEY_ID", "test");
        String secretKey = JsonValues.env("AWS_SECRET_ACCESS_KEY", "test");

        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
