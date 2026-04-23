# ECS Fargate Deployment Guide

This guide explains how to deploy the S3 Copy Machine as an ECS Task on AWS Fargate.

## Overview

The S3 Copy Machine can be run as an ECS Fargate task, allowing you to:
- Execute bulk S3 copy operations in a serverless, containerized environment
- Trigger copy operations from Step Functions, EventBridge, or manually
- Scale automatically without managing servers
- Pay only for the resources used during execution

## Prerequisites

1. **AWS Account** with permissions to create ECS resources
2. **Docker image** of the application pushed to ECR or Docker Hub
3. **IAM roles** configured for ECS task execution and S3 access
4. **VPC** with subnets (public or private with NAT gateway for S3 access)

## Architecture

```
CSV File (S3 or Local) → ECS Fargate Task → S3 Copy Operations → Destination S3 Buckets
                              ↓
                       CloudWatch Logs
```

## Step 1: Build and Push Docker Image

### Build and Push to Docker Hub (Recommended)

```bash
# Build and push in one command
./scripts/build-csv-s3-ops-image.sh push latest

# This will:
# 1. Build the assembly JAR
# 2. Build the Docker image
# 3. Tag it as: pennsieve/s3-ops-machine:latest
# 4. Prompt for Docker Hub login
# 5. Push to Docker Hub
```

**Note**: For private Docker Hub repositories, you'll need to store your Docker Hub credentials in AWS Secrets Manager (see Step 2b below).

### Alternative: Push to ECR

If you prefer using AWS ECR instead:

```bash
# Set ECR configuration
export ECR_REGISTRY=<account-id>.dkr.ecr.us-east-1.amazonaws.com
export AWS_REGION=us-east-1

# Create ECR repository (if not exists)
aws ecr create-repository --repository-name s3-ops-machine --region $AWS_REGION

# Build and push to ECR
./scripts/build-csv-s3-ops-image.sh push-ecr latest
```

## Step 2: Create IAM Roles and Docker Hub Credentials

### Step 2a: Store Docker Hub Credentials (if using private repository)

If your Docker Hub repository is private, store credentials in AWS Secrets Manager:

```bash
# Store Docker Hub credentials in Secrets Manager
aws secretsmanager create-secret \
  --name docker-hub-credentials \
  --description "Docker Hub credentials for ECS" \
  --secret-string '{
    "username": "your-docker-hub-username",
    "password": "your-docker-hub-password-or-token"
  }' \
  --region us-east-1

# Note the ARN from the output - you'll need it for the task definition
```

**Security Best Practice**: Use a Docker Hub access token instead of your password. Create one at: https://hub.docker.com/settings/security

**For Public Images**: If your image is public on Docker Hub, you can skip this step and remove the `repositoryCredentials` section from the task definition.

### Step 2b: Task Execution Role

This role allows ECS to pull the Docker image from Docker Hub and write logs.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:CreateLogGroup"
      ],
      "Resource": "*"
    }
  ]
}
```

**Note**: If using ECR instead of Docker Hub, replace the Secrets Manager permission with ECR permissions:
```json
{
  "Effect": "Allow",
  "Action": [
    "ecr:GetAuthorizationToken",
    "ecr:BatchCheckLayerAvailability",
    "ecr:GetDownloadUrlForLayer",
    "ecr:BatchGetImage"
  ],
  "Resource": "*"
}
```

### Step 2c: Task Role

This role allows the application to access S3 buckets.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:GetObjectAttributes",
        "s3:GetObjectVersion"
      ],
      "Resource": [
        "arn:aws:s3:::source-bucket/*",
        "arn:aws:s3:::csv-config-bucket/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": "arn:aws:s3:::destination-bucket/*"
    }
  ]
}
```

## Step 3: Create Task Definition

Use the provided task definition template at `discover-publish/terraform/csv-s3-ops-task-definition.json`.

### Register Task Definition via AWS CLI

```bash
aws ecs register-task-definition \
  --cli-input-json file://discover-publish/terraform/csv-s3-ops-task-definition.json \
  --region us-east-1
```

### Using Terraform

The task definition template uses Terraform variables. Example usage:

