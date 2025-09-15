# ECS IAM Role
resource "aws_iam_role" "ecs_task_iam_role" {
  name = "${var.environment_name}-${var.service_name}-${var.tier}-ecs-task-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement":
  [
    {
      "Sid": "",
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

# Create IAM Policy
resource "aws_iam_policy" "ecs_task_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.ecs_task_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "ecs_task_iam_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.ecs_task_iam_policy.arn
}

# ECS task IAM Policy Document
data "aws_iam_policy_document" "ecs_task_iam_policy_document" {
  statement {
    sid    = "CloudwatchLogPermissions"
    effect = "Allow"

    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutDestination",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]

    resources = ["*"]
  }

  statement {
    sid    = "SSMGetParameters"
    effect = "Allow"

    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParameterHistory",
      "ssm:GetParametersByPath",
    ]

    resources = ["arn:aws:ssm:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:parameter/${var.environment_name}/${var.service_name}-${var.tier}/*"]
  }

  statement {
    sid    = "SecretsManagerPermissions"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "secretsmanager:GetSecretValue",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.docker_hub_credentials_arn,
      data.aws_kms_key.ssm_kms_key.arn,
    ]
  }

  statement {
    sid       = "KMSDecryptSSMSecrets"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["arn:aws:kms:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:key/alias/aws/ssm"]
  }

  statement {
    sid     = "S3GetObject"
    effect  = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectAttributes",
      "s3:GetObjectVersion",
      "s3:GetObjectVersionAttributes"
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn}/*",
      data.terraform_remote_state.upload_service_v2.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.upload_service_v2.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*",

    ]
  }


  statement {
    sid     = "S3PutObject"
    effect  = "Allow"
    actions = ["s3:PutObject"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*",
      
    ]
  }

  statement {
    sid     = "S3ListBucket"
    effect  = "Allow"
    actions = ["s3:ListBucket"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      
    ]
  }

  statement {
    sid     = "S3ListBucketVersions"
    effect  = "Allow"
    actions = ["s3:ListBucketVersions"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
    ]
  }

  statement {
    sid     = "S3DeleteObject"
    effect  = "Allow"
    actions = ["s3:DeleteObject"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*",
    ]
  }

  statement {
    sid     = "S3DeleteObjectVersion"
    effect  = "Allow"
    actions = ["s3:DeleteObjectVersion"]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*",
    ]
  }

  statement {
    sid    = "EC2Permissions"
    effect = "Allow"

    actions = [
      "ec2:DeleteNetworkInterface",
      "ec2:CreateNetworkInterface",
      "ec2:AttachNetworkInterface",
      "ec2:DescribeNetworkInterfaces",
    ]

    resources = ["*"]
  }
}

# Step Function IAM Role
resource "aws_iam_role" "sfn_state_machine_iam_role" {
  name = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-role-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "states.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

# Create IAM Policy
resource "aws_iam_policy" "sfn_state_machine_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.sfn_state_machine_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "sfn_state_machine_iam_policy_attachment" {
  role       = aws_iam_role.sfn_state_machine_iam_role.name
  policy_arn = aws_iam_policy.sfn_state_machine_iam_policy.arn
}

# Step function IAM Policy Document
data "aws_iam_policy_document" "sfn_state_machine_iam_policy_document" {
  statement {
    sid    = "RunTask"
    effect = "Allow"

    actions = [
      "ecs:RunTask",
    ]

    resources = [
      local.discover_publish_task_definition_arn_wildcard_version,
      local.model_publish_task_definition_arn_wildcard_version,
      local.metadata_publish_task_definition_arn_wildcard_version,
    ]
  }

  statement {
    sid    = "TaskControl"
    effect = "Allow"

    actions = [
      "ecs:StopTask",
      "ecs:DescribeTasks",
    ]

    resources = [
      "*",
    ]
  }

  statement {
    sid    = "EventsControl"
    effect = "Allow"

    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule",
    ]

    resources = [
      "arn:aws:events:${data.aws_region.current_region.name}:${data.aws_caller_identity.current.account_id}:rule/StepFunctionsGetEventsForECSTaskRule",
    ]
  }

  statement {
    sid    = "InvokeLambda"
    effect = "Allow"

    actions = [
      "lambda:InvokeFunction",
    ]

    resources = [
      data.terraform_remote_state.discover_pgdump_lambda.outputs.lambda_function_arn,
      data.terraform_remote_state.discover_s3clean_lambda.outputs.lambda_function_arn,
    ]
  }

  statement {
    sid    = "PassRole"
    effect = "Allow"

    actions = [
      "iam:PassRole",
    ]

    resources = [
      aws_iam_role.ecs_task_iam_role.arn,
      data.terraform_remote_state.model_publish.outputs.ecs_task_iam_role_arn,
      data.terraform_remote_state.metadata_publish.outputs.metadata_publish_ecs_task_iam_role_arn,
    ]
  }

  statement {
    sid    = "SQSSendMessages"
    effect = "Allow"

    actions = [
      "sqs:SendMessage",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_queue_arn,
    ]
  }

  statement {
    sid    = "KMSDecryptMessages"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_kms_key_arn,
    ]
  }
}

# The policy document is getting too large. There is a default limit of 6144 characters

# It can be increased by making a request to AWS, but the most expedient way to fix is 
# to make multiple policies and attach it to the role

# Create IAM Policy
resource "aws_iam_policy" "ecs_task_iam_policy_2" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}-2"
  path   = "/"
  policy = data.aws_iam_policy_document.ecs_task_iam_policy_document_2.json
}

# Attach IAM Policy to the same role
resource "aws_iam_role_policy_attachment" "ecs_task_iam_policy_attachment_2" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.ecs_task_iam_policy_2.arn
}

# ECS task IAM Policy Document
data "aws_iam_policy_document" "ecs_task_iam_policy_document_2" {


  statement {
    sid     = "S3GetObject"
    effect  = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectAttributes",
      "s3:GetObjectVersion",
      "s3:GetObjectVersionAttributes"
    ]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_storage_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_storage_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",

    ]
  }


  statement {
    sid     = "S3PutObject"
    effect  = "Allow"
    actions = ["s3:PutObject"]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",
      
    ]
  }

  statement {
    sid     = "S3ListBucket"
    effect  = "Allow"
    actions = ["s3:ListBucket"]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
    ]
  }

  statement {
    sid     = "S3ListBucketVersions"
    effect  = "Allow"
    actions = ["s3:ListBucketVersions"]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
    ]
  }

  statement {
    sid     = "S3DeleteObject"
    effect  = "Allow"
    actions = ["s3:DeleteObject"]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",
    ]
  }

  statement {
    sid     = "S3DeleteObjectVersion"
    effect  = "Allow"
    actions = ["s3:DeleteObjectVersion"]

    resources = [
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",
    ]
  }
}

#
# AWS Open Data S3 Bucket for SPARC
#
resource "aws_iam_policy" "awsod_sparc_s3_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-awsod-sparc-s3-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}-2"
  path   = "/"
  policy = data.aws_iam_policy_document.awsod_sparc_s3_policy_document.json
}

# Attach IAM Policy to the ECS Task role
resource "aws_iam_role_policy_attachment" "awsod_sparc_s3_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.awsod_sparc_s3_policy.arn
}

# ECS task IAM Policy Document
data "aws_iam_policy_document" "awsod_sparc_s3_policy_document" {
  statement {
    sid     = "S3Access"
    effect  = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectAttributes",
      "s3:GetObjectVersion",
      "s3:GetObjectVersionAttributes",
      "s3:ListBucket",
      "s3:ListBucketVersions",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion"
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn}/*"
    ]
  }
}
