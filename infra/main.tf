locals {
  lambda_functions = {
    validate = {
      handler     = "io.floci.demo.functions.ValidateOrderHandler::handleRequest"
      description = "Step 1 - validate incoming order lines"
    }
    enrich = {
      handler     = "io.floci.demo.functions.EnrichOrderHandler::handleRequest"
      description = "Step 2 - price the order, apply tier discount and tax"
    }
    persist = {
      handler     = "io.floci.demo.functions.PersistOrderHandler::handleRequest"
      description = "Step 3 - persist the priced order to DynamoDB"
    }
  }
}

resource "aws_s3_bucket" "lambda_code" {
  bucket        = "${var.project_name}-lambda-code"
  force_destroy = true
}

resource "aws_s3_object" "lambda_jar" {
  bucket      = aws_s3_bucket.lambda_code.id
  key         = "aws-step-functions-floci-demo.jar"
  source      = var.lambda_jar_path
  source_hash = filebase64sha256(var.lambda_jar_path)
}

resource "aws_iam_role" "lambda" {
  name = "${var.project_name}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role" "sfn" {
  name = "${var.project_name}-sfn-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "states.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_dynamodb_table" "orders" {
  name         = var.orders_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "orderId"

  attribute {
    name = "orderId"
    type = "S"
  }
}

resource "aws_lambda_function" "this" {
  for_each = local.lambda_functions

  function_name    = "${var.project_name}-${each.key}"
  description      = each.value.description
  role             = aws_iam_role.lambda.arn
  handler          = each.value.handler
  runtime          = "java25"
  timeout          = 60
  memory_size      = 1024
  s3_bucket        = aws_s3_bucket.lambda_code.id
  s3_key           = aws_s3_object.lambda_jar.key
  source_code_hash = filebase64sha256(var.lambda_jar_path)

  environment {
    variables = {
      AWS_ENDPOINT_URL = var.lambda_endpoint
      ORDERS_TABLE     = aws_dynamodb_table.orders.name
    }
  }

  depends_on = [aws_s3_object.lambda_jar]
}

resource "aws_sfn_state_machine" "orders" {
  name     = "${var.project_name}-workflow"
  role_arn = aws_iam_role.sfn.arn
  type     = "STANDARD"

  definition = templatefile("${path.module}/state-machine.asl.json.tftpl", {
    validate_order_arn = aws_lambda_function.this["validate"].arn
    enrich_order_arn   = aws_lambda_function.this["enrich"].arn
    persist_order_arn  = aws_lambda_function.this["persist"].arn
  })
}
