# Quick Start: Deploy CSV S3 Copy to ECS Fargate

This is a quick reference for deploying the CSV S3 Copy tool to ECS Fargate.

For detailed documentation, see [ECS_FARGATE_DEPLOYMENT.md](ECS_FARGATE_DEPLOYMENT.md).

## Prerequisites

```bash
# Set your AWS configuration
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REGISTRY=$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
```

## Step 1: Build and Push Docker Image

```bash
# Build the assembly and Docker image
./scripts/build-csv-s3-copy-image.sh

# Create ECR repository
aws ecr create-repository \
    --repository-name csv-s3-copy \
    --region $AWS_REGION

# Push to ECR
./scripts/build-csv-s3-copy-image.sh push latest
```

## Step 2: Create IAM Roles

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

# Attach AWS managed policy
aws iam attach-role-policy \
    --role-name csv-s3-copy-execution-role \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

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

```bash
# Update the task definition with your values
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
    "image": "$ECR_REGISTRY/csv-s3-copy:latest",
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
