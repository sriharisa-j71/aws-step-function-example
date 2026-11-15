SHELL := /bin/bash

NAMESPACE      ?= regtest-demo
FLOCI_SERVICE  ?= floci
JAR            := target/aws-step-functions-floci-demo.jar
TOFU           := tofu -chdir=infra

.PHONY: build init validate plan apply destroy run run-invalid forward clean

build:
	mvn -q clean package

init:
	$(TOFU) init

validate: build init
	$(TOFU) validate

plan: build init
	$(TOFU) plan $(TOFU_ARGS)

apply: build init
	$(TOFU) apply -auto-approve $(TOFU_ARGS)

destroy:
	$(TOFU) destroy -auto-approve

forward:
	kubectl -n $(NAMESPACE) port-forward svc/$(FLOCI_SERVICE) 4566:4566

run:
	@ARN=$$($(TOFU) output -raw state_machine_arn); \
	AWS_ENDPOINT_URL=http://localhost:4566 java -jar $(JAR) "$$ARN" samples/order.json

run-invalid:
	@ARN=$$($(TOFU) output -raw state_machine_arn); \
	AWS_ENDPOINT_URL=http://localhost:4566 java -jar $(JAR) "$$ARN" samples/invalid-order.json

clean:
	rm -rf target