```hcl
# Docker Hub credentials secret (only needed for private repositories)
resource "aws_secretsmanager_secret" "docker_hub_credentials" {
  name        = "docker-hub-credentials"
  description = "Docker Hub credentials for ECS"
}

resource "aws_secretsmanager_secret_version" "docker_hub_credentials" {
  secret_id = aws_secretsmanager_secret.docker_hub_credentials.id
  secret_string = jsonencode({
    username = "your-docker-hub-username"  # User with access to pennsieve organization
    password = var.docker_hub_password
  })
}

data "template_file" "csv_s3_copy_task_definition" {
  template = file("${path.module}/csv-s3-ops-task-definition.json")

  vars = {
    image_tag                    = var.image_tag
    docker_hub_credentials_arn   = aws_secretsmanager_secret.docker_hub_credentials.arn
    execution_role_arn           = aws_iam_role.ecs_execution_role.arn
    task_role_arn                = aws_iam_role.csv_s3_copy_task_role.arn
    cloudwatch_log_group_name    = "/ecs/csv-s3-ops"
    aws_region                   = var.aws_region
    csv_file_path                = var.csv_file_path
    parallelism                  = var.parallelism
    max_part_size                = var.max_part_size
    max_wait_time                = var.max_wait_time
  }
}

resource "aws_ecs_task_definition" "csv_s3_copy" {
  family                   = "csv-s3-ops"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.ecs_execution_role.arn
  task_role_arn            = aws_iam_role.csv_s3_copy_task_role.arn

  container_definitions = data.template_file.csv_s3_copy_task_definition.rendered
}
```

**For Public Docker Hub Images**: If your image is public, you can remove the `repositoryCredentials` section from the task definition JSON and skip creating the Docker Hub credentials secret.

## Step 4: Run the Task

### Via AWS CLI

```bash
aws ecs run-task \
  --cluster your-ecs-cluster \
  --launch-type FARGATE \
  --task-definition csv-s3-ops:1 \
  --network-configuration "awsvpcConfiguration={
    subnets=[subnet-12345678],
    securityGroups=[sg-12345678],
    assignPublicIp=ENABLED
  }" \
  --overrides '{
    "containerOverrides": [{
      "name": "csv-s3-ops",
      "environment": [
        {"name": "CSV_FILE_PATH", "value": "s3://my-bucket/copy-list.csv"},
        {"name": "AWS_REGION", "value": "us-east-1"},
        {"name": "PARALLELISM", "value": "5"}
      ]
    }]
  }' \
  --region us-east-1
```

### Via AWS Console

1. Navigate to **ECS Console**
2. Select your cluster
3. Click **Tasks** → **Run new Task**
4. Choose:
   - **Launch type**: Fargate
   - **Task Definition**: csv-s3-ops
   - **VPC and Subnets**: Select appropriate subnets
   - **Security Groups**: Allow outbound HTTPS (443)
5. Under **Container Overrides**, set environment variables:
   - `CSV_FILE_PATH`: Path to your CSV file
   - `PARALLELISM`: Number of parallel operations
6. Click **Run Task**

### Using Step Functions

You can orchestrate the CSV S3 copy task from Step Functions:

```json
{
  "Comment": "Run S3 Copy Machine Task",
  "StartAt": "RunCopyTask",
  "States": {
    "RunCopyTask": {
      "Type": "Task",
      "Resource": "arn:aws:states:::ecs:runTask.sync",
      "Parameters": {
        "LaunchType": "FARGATE",
        "Cluster": "arn:aws:ecs:us-east-1:123456789012:cluster/your-cluster",
        "TaskDefinition": "csv-s3-ops",
        "NetworkConfiguration": {
          "AwsvpcConfiguration": {
            "Subnets": ["subnet-12345678"],
            "SecurityGroups": ["sg-12345678"],
            "AssignPublicIp": "ENABLED"
          }
        },
        "Overrides": {
          "ContainerOverrides": [
            {
              "Name": "csv-s3-ops",
              "Environment": [
                {
                  "Name": "CSV_FILE_PATH",
                  "Value.$": "$.csvFilePath"
                },
                {
                  "Name": "PARALLELISM",
                  "Value": "5"
                }
              ]
            }
          ]
        }
      },
      "End": true
    }
  }
}
```

## Step 5: Monitor Execution

### View Logs in CloudWatch

```bash
# Get the task ARN from the run-task command output
TASK_ARN="arn:aws:ecs:us-east-1:123456789012:task/your-cluster/abc123..."

# Extract task ID
TASK_ID=$(echo $TASK_ARN | awk -F/ '{print $NF}')

# View logs
aws logs tail /ecs/csv-s3-ops --follow \
  --log-stream-name-prefix csv-s3-ops/csv-s3-ops/$TASK_ID
```

### Check Task Status

```bash
aws ecs describe-tasks \
  --cluster your-ecs-cluster \
  --tasks $TASK_ARN \
  --region us-east-1
```

## Configuration Options

### CPU and Memory

Adjust based on your workload:

| CPU (vCPU) | Memory (GB) | Recommended Use Case |
|------------|-------------|----------------------|
| 0.25       | 0.5-2       | Small files, low parallelism |
| 0.5        | 1-4         | Medium workloads |
| 1          | 2-8         | Large files or high parallelism (default) |
| 2          | 4-16        | Very large files, high parallelism |
| 4          | 8-30        | Maximum performance |

