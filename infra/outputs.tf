output "state_machine_arn" {
  description = "ARN of the deployed Step Functions state machine."
  value       = aws_sfn_state_machine.orders.arn
}

output "state_machine_name" {
  description = "Name of the deployed Step Functions state machine."
  value       = aws_sfn_state_machine.orders.name
}

output "orders_table_name" {
  description = "DynamoDB table written by the final workflow step."
  value       = aws_dynamodb_table.orders.name
}

output "lambda_functions" {
  description = "Lambda function names created for the three workflow steps."
  value       = { for name, fn in aws_lambda_function.this : name => fn.function_name }
}

output "run_command" {
  description = "Convenience command that starts a sample execution through the CLI runner."
  value       = "AWS_ENDPOINT_URL=${var.floci_endpoint} java -jar ${abspath(var.lambda_jar_path)} ${aws_sfn_state_machine.orders.arn} samples/order.json"
}
