package io.floci.demo.cli;

import io.floci.demo.util.JsonValues;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryRequest;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Starts the deployed state machine against Floci and streams its progress to the console.
 */
public final class WorkflowRunner {

    private static final Set<ExecutionStatus> TERMINAL = EnumSet.of(
            ExecutionStatus.SUCCEEDED,
            ExecutionStatus.FAILED,
            ExecutionStatus.TIMED_OUT,
            ExecutionStatus.ABORTED);

    private WorkflowRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            usage();
            return;
        }

        String stateMachineArn = args.length > 0
                ? args[0]
                : JsonValues.env("STATE_MACHINE_ARN", "");
        if (stateMachineArn.isBlank()) {
            usage();
            System.exit(2);
        }

        Path inputPath = Path.of(args.length > 1 ? args[1] : "samples/order.json");
        String input = Files.readString(inputPath);

        String endpoint = JsonValues.env("AWS_ENDPOINT_URL", "http://localhost:4566");
        String region = JsonValues.env("AWS_REGION", "us-east-1");
        String accessKey = JsonValues.env("AWS_ACCESS_KEY_ID", "test");
        String secretKey = JsonValues.env("AWS_SECRET_ACCESS_KEY", "test");

        try (SfnClient client = SfnClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build()) {

            StartExecutionResponse started = client.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .input(input)
                    .build());

            System.out.println("State machine : " + stateMachineArn);
            System.out.println("Input         : " + inputPath.toAbsolutePath());
            System.out.println("Execution     : " + started.executionArn());
            System.out.println("Status        : RUNNING");
            System.out.println();

            DescribeExecutionResponse execution = poll(client, started.executionArn());

            System.out.println();
            System.out.println("Final status  : " + execution.statusAsString());
            if (execution.output() != null && !execution.output().isBlank()) {
                System.out.println("Output        : " + execution.output());
            }
            if (execution.error() != null) {
                System.out.println("Error         : " + execution.error());
            }
            if (execution.cause() != null) {
                System.out.println("Cause         : " + execution.cause());
            }

            System.out.println();
            System.out.println("States entered:");
            client.getExecutionHistory(GetExecutionHistoryRequest.builder()
                            .executionArn(started.executionArn())
                            .build())
                    .events()
                    .stream()
                    .filter(event -> event.typeAsString().endsWith("StateEntered"))
                    .map(HistoryEvent::stateEnteredEventDetails)
                    .filter(details -> details != null && details.name() != null)
                    .forEach(details -> System.out.println("  - " + details.name()));

            if (execution.status() != ExecutionStatus.SUCCEEDED) {
                System.exit(1);
            }
        }
    }

    private static DescribeExecutionResponse poll(SfnClient client, String executionArn)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        ExecutionStatus lastStatus = null;

        while (System.nanoTime() < deadline) {
            DescribeExecutionResponse execution = client.describeExecution(
                    DescribeExecutionRequest.builder().executionArn(executionArn).build());

            if (execution.status() != lastStatus) {
                lastStatus = execution.status();
                if (lastStatus != ExecutionStatus.RUNNING) {
                    System.out.println("Status        : " + execution.statusAsString());
                }
            }
            if (TERMINAL.contains(execution.status())) {
                return execution;
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("Execution did not finish within 5 minutes: " + executionArn);
    }

    private static void usage() {
        System.out.println("""
                Usage: java -jar target/aws-step-functions-floci-demo.jar <state-machine-arn> [input.json]

                The state machine ARN may also be supplied through STATE_MACHINE_ARN.
                AWS_ENDPOINT_URL defaults to http://localhost:4566 (forward the Floci service first).
                """);
    }
}