Update in task definition:
```json
{
  "cpu": "1024",
  "memory": "2048"
}
```

### Environment Variables

All configuration can be set via environment variables or overrides:

| Variable | Description | Example |
|----------|-------------|---------|
| `CSV_FILE_PATH` | Path to CSV file (local or S3 URI) | `s3://bucket/file.csv` |
| `AWS_REGION` | AWS region | `us-east-1` |
| `PARALLELISM` | Number of parallel operations | `5` |
| `MAX_PART_SIZE` | Max part size in bytes | `52428800` |
| `MAX_WAIT_TIME` | Max wait time | `120m` |

## Cost Optimization

### Fargate Pricing

Fargate charges based on vCPU and memory per second:
- **vCPU**: ~$0.04048 per vCPU per hour
- **Memory**: ~$0.004445 per GB per hour

Example cost for 1 vCPU, 2GB task running for 30 minutes:
- vCPU: $0.04048 × 0.5 hours = $0.02024
- Memory: $0.004445 × 2 GB × 0.5 hours = $0.004445
- **Total**: ~$0.025 per run

### Cost Optimization Tips

1. **Right-size resources**: Use the minimum CPU/memory needed
2. **Increase parallelism**: Finish faster to reduce total runtime
3. **Use Spot**: Consider Fargate Spot for 70% cost savings (non-critical workloads)
4. **Batch operations**: Process multiple files in one task invocation

## Troubleshooting

### Task Fails to Start

1. **Check execution role**: Ensure it has permissions to pull ECR image
2. **Check network**: Verify subnets have internet access (NAT gateway or public IP)
3. **Check security groups**: Allow outbound HTTPS (443) for S3 access

### Task Runs But Fails

1. **Check CloudWatch Logs**: View application logs for errors
2. **Verify IAM permissions**: Ensure task role has S3 permissions
3. **Check CSV file**: Verify path is correct and accessible
4. **Resource limits**: Increase CPU/memory if seeing OOM errors

### CSV File Not Found

1. **S3 URI format**: Ensure format is `s3://bucket/key`
2. **Permissions**: Verify task role can `s3:GetObject` on CSV bucket
3. **Region**: Ensure AWS_REGION matches bucket region

## Example: Complete Terraform Module

```hcl
module "csv_s3_copy" {
  source = "./modules/csv-s3-ops"

  cluster_name              = "production"
  vpc_id                    = aws_vpc.main.id
  private_subnet_ids        = aws_subnet.private[*].id
  csv_s3_copy_image         = "pennsieve/s3-ops-machine:latest"
  source_buckets            = ["source-bucket-1", "source-bucket-2"]
  destination_buckets       = ["destination-bucket-1"]
  csv_config_buckets        = ["config-bucket"]
}
```

## Security Best Practices

1. **Use private subnets**: Run tasks in private subnets with NAT gateway
2. **Least privilege IAM**: Grant only required S3 bucket permissions
3. **Encrypt in transit**: S3 already uses HTTPS
4. **Encrypt at rest**: Enable S3 bucket encryption
5. **Use VPC endpoints**: Reduce costs and improve security with S3 VPC endpoint
6. **Secrets management**: Use AWS Secrets Manager for sensitive configuration
7. **Network isolation**: Use security groups to restrict traffic

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: Deploy S3 Copy Machine

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v1
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1

      - name: Login to ECR
        run: |
          aws ecr get-login-password --region us-east-1 | \
            docker login --username AWS --password-stdin ${{ secrets.ECR_REGISTRY }}

      - name: Build and push image
        run: |
          sbt assembly
          sbt docker
          docker tag pennsieve/s3-ops-machine:latest ${{ secrets.ECR_REGISTRY }}/s3-ops-machine:${{ github.sha }}
          docker push ${{ secrets.ECR_REGISTRY }}/s3-ops-machine:${{ github.sha }}

      - name: Update task definition
        run: |
          aws ecs register-task-definition \
            --cli-input-json file://discover-publish/terraform/csv-s3-ops-task-definition.json
```

## Summary

The S3 Copy Machine is well-suited for ECS Fargate deployment:
- **Serverless**: No server management required
- **Scalable**: Run multiple tasks in parallel for large workloads
- **Cost-effective**: Pay only for execution time
- **Integrated**: Works with Step Functions, EventBridge, Lambda
- **Monitored**: CloudWatch Logs and ECS metrics built-in

For questions or issues, refer to the main [S3_COPY_MACHINE.md](S3_COPY_MACHINE.md).
