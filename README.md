# AWS Step Functions on Floci — 3-step order workflow

An end-to-end example that runs a real AWS Step Functions workflow against
[Floci](https://floci.io) on minikube, with Java 25 Lambda tasks deployed by
OpenTofu.

## What it demonstrates

- A Standard state machine with **three Task states**, a **Choice**, retries,
  catches and Succeed/Fail terminals.
- Data flowing between states: each Lambda receives the previous step's output.
- Direct DynamoDB persistence from the last Lambda step, also backed by Floci.
- Java 25 Lambda functions built with Maven (AWS SDK for Java v2).
- Infrastructure as code with OpenTofu and the `hashicorp/aws` provider pointed
  at the Floci endpoint.

```text
samples/order.json
        |
        v
  StartExecution
        |
        v
 [1] ValidateOrder ──(Choice: validation.valid)──> [2] EnrichOrder ──> [3] PersistOrder ──> Succeed
        |                                                  |                    |
        └──────────── invalid / error -> RejectOrder (Fail) <───────────────────┘
```

| Step | State | Implementation | Effect |
| --- | --- | --- | --- |
| 1 | `ValidateOrder` | `ValidateOrderHandler` | Checks order id, line items, quantities and prices; produces `validation` |
| 2 | `EnrichOrder` | `EnrichOrderHandler` | Computes subtotal, tier discount, tax and total into `pricing` |
| 3 | `PersistOrder` | `PersistOrderHandler` | Writes the priced order to the `sfn-demo-orders` DynamoDB table |

## Environment analysis

The target cluster already has everything this demo needs:

- Namespace `regtest-demo` with `floci` (`floci/floci:2.2.0`) on port `4566`.
- Floci reports the `states` (Step Functions) service as running. Its ASL
  interpreter runs in-process, so no extra container is needed for the workflow.
- Floci is configured with `FLOCI_SERVICES_LAMBDA_EXECUTOR=kubernetes`, so each
  Lambda execution environment becomes a pod in `regtest-demo`. The first
  invocation of a `java25` function pulls `public.ecr.aws/lambda/java:25`.
- IAM policy enforcement is disabled, but Lambda and Step Functions still
  require role ARNs, so the stack creates them.
- The existing `postgres` service is not part of this demo; persistence is done
  in the Floci DynamoDB service.

Two endpoints are used on purpose:

| Caller | Endpoint | Why |
| --- | --- | --- |
| OpenTofu / CLI runner (host) | `http://localhost:4566` | `kubectl port-forward svc/floci 4566:4566` |
| Lambda pods (in cluster) | `http://floci:4566` | Pod-to-pod service DNS |

## Project layout

```text
.
├── pom.xml                         # Java 25 + AWS SDK v2 + shaded jar
├── Makefile
├── samples/
│   ├── order.json                  # valid order
│   └── invalid-order.json          # forces RejectOrder
├── src/main/java/io/floci/demo/
│   ├── functions/                  # the three Lambda handlers
│   ├── cli/WorkflowRunner.java     # StartExecution + poll + print history
│   └── util/JsonValues.java
└── infra/
    ├── versions.tf                 # AWS provider pointed at Floci
    ├── main.tf                     # S3 jar, IAM, DynamoDB, Lambdas, state machine
    ├── variables.tf
    ├── outputs.tf
    └── state-machine.asl.json.tftpl
```

The shaded jar is used twice: it is the Lambda deployment package (classes at
the archive root) and the executable CLI runner.

## Prerequisites

- JDK 25 and Maven 3.9+
- OpenTofu
- AWS CLI v2 (for the `local` profile)
- `kubectl` access to the cluster and the `regtest-demo` namespace

Forward Floci to the host in a separate terminal:

```bash
kubectl -n regtest-demo port-forward svc/floci 4566:4566
```

## AWS CLI and VS Code profile

Create a `local` profile that targets Floci. Append this to `~/.aws/config`:

```ini
[profile local]
region = us-east-1
output = json
endpoint_url = http://localhost:4566

s3 =
    addressing_style = path
```

Append this to `~/.aws/credentials`:

```ini
[local]
aws_access_key_id = test
aws_secret_access_key = test
```

Verify the profile:

```bash
AWS_PROFILE=local aws sts get-caller-identity
AWS_PROFILE=local aws stepfunctions list-state-machines
AWS_PROFILE=local aws dynamodb scan --table-name sfn-demo-orders
```

Select `local` in the AWS Toolkit for VS Code (`AWS: Select Profile`). If the
Toolkit ignores the profile's `endpoint_url`, launch VS Code with:

```bash
AWS_PROFILE=local AWS_ENDPOINT_URL=http://localhost:4566 code
```

## Build, plan, apply

```bash
make build                 # mvn clean package -> Java 25 shaded jar
make init                  # tofu init
make plan                  # tofu plan
make apply                 # tofu apply -auto-approve
```

The plan is:

```text
Plan: 9 to add, 0 to change, 0 to destroy.
```

`make apply` creates nine resources: an S3 bucket and jar object, two IAM roles,
a DynamoDB table, three Lambda functions and one Step Functions state machine:

```text
Apply complete! Resources: 9 added, 0 changed, 0 destroyed.
```

The three functions are created with runtime `java25` and report `Active`:

```bash
AWS_PROFILE=local aws lambda list-functions \
  --query 'Functions[].{Name:FunctionName,Runtime:Runtime,State:State}' \
  --output table
```

## Run the workflow

```bash
make run                   # samples/order.json
make run-invalid           # samples/invalid-order.json -> OrderRejected
```

The runner starts the execution, polls until it is terminal, prints the final
output and lists the states that were entered. A successful run looks like:

```text
Status: SUCCEEDED

States entered:
  - ValidateOrder
  - OrderValid
  - EnrichOrder
  - PersistOrder
  - OrderPersisted
```

The invalid sample exercises the rejection branch:

```text
Status: FAILED
Error : OrderRejected

States entered:
  - ValidateOrder
  - OrderValid
  - RejectOrder
```

You can also get the ARN and run it directly:

```bash
tofu -chdir=infra output -raw state_machine_arn
AWS_ENDPOINT_URL=http://localhost:4566 \
  java -jar target/aws-step-functions-floci-demo.jar <state-machine-arn> samples/order.json
```

Inspect the persisted rows and the execution:

```bash
AWS_PROFILE=local aws dynamodb scan --table-name sfn-demo-orders
AWS_PROFILE=local aws stepfunctions list-state-machines
AWS_PROFILE=local aws stepfunctions list-executions \
  --state-machine-arn "$(tofu -chdir=infra output -raw state_machine_arn)"
```

Without the `local` profile, add
`--endpoint-url http://localhost:4566` to each command instead.

## Verified results

The stack was deployed and re-run end to end against Floci:

- `tofu plan` reports no drift after apply.
- `floci-sfn-demo-validate`, `floci-sfn-demo-enrich` and
  `floci-sfn-demo-persist` are `Active` with runtime `java25`.
- The valid execution succeeds through `ValidateOrder`, `OrderValid`,
  `EnrichOrder`, `PersistOrder` and `OrderPersisted`.
- The invalid execution fails at `RejectOrder` with error `OrderRejected`.
- The final step writes one row to DynamoDB:

```json
{
  "orderId": "ORD-1001",
  "status": "PERSISTED",
  "customerId": "CUST-42",
  "tier": "GOLD",
  "currency": "USD",
  "subtotal": "139",
  "discount": "13.9",
  "tax": "10.01",
  "total": "135.11",
  "itemCount": "2"
}
```

## Useful overrides

All OpenTofu variables have defaults in `infra/variables.tf`:

```bash
make plan TOFU_ARGS='-var="project_name=my-demo"'
```

For a different host endpoint, port-forward on another port and pass:

```bash
tofu -chdir=infra plan -var="floci_endpoint=http://localhost:4567"
```

## Reinstall from scratch

A fresh reinstall destroys all nine resources, recreates them and runs the
workflow again. This also clears the DynamoDB table, so `make run` repopulates
it:

```bash
make destroy
make build
make apply
make run

AWS_PROFILE=local aws dynamodb scan --table-name sfn-demo-orders
```

Expected apply and run summaries:

```text
Destroy complete! Resources: 9 destroyed.
Apply complete!   Resources: 9 added, 0 changed, 0 destroyed.
...
Status: SUCCEEDED
```

## Cleanup

```bash
make destroy
```
