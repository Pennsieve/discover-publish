# CSV S3 Copy Application

A Scala application that reads S3 copy instructions from a CSV file and performs bulk S3 object copies using the MultipartUploader.

## Overview

This application allows you to perform bulk S3 copy operations by providing a CSV file with source and destination information. It uses the existing `MultipartUploader` class which automatically:
- Detects object size and chooses between single-part or multi-part copy
- Handles large files (>5GB) using multi-part copy operations
- Calculates SHA256 checksums
- Supports versioned S3 objects

## CSV File Location

The application supports reading CSV files from:
- **Local file system**: Use a file path (e.g., `/path/to/file.csv` or `./file.csv`)
- **S3**: Use an S3 URI (e.g., `s3://my-bucket/path/to/file.csv`)

When using an S3 URI, the application will automatically download the CSV file to a temporary location, process it, and clean up the temporary file afterward.

## CSV Format

The CSV file must have a header row with the following columns:

| Column Name | Required | Description |
|-------------|----------|-------------|
| `source_bucket` | Yes | Source S3 bucket name |
| `source_key` | Yes | Source S3 object key (path) |
| `source_version_id` | No | Source S3 version ID (leave empty if not needed) |
| `destination_bucket` | Yes | Destination S3 bucket name |
| `destination_key` | Yes | Destination S3 object key (path) |

### Example CSV

```csv
source_bucket,source_key,source_version_id,destination_bucket,destination_key
my-source-bucket,path/to/file1.txt,,my-dest-bucket,new/path/file1.txt
my-source-bucket,path/to/file2.txt,abc123xyz456,my-dest-bucket,new/path/file2.txt
my-source-bucket,data/file3.dat,,my-dest-bucket,backup/file3.dat
source-bucket-2,documents/doc.pdf,v1234567890,target-bucket,archive/doc.pdf
```

See `example-copy-requests.csv` for a complete example.

## Building the Application

```bash
sbt compile
sbt assembly  # Creates a fat JAR
```

### Building Docker Image

```bash
# Build Docker image for CSV S3 Copy tool
./scripts/build-csv-s3-copy-image.sh

# Build and push to Docker Hub (recommended)
export DOCKER_HUB_USERNAME=your-username
./scripts/build-csv-s3-copy-image.sh push latest

# Or build and push to ECR (alternative)
export ECR_REGISTRY=<your-account-id>.dkr.ecr.us-east-1.amazonaws.com
export AWS_REGION=us-east-1
./scripts/build-csv-s3-copy-image.sh push-ecr latest
```

## Deployment Options

### Local / Development

Run directly with sbt (see Running the Application below).

### AWS ECS Fargate (Recommended for Production)

The CSV S3 Copy tool is designed to run as an ECS Task on AWS Fargate, providing:
- **Serverless execution**: No server management
- **Scalability**: Run multiple tasks in parallel
- **Cost-effective**: Pay only for execution time
- **Integration**: Works with Step Functions, EventBridge, Lambda

**Quick Start**: See [QUICK_START_ECS.md](QUICK_START_ECS.md) for a fast deployment guide.

**Full Documentation**: See [ECS_FARGATE_DEPLOYMENT.md](ECS_FARGATE_DEPLOYMENT.md) for comprehensive deployment instructions including:
- Building and pushing Docker images to ECR
- Creating IAM roles with proper S3 permissions
- Registering ECS task definitions
- Running tasks via CLI, Console, or Step Functions
- Monitoring with CloudWatch Logs
- Cost optimization strategies
- Troubleshooting common issues

### Docker (Local or Any Container Platform)

```bash
# Run with local CSV file
docker run \
  -e CSV_FILE_PATH=/data/copy-requests.csv \
  -e AWS_ACCESS_KEY_ID=<key> \
  -e AWS_SECRET_ACCESS_KEY=<secret> \
  -e AWS_REGION=us-east-1 \
  -v /local/path:/data \
  csv-s3-copy:latest

# Run with S3 CSV file
docker run \
  -e CSV_FILE_PATH=s3://my-bucket/copy-requests.csv \
  -e AWS_ACCESS_KEY_ID=<key> \
  -e AWS_SECRET_ACCESS_KEY=<secret> \
  -e AWS_REGION=us-east-1 \
  csv-s3-copy:latest
```

## Configuration

The application supports configuration through both command-line arguments and environment variables. The priority order is:

1. **Command-line arguments** (highest priority)
2. **Environment variables**
3. **Default values** (lowest priority)

This allows you to set defaults via environment variables and override them with command-line arguments when needed.

## Running the Application

### Using sbt run with local file

```bash
sbt "runMain com.pennsieve.publish.CsvS3CopyMain --csv /path/to/your/file.csv"
```

### Using sbt run with S3 URI

```bash
sbt "runMain com.pennsieve.publish.CsvS3CopyMain --csv s3://my-bucket/path/to/file.csv"
```

### Using environment variables

```bash
export CSV_FILE_PATH=/path/to/your/file.csv
export AWS_REGION=us-west-2
export PARALLELISM=3
sbt "runMain com.pennsieve.publish.CsvS3CopyMain"

# Or with S3 URI
export CSV_FILE_PATH=s3://my-bucket/path/to/file.csv
sbt "runMain com.pennsieve.publish.CsvS3CopyMain"
```

### Using the assembled JAR

```bash
java -cp target/scala-2.13/discover-publish-assembly-*.jar \
  com.pennsieve.publish.CsvS3CopyMain \
  --csv /path/to/your/file.csv
```

### Mixing environment variables and command-line arguments

