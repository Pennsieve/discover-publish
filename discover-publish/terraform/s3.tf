# Holds the publish input file written by the state machine and read by both
# discover-publish invocations. The input outgrew the 8192 character limit on ECS
# container overrides, so it is passed by reference instead.
#
# Versioning is deliberately left disabled: republishing a dataset version writes the
# same key, and the noncurrent versions that would accumulate are not removed by the
# current-version expiration rule below.
resource "aws_s3_bucket" "publish_input" {
  bucket = "${var.environment_name}-${var.service_name}-${var.tier}-input-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
}

resource "aws_s3_bucket_public_access_block" "publish_input" {
  bucket = aws_s3_bucket.publish_input.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Expiration is the only thing that removes these files, so the window has to outlast the
# longest possible publish. The finalize task reads the file when it starts, which is
# after publish-assets and metadata-publish have both run, and neither the state machine
# nor its states set a timeout.
resource "aws_s3_bucket_lifecycle_configuration" "publish_input" {
  bucket = aws_s3_bucket.publish_input.id

  rule {
    id     = "expire-publish-input"
    status = "Enabled"

    filter {
      prefix = ""
    }

    expiration {
      days = 7
    }
  }
}