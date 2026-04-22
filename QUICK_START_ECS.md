# Quick Start: Deploy CSV S3 Copy to ECS Fargate

This is a quick reference for deploying the CSV S3 Copy tool to ECS Fargate.

For detailed documentation, see [ECS_FARGATE_DEPLOYMENT.md](ECS_FARGATE_DEPLOYMENT.md).

**💡 Tip**: Test locally first using `./run-s3-copy.sh --csv your-file.csv` before deploying to ECS. See [CSV_S3_COPY_README.md](CSV_S3_COPY_README.md) for local execution options.

## Prerequisites

```bash
# Set your AWS configuration
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
```

## Step 1: Build and Push Docker Image

### Option A: Docker Hub (Recommended)

```bash
# Build and push to Docker Hub
./scripts/build-csv-s3-copy-image.sh push latest

# This will prompt for your Docker Hub password
# The image will be: pennsieve/s3-copy-machine:latest
```

### Option B: ECR (Alternative)

```bash
# Set ECR configuration
export ECR_REGISTRY=$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Create ECR repository
aws ecr create-repository \
    --repository-name s3-copy-machine \
    --region $AWS_REGION

# Build and push to ECR
./scripts/build-csv-s3-copy-image.sh push-ecr latest
```

## Step 2: Create IAM Roles and Docker Hub Credentials

### Store Docker Hub Credentials (if using private repository)

**Skip this if your Docker Hub image is public**

```bash
# Store Docker Hub credentials in AWS Secrets Manager
# Use credentials for a user with access to the pennsieve organization
aws secretsmanager create-secret \
    --name docker-hub-credentials \
    --secret-string '{
      "username": "your-docker-hub-username",
      "password": "your-docker-hub-password-or-token"
    }' \
    --region $AWS_REGION

# Note the ARN from output - you'll need it later
export DOCKER_HUB_CREDENTIALS_ARN=$(aws secretsmanager describe-secret \
    --secret-id docker-hub-credentials \
    --query ARN --output text)
```

**Tip**: Use a Docker Hub access token instead of your password. Create one at: https://hub.docker.com/settings/security

### Task Execution Role

```bash
# Create trust policy
cat > /tmp/ecs-trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ecs-tasks.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
EOF

# Create execution role
aws iam create-role \
    --role-name csv-s3-copy-execution-role \
    --assume-role-policy-document file:///tmp/ecs-trust-policy.json

# Create and attach policy for Docker Hub credentials and logs
cat > /tmp/execution-role-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "$DOCKER_HUB_CREDENTIALS_ARN"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:CreateLogGroup"
      ],
      "Resource": "*"
    }
  ]
}
EOF

aws iam put-role-policy \
    --role-name csv-s3-copy-execution-role \
    --policy-name execution-policy \
    --policy-document file:///tmp/execution-role-policy.json
```

**For Public Images**: If using a public Docker Hub image, you can skip the Secrets Manager permission and remove the `repositoryCredentials` section from your task definition.

### Task Role

```bash
# Create task role
aws iam create-role \
    --role-name csv-s3-copy-task-role \
    --assume-role-policy-document file:///tmp/ecs-trust-policy.json

# Create and attach S3 policy
cat > /tmp/s3-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "s3:GetObject",
      "s3:GetObjectAttributes",
      "s3:GetObjectVersion",
      "s3:PutObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts"
    ],
    "Resource": "*"
  }]
}
EOF

aws iam put-role-policy \
    --role-name csv-s3-copy-task-role \
    --policy-name s3-access \
    --policy-document file:///tmp/s3-policy.json
```

## Step 3: Register Task Definition

### For Private Docker Hub Images

```bash
# Create task definition with Docker Hub credentials
cat > /tmp/task-definition.json <<EOF
{
  "family": "csv-s3-copy",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::$AWS_ACCOUNT_ID:role/csv-s3-copy-execution-role",
  "taskRoleArn": "arn:aws:iam::$AWS_ACCOUNT_ID:role/csv-s3-copy-task-role",
  "containerDefinitions": [{
    "name": "csv-s3-copy",
    "image": "pennsieve/s3-copy-machine:latest",
    "repositoryCredentials": {
      "credentialsParameter": "$DOCKER_HUB_CREDENTIALS_ARN"
    },
    "essential": true,
    "environment": [
      {"name": "AWS_REGION", "value": "$AWS_REGION"}
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/csv-s3-copy",
        "awslogs-region": "$AWS_REGION",
        "awslogs-stream-prefix": "csv-s3-copy",
        "awslogs-create-group": "true"
      }
    }
  }]
}
EOF

# Register the task definition
aws ecs register-task-definition \
    --cli-input-json file:///tmp/task-definition.json
```

### For Public Docker Hub Images

If your Docker Hub repository is public, you can omit the `repositoryCredentials`:

```bash
cat > /tmp/task-definition.json <<EOF
{
  "family": "csv-s3-copy",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::$AWS_ACCOUNT_ID:role/csv-s3-copy-execution-role",
  "taskRoleArn": "arn:aws:iam::$AWS_ACCOUNT_ID:role/csv-s3-copy-task-role",
  "containerDefinitions": [{
    "name": "csv-s3-copy",
    "image": "pennsieve/s3-copy-machine:latest",
    "essential": true,
    "environment": [
      {"name": "AWS_REGION", "value": "$AWS_REGION"}
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/csv-s3-copy",
        "awslogs-region": "$AWS_REGION",
        "awslogs-stream-prefix": "csv-s3-copy",
        "awslogs-create-group": "true"
      }
    }
  }]
}
EOF

aws ecs register-task-definition \
    --cli-input-json file:///tmp/task-definition.json
```

## Step 4: Run the Task

```bash
# Set your VPC configuration
export VPC_SUBNET=subnet-xxxxxxxx
export SECURITY_GROUP=sg-xxxxxxxx

# Run the task
aws ecs run-task \
    --cluster default \
    --launch-type FARGATE \
    --task-definition csv-s3-copy \
    --network-configuration "awsvpcConfiguration={
        subnets=[$VPC_SUBNET],
        securityGroups=[$SECURITY_GROUP],
        assignPublicIp=ENABLED
    }" \
    --overrides '{
        "containerOverrides": [{
            "name": "csv-s3-copy",
            "environment": [
                {"name": "CSV_FILE_PATH", "value": "s3://my-bucket/copy-requests.csv"},
                {"name": "PARALLELISM", "value": "5"}
            ]
        }]
    }' \
    --region $AWS_REGION
```

## Step 5: Monitor

```bash
# View logs (replace TASK_ID with your task ID from run-task output)
TASK_ID=abc123...

aws logs tail /ecs/csv-s3-copy \
    --follow \
    --log-stream-name-prefix csv-s3-copy/csv-s3-copy/$TASK_ID \
    --region $AWS_REGION
```

## Common Scenarios

### Scenario 1: One-time Copy Job

```bash
aws ecs run-task \
    --cluster default \
    --launch-type FARGATE \
    --task-definition csv-s3-copy \
    --network-configuration "awsvpcConfiguration={...}" \
    --overrides '{
        "containerOverrides": [{
            "name": "csv-s3-copy",
            "environment": [
                {"name": "CSV_FILE_PATH", "value": "s3://config-bucket/one-time-copy.csv"}
            ]
        }]
    }'
```

### Scenario 2: Scheduled Job (EventBridge)

```bash
# Create EventBridge rule
aws events put-rule \
    --name daily-s3-copy \
    --schedule-expression "cron(0 2 * * ? *)"

# Add ECS task as target
aws events put-targets \
    --rule daily-s3-copy \
    --targets '[{
        "Id": "1",
        "Arn": "arn:aws:ecs:us-east-1:'$AWS_ACCOUNT_ID':cluster/default",
        "RoleArn": "arn:aws:iam::'$AWS_ACCOUNT_ID':role/ecsEventsRole",
        "EcsParameters": {
            "TaskDefinitionArn": "arn:aws:ecs:us-east-1:'$AWS_ACCOUNT_ID':task-definition/csv-s3-copy",
            "LaunchType": "FARGATE",
            "NetworkConfiguration": {
                "awsvpcConfiguration": {
                    "Subnets": ["'$VPC_SUBNET'"],
                    "SecurityGroups": ["'$SECURITY_GROUP'"],
                    "AssignPublicIp": "ENABLED"
                }
            }
        }
    }]'
```

### Scenario 3: Triggered by S3 Upload (via Lambda)

```python
# Lambda function to trigger ECS task when CSV is uploaded
import boto3
import json

ecs = boto3.client('ecs')

def lambda_handler(event, context):
    # Get S3 object info from event
    bucket = event['Records'][0]['s3']['bucket']['name']
    key = event['Records'][0]['s3']['object']['key']

    # Run ECS task
    response = ecs.run_task(
        cluster='default',
        launchType='FARGATE',
        taskDefinition='csv-s3-copy',
        networkConfiguration={
            'awsvpcConfiguration': {
                'subnets': ['subnet-xxxxxxxx'],
                'securityGroups': ['sg-xxxxxxxx'],
                'assignPublicIp': 'ENABLED'
            }
        },
        overrides={
            'containerOverrides': [{
                'name': 'csv-s3-copy',
                'environment': [
                    {'name': 'CSV_FILE_PATH', 'value': f's3://{bucket}/{key}'}
                ]
            }]
        }
    )

    return {'statusCode': 200, 'body': json.dumps('Task started')}
```

## Troubleshooting

### Task fails to start
- Check execution role has ECR permissions
- Verify subnet has internet access
- Check security group allows outbound HTTPS

### Task runs but fails
- View CloudWatch Logs: `/ecs/csv-s3-copy`
- Verify task role has S3 permissions
- Check CSV file path is correct

### Need more resources
Update CPU/memory in task definition:
```bash
# 2 vCPU, 4 GB memory
"cpu": "2048",
"memory": "4096"
```

## Next Steps

- Review [ECS_FARGATE_DEPLOYMENT.md](ECS_FARGATE_DEPLOYMENT.md) for detailed documentation
- Set up monitoring and alerts
- Configure auto-scaling (if using ECS Service)
- Implement cost optimization with Fargate Spot

## Cleanup

```bash
# Stop running tasks
aws ecs list-tasks --cluster default --family csv-s3-copy --query 'taskArns[]' --output text | \
    xargs -I {} aws ecs stop-task --cluster default --task {}

# Deregister task definition (all revisions)
for rev in $(aws ecs list-task-definitions --family-prefix csv-s3-copy --query 'taskDefinitionArns[]' --output text); do
    aws ecs deregister-task-definition --task-definition $rev
done

# Delete ECR repository
aws ecr delete-repository --repository-name csv-s3-copy --force

# Delete IAM roles
aws iam delete-role-policy --role-name csv-s3-copy-task-role --policy-name s3-access
aws iam delete-role --role-name csv-s3-copy-task-role
aws iam detach-role-policy --role-name csv-s3-copy-execution-role --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
aws iam delete-role --role-name csv-s3-copy-execution-role
```