```bash
# Set defaults via environment variables
export CSV_FILE_PATH=/path/to/default.csv
export PARALLELISM=5

# Override specific settings with command-line arguments
sbt "runMain com.pennsieve.publish.CsvS3CopyMain --parallelism 10"
# This will use /path/to/default.csv but with parallelism of 10
```

## Command Line Options

### Required Options

- `--csv <path>` - Path to the CSV file containing copy instructions
  - Supports local file paths (e.g., `/path/to/file.csv`) and S3 URIs (e.g., `s3://bucket/key`)
  - Environment variable: `CSV_FILE_PATH`

### Optional Options

- `--region <region>` - AWS region (default: `us-east-1`)
  - Example: `--region us-west-2`
  - Environment variable: `AWS_REGION`

- `--maxPartSize <bytes>` - Maximum part size for multi-part uploads in bytes (default: `52428800` = 50MB)
  - Example: `--maxPartSize 104857600` (100MB)
  - Environment variable: `MAX_PART_SIZE`

- `--maxWaitTime <duration>` - Maximum time to wait for all operations to complete (default: `60m`)
  - Examples: `--maxWaitTime 30m`, `--maxWaitTime 2h`
  - Environment variable: `MAX_WAIT_TIME`

- `--parallelism <number>` - Number of parallel copy operations to run (default: `1`)
  - Example: `--parallelism 5` (copies 5 files at a time)
  - Environment variable: `PARALLELISM`

## Environment Variables

All configuration options can be set via environment variables:

| Environment Variable | Description | Example |
|---------------------|-------------|---------|
| `CSV_FILE_PATH` | Path to CSV file with copy instructions (local or S3 URI) | `/path/to/file.csv` or `s3://bucket/key` |
| `AWS_REGION` | AWS region | `us-west-2` |
| `MAX_PART_SIZE` | Maximum part size in bytes | `104857600` (100MB) |
| `MAX_WAIT_TIME` | Maximum wait time duration | `120m` or `2h` |
| `PARALLELISM` | Number of parallel copy operations | `5` |

**Note**: Command-line arguments always override environment variables.

## Complete Examples

### Using command-line arguments

```bash
sbt "runMain com.pennsieve.publish.CsvS3CopyMain \
  --csv example-copy-requests.csv \
  --region us-east-1 \
  --maxPartSize 52428800 \
  --parallelism 3 \
  --maxWaitTime 120m"
```

### Using environment variables

```bash
export CSV_FILE_PATH=example-copy-requests.csv
export AWS_REGION=us-east-1
export MAX_PART_SIZE=52428800
export PARALLELISM=3
export MAX_WAIT_TIME=120m

sbt "runMain com.pennsieve.publish.CsvS3CopyMain"
```

### Using S3 URI for CSV file

```bash
# CSV file stored in S3
sbt "runMain com.pennsieve.publish.CsvS3CopyMain \
  --csv s3://my-config-bucket/copy-requests.csv \
  --region us-east-1 \
  --parallelism 3"
```

### Using Docker/container environments

Environment variables are particularly useful in containerized environments:

```bash
# With local CSV file
docker run \
  -e CSV_FILE_PATH=/data/copy-requests.csv \
  -e AWS_REGION=us-west-2 \
  -e PARALLELISM=5 \
  -v /local/path:/data \
  your-image

# With S3 CSV file
docker run \
  -e CSV_FILE_PATH=s3://my-config-bucket/copy-requests.csv \
  -e AWS_REGION=us-west-2 \
  -e PARALLELISM=5 \
  your-image
```

## AWS Credentials

The application uses the default AWS credentials provider chain, which checks:
1. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
2. System properties
3. AWS credentials file (`~/.aws/credentials`)
4. IAM instance profile credentials (when running on EC2)

Make sure you have appropriate AWS credentials configured before running the application.

## Required IAM Permissions

The AWS credentials used must have the following permissions:

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

**Note**: If using an S3 URI for the CSV file (e.g., `s3://csv-config-bucket/file.csv`), ensure the credentials have `s3:GetObject` permission for that bucket as well.

## Features

- **Automatic Multi-part Handling**: Files larger than 5GB are automatically copied using multi-part operations
- **Version Support**: Supports copying specific versions of S3 objects
- **SHA256 Checksums**: Automatically calculates and stores SHA256 checksums
- **Controlled Parallelism**: Control how many files are copied simultaneously
- **S3 URI Support**: CSV file can be read from S3 (s3://bucket/key) or local file system
- **Flexible Configuration**: Support for both command-line arguments and environment variables with priority override
- **Comprehensive Logging**: Detailed logging of all operations
- **Error Handling**: Continues processing even if some copies fail, provides summary at the end
- **Request Payer Support**: Uses requester-pays mode for S3 operations
- **Container-Friendly**: Easy to configure in Docker and Kubernetes environments via environment variables

## Error Handling

- If the CSV file cannot be read or parsed, the application will exit with an error
- If individual copy operations fail, they are logged but processing continues
- At the end, a summary shows how many operations succeeded vs. failed
- The application exits with code 1 if any operations failed, 0 if all succeeded

## Output

The application logs:
- Each copy operation being performed
- Success/failure status of each operation
- Final summary with count of successful and failed operations
- Details about the copy operation used (single-part vs. multi-part)
- Version IDs, ETags, and SHA256 checksums of copied objects

## Implementation Details

- **Location**: `discover-publish/src/main/scala/com/pennsieve/publish/CsvS3CopyMain.scala`
- **CSV Library**: Uses `scala-csv` for robust CSV parsing
- **S3 SDK**: Uses AWS SDK v2 for S3 operations
- **Concurrency**: Uses Scala Futures with configurable parallelism
- **Integration**: Leverages the existing `MultipartUploader` class (see `MultipartUploader.scala:286`)
