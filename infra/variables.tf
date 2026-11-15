variable "project_name" {
  description = "Prefix used for every resource created in Floci."
  type        = string
  default     = "floci-sfn-demo"
}

variable "aws_region" {
  description = "Region Floci emulates."
  type        = string
  default     = "us-east-1"
}

variable "floci_endpoint" {
  description = "Floci endpoint reachable from the machine running OpenTofu."
  type        = string
  default     = "http://localhost:4566"
}

variable "lambda_endpoint" {
  description = "Floci endpoint reachable from Lambda pods inside the cluster."
  type        = string
  default     = "http://floci:4566"
}

variable "lambda_jar_path" {
  description = "Shaded jar produced by mvn package; used as the Lambda deployment package."
  type        = string
  default     = "../target/aws-step-functions-floci-demo.jar"
}

variable "orders_table_name" {
  description = "DynamoDB table the final workflow step writes to."
  type        = string
  default     = "sfn-demo-orders"
}
