# S3 Copy Machine

A Scala application that performs bulk S3 operations based on CSV instructions. Supports multiple operation types including copy, delete, list, and validation.

## Overview

This application allows you to perform bulk S3 operations by providing a CSV file with operation instructions. It supports four operation types:

- **COPY**: Copy objects between buckets using the `MultipartUploader` (automatic single-part or multipart for files ≥5GB)
- **DELETE**: Delete objects with soft delete (delete marker) or permanent delete (with version ID)
- **LIST**: Get comprehensive object information including all versions, delete markers, and checksums
- **KEEP**: Validate that important objects exist

The COPY operation uses the existing `MultipartUploader` class which automatically:
- Detects object size and chooses between single-part or multi-part copy
- Handles large files (>5GB) using multi-part copy operations
- Calculates SHA256 checksums
- Supports versioned S3 objects

## Quick Start

```bash
# Run locally with sbt
./run-s3-ops.sh --csv example-operation-requests.csv

# Run with Docker
./run-s3-ops.sh --method docker --csv example-operation-requests.csv

# Run with S3 CSV file and custom parallelism
./run-s3-ops.sh --csv s3://my-bucket/copy-list.csv --parallelism 5
```

See the full [Running the Application](#running-the-application) section for more options.

## CSV File Location

The application supports reading CSV files from:
- **Local file system**: Use a file path (e.g., `/path/to/file.csv` or `./file.csv`)
- **S3**: Use an S3 URI (e.g., `s3://my-bucket/path/to/file.csv`)

When using an S3 URI, the application will automatically download the CSV file to a temporary location, process it, and clean up the temporary file afterward.

## CSV Format

The CSV file must have a header row with the following columns:

| Column Name | Required | Description |
|-------------|----------|-------------|
| `operation` | Yes | S3 operation to perform: `COPY`, `DELETE`, `LIST`, or `KEEP` |
| `source_bucket` | Yes | Source S3 bucket name |
| `source_key` | Yes | Source S3 object key (path) |
| `source_version_id` | No | Source S3 version ID (leave empty if not needed) |
| `destination_bucket` | COPY only | Destination S3 bucket name (required for COPY operations) |
| `destination_key` | COPY only | Destination S3 object key (required for COPY operations) |

### Supported Operations

#### COPY
Copies an object from source to destination. Automatically detects object size and uses single-part copy (<5GB) or multipart copy (≥5GB).

**Required fields**: `operation`, `source_bucket`, `source_key`, `destination_bucket`, `destination_key`

#### DELETE
Deletes an S3 object.
- **Without `source_version_id`**: Performs a "soft delete" by adding a delete marker (object can be recovered)
- **With `source_version_id`**: Performs a permanent delete of the specific version

**Required fields**: `operation`, `source_bucket`, `source_key`

#### LIST
Lists all attributes, versions, delete markers, and checksums for an S3 object. Provides detailed information including:
- Object size, last modified date, ETag
- All version IDs and their metadata
- Delete markers
- Checksums (SHA256, SHA1, CRC32, CRC32C)
- Storage class

**Required fields**: `operation`, `source_bucket`, `source_key`

#### KEEP
Checks if an S3 object exists. Useful for validation workflows to ensure important objects are present.

**Required fields**: `operation`, `source_bucket`, `source_key`

### Example CSV

```csv
operation,source_bucket,source_key,source_version_id,destination_bucket,destination_key
COPY,my-source-bucket,path/to/file1.txt,,my-dest-bucket,new/path/file1.txt
COPY,my-source-bucket,path/to/file2.txt,abc123xyz456,my-dest-bucket,new/path/file2.txt
DELETE,my-source-bucket,path/to/old-file.txt,,,
DELETE,my-source-bucket,path/to/specific-version.txt,version123,,
LIST,my-source-bucket,data/file3.dat,,,
KEEP,source-bucket-2,documents/important.pdf,,,
```

See `example-operation-requests.csv` for a complete example.

## Building the Application

```bash
sbt compile
sbt assembly  # Creates a fat JAR
```

### Building Docker Image

```bash
# Build Docker image for S3 Copy Machine
./scripts/build-csv-s3-ops-image.sh

# Build and push to Docker Hub as pennsieve/s3-ops-machine (recommended)
./scripts/build-csv-s3-ops-image.sh push latest

# Or build and push to ECR (alternative)
export ECR_REGISTRY=<your-account-id>.dkr.ecr.us-east-1.amazonaws.com
export AWS_REGION=us-east-1
./scripts/build-csv-s3-ops-image.sh push-ecr latest
```

## Deployment Options

### Local / Development

Run directly with sbt (see Running the Application below).

### AWS ECS Fargate (Recommended for Production)

The S3 Copy Machine is designed to run as an ECS Task on AWS Fargate, providing:
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
  pennsieve/s3-ops-machine:latest

# Run with S3 CSV file
docker run \
  -e CSV_FILE_PATH=s3://my-bucket/copy-requests.csv \
  -e AWS_ACCESS_KEY_ID=<key> \
  -e AWS_SECRET_ACCESS_KEY=<secret> \
  -e AWS_REGION=us-east-1 \
  pennsieve/s3-ops-machine:latest
```

## Configuration

The application supports configuration through both command-line arguments and environment variables. The priority order is:

1. **Command-line arguments** (highest priority)
2. **Environment variables**
3. **Default values** (lowest priority)

This allows you to set defaults via environment variables and override them with command-line arguments when needed.

## Running the Application

### Quick Start: Using the Convenience Script (Recommended)

The easiest way to run the tool is using the provided `run-s3-ops.sh` script:

```bash
# Run with sbt (local development)
./run-s3-ops.sh --csv example-operation-requests.csv

# Run with Docker
./run-s3-ops.sh --method docker --csv s3://my-bucket/copy-list.csv

# Run with options
./run-s3-ops.sh --csv example.csv --parallelism 5 --region us-west-2

# Use environment variables
export CSV_FILE_PATH=s3://bucket/file.csv
export PARALLELISM=10
./run-s3-ops.sh

# Get help
./run-s3-ops.sh --help
```

**Features:**
- Automatically handles sbt or Docker execution
- Validates inputs and provides clear error messages
- Mounts local CSV files when using Docker
- Handles AWS credentials automatically
- Colored output for better readability
- Shows configuration before running

### Using sbt run directly

If you prefer to use sbt directly:

```bash
# With local file
sbt "runMain com.pennsieve.publish.CsvS3OpsMain --csv /path/to/your/file.csv"

# With S3 URI
sbt "runMain com.pennsieve.publish.CsvS3OpsMain --csv s3://my-bucket/path/to/file.csv"

# Using environment variables
export CSV_FILE_PATH=/path/to/your/file.csv
export AWS_REGION=us-west-2
export PARALLELISM=3
sbt "runMain com.pennsieve.publish.CsvS3OpsMain"
```

### Using the assembled JAR

```bash
java -cp target/scala-2.13/discover-publish-assembly-*.jar \
  com.pennsieve.publish.CsvS3OpsMain \
  --csv /path/to/your/file.csv
```

### Mixing environment variables and command-line arguments

```bash
# Set defaults via environment variables
export CSV_FILE_PATH=/path/to/default.csv
export PARALLELISM=5

# Override specific settings with command-line arguments
sbt "runMain com.pennsieve.publish.CsvS3OpsMain --parallelism 10"
# This will use /path/to/default.csv but with parallelism of 10
```

## Command Line Options

### Required Options (choose one mode)

#### CSV mode

- `--csv <path>` - Path to the CSV file containing copy instructions
  - Supports local file paths (e.g., `/path/to/file.csv`) and S3 URIs (e.g., `s3://bucket/key`)
  - Environment variable: `CSV_FILE_PATH`

#### Single-file mode

Perform exactly one S3 operation without a CSV. Mutually exclusive with `--csv`.

- `--operation <op>` - Operation to perform: `COPY`, `DELETE`, `LIST`, or `KEEP`
  - Environment variable: `S3_OPERATION`
- `--source-uri <uri>` - Source S3 URI (`s3://bucket/key`)
  - Environment variable: `SOURCE_S3_URI`
- `--dest-uri <uri>` - Destination S3 URI (required for `COPY`)
  - Environment variable: `DEST_S3_URI`
- `--source-version-id <id>` - Optional source S3 version ID
  - Environment variable: `SOURCE_S3_VERSION_ID`

Example:

```bash
sbt "runMain com.pennsieve.publish.CsvS3OpsMain \
  --operation COPY \
  --source-uri s3://src-bucket/path/file.txt \
  --dest-uri  s3://dst-bucket/path/file.txt"
```

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
| `S3_OPERATION` | Single-file mode: operation to perform | `COPY`, `DELETE`, `LIST`, `KEEP` |
| `SOURCE_S3_URI` | Single-file mode: source S3 URI | `s3://bucket/key` |
| `DEST_S3_URI` | Single-file mode: destination S3 URI (required for `COPY`) | `s3://bucket/key` |
| `SOURCE_S3_VERSION_ID` | Single-file mode: optional source version ID | `abc123def456` |
| `AWS_REGION` | AWS region | `us-west-2` |
| `MAX_PART_SIZE` | Maximum part size in bytes | `104857600` (100MB) |
| `MAX_WAIT_TIME` | Maximum wait time duration | `120m` or `2h` |
| `PARALLELISM` | Number of parallel copy operations | `5` |

**Note**: Command-line arguments always override environment variables.

## Complete Examples

### Using command-line arguments

```bash
sbt "runMain com.pennsieve.publish.CsvS3OpsMain \
  --csv example-operation-requests.csv \
  --region us-east-1 \
  --maxPartSize 52428800 \
  --parallelism 3 \
  --maxWaitTime 120m"
```

### Using environment variables

```bash
export CSV_FILE_PATH=example-operation-requests.csv
export AWS_REGION=us-east-1
export MAX_PART_SIZE=52428800
export PARALLELISM=3
export MAX_WAIT_TIME=120m

sbt "runMain com.pennsieve.publish.CsvS3OpsMain"
```

### Using S3 URI for CSV file

```bash
# CSV file stored in S3
sbt "runMain com.pennsieve.publish.CsvS3OpsMain \
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

- **Location**: `discover-publish/src/main/scala/com/pennsieve/publish/CsvS3OpsMain.scala`
- **CSV Library**: Uses `scala-csv` for robust CSV parsing
- **S3 SDK**: Uses AWS SDK v2 for S3 operations
- **Concurrency**: Uses Scala Futures with configurable parallelism
- **Integration**: Leverages the existing `MultipartUploader` class (see `MultipartUploader.scala:286`)
