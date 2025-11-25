# Create IAM Policy for S3 read access
resource "aws_iam_policy" "discover_publish_s3_read_access_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-s3-read-access-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.discover_publish_s3_read_access_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "discover_publish_s3_read_access_iam_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.discover_publish_s3_read_access_iam_policy.arn
}

data "aws_iam_policy_document" "discover_publish_s3_read_access_iam_policy_document" {
  statement {
    sid    = "S3ReadAccess"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:GetObjectAttributes",
      "s3:GetObjectVersion",
      "s3:GetObjectVersionAttributes",
      "s3:GetObjectTagging",
      "s3:GetObjectVersionTagging",
      "s3:ListBucket",
      "s3:ListBucketVersions"
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn}/*",
      data.terraform_remote_state.upload_service_v2.outputs.uploads_bucket_arn,
      "${data.terraform_remote_state.upload_service_v2.outputs.uploads_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.dataset_assets_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",

      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_storage_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_storage_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_storage_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*"

      data.terraform_remote_state.platform_infrastructure.outputs.awsod_edots_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.awsod_edots_publish50_bucket_arn}/*",
    ]
  }
}

# Create IAM Policy for S3 write access
resource "aws_iam_policy" "discover_publish_s3_write_access_iam_policy" {
  name   = "${var.environment_name}-${var.service_name}-${var.tier}-s3-write-access-policy-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  path   = "/"
  policy = data.aws_iam_policy_document.discover_publish_s3_write_access_iam_policy_document.json
}

# Attach IAM Policy
resource "aws_iam_role_policy_attachment" "discover_publish_s3_write_access_iam_policy_attachment" {
  role       = aws_iam_role.ecs_task_iam_role.name
  policy_arn = aws_iam_policy.discover_publish_s3_write_access_iam_policy.arn
}

data "aws_iam_policy_document" "discover_publish_s3_write_access_iam_policy_document" {
  statement {
    sid    = "S3WriteAccess"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:PutObjectTagging",
      "s3:PutObjectVersionTagging",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:DeleteObjectTagging",
      "s3:DeleteObjectVersionTagging",
      "s3:ListBucketMultipartUploads",
      "s3:ListMultipartUploadParts",
      "s3:AbortMultipartUpload"
    ]

    resources = [
      data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_pgdump_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_s3_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.discover_embargo50_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_discover_bucket_arn}/*",
      data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn,
      "${data.terraform_remote_state.africa_south_region.outputs.af_south_s3_embargo_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.sparc_embargo50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.awsod_sparc_publish50_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.rejoin_embargo50_bucket_arn}/*",

      data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_publish50_bucket_arn}/*",
      data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.precision_embargo50_bucket_arn}/*"

      data.terraform_remote_state.platform_infrastructure.outputs.awsod_edots_publish50_bucket_arn,
      "${data.terraform_remote_state.platform_infrastructure.outputs.awsod_edots_publish50_bucket_arn}/*",
    ]
  }
}
