locals {
  common_environment_override = [
    { Name = "DOI",                  "Value.$" = "$.doi" },
    { Name = "ORGANIZATION_ID",      "Value.$" = "$.organization_id" },
    { Name = "ORGANIZATION_NODE_ID", "Value.$" = "$.organization_node_id" },
    { Name = "ORGANIZATION_NAME",    "Value.$" = "$.organization_name" },
    { Name = "DATASET_ID",           "Value.$" = "$.dataset_id" },
    { Name = "DATASET_NODE_ID",      "Value.$" = "$.dataset_node_id" },
    { Name = "PUBLISHED_DATASET_ID", "Value.$" = "$.published_dataset_id" },
    { Name = "USER_ID",              "Value.$" = "$.user_id" },
    { Name = "USER_NODE_ID",         "Value.$" = "$.user_node_id" },
    { Name = "USER_FIRST_NAME",      "Value.$" = "$.user_first_name" },
    { Name = "USER_LAST_NAME",       "Value.$" = "$.user_last_name" },
    { Name = "USER_ORCID",           "Value.$" = "$.user_orcid" },
    { Name = "S3_PUBLISH_KEY",       "Value.$" = "$.s3_publish_key" },
    { Name = "S3_BUCKET",            "Value.$" = "$.s3_bucket" }
  ]

  discover_publish_environment_override = concat(local.common_environment_override, [
    { Name = "CONTRIBUTORS",          "Value.$" = "$.contributors" },
    { Name = "COLLECTIONS",           "Value.$" = "$.collections" },
    { Name = "EXTERNAL_PUBLICATIONS", "Value.$" = "$.external_publications" },
    { Name = "VERSION",               "Value.$" = "$.version" },
    { Name = "WORKFLOW_ID",           "Value.$" = "$.workflow_id" },
    { Name = "EXPECT_PREVIOUS",       "Value.$" = "$.expect_previous" }
  ])

  step_function_variables = {
    fargate_ecs_cluster_arn                 = data.terraform_remote_state.fargate.outputs.ecs_cluster_arn
    discover_publish_queue_id               = data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_queue_id

    discover_s3clean_lambda_arn             = data.terraform_remote_state.discover_s3clean_lambda.outputs.lambda_function_arn
    discover_pgdump_lambda_arn              = data.terraform_remote_state.discover_pgdump_lambda.outputs.lambda_function_arn

    discover_publish_task_definition_family = local.discover_publish_task_definition_family
    metadata_publish_task_definition_family = local.metadata_publish_task_definition_family

    common_environment_override             = local.common_environment_override
    discover_publish_environment_override   = local.discover_publish_environment_override

    ecs_network = {
      AwsvpcConfiguration = {
        Subnets        = data.terraform_remote_state.vpc.outputs.private_subnet_ids
        AssignPublicIp = "DISABLED"
        SecurityGroups = [data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_security_group_id]
      }
    }
  }

  step_function_definition = templatefile("${path.module}/step-function.json", local.step_function_variables)
}

resource "aws_sfn_state_machine" "sfn_state_machine" {
  name     = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  role_arn = aws_iam_role.sfn_state_machine_iam_role.arn
  definition = local.step_function_definition
}
