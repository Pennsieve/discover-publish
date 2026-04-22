# CSV S3 Copy Application

A Scala application that reads S3 copy instructions from a CSV file and performs bulk S3 object copies using the MultipartUploader.

## Overview

This application allows you to perform bulk S3 copy operations by providing a CSV file with source and destination information. It uses the existing `MultipartUploader` class which automatically:
- Detects object size and chooses between single-part or multi-part copy
- Handles large files (>5GB) using multi-part copy operations
- Calculates SHA256 checksums
- Supports versioned S3 objects

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

## Running the Application

### Using sbt run

```bash
sbt "runMain com.pennsieve.publish.CsvS3CopyMain --csv /path/to/your/file.csv"
```

### Using the assembled JAR

```bash
java -cp target/scala-2.13/discover-publish-assembly-*.jar \
  com.pennsieve.publish.CsvS3CopyMain \
  --csv /path/to/your/file.csv
```

## Command Line Options

### Required Options

- `--csv <path>` - Path to the CSV file containing copy instructions

### Optional Options

- `--region <region>` - AWS region (default: `us-east-1`)
  - Example: `--region us-west-2`

- `--maxPartSize <bytes>` - Maximum part size for multi-part uploads in bytes (default: `52428800` = 50MB)
  - Example: `--maxPartSize 104857600` (100MB)

- `--maxWaitTime <duration>` - Maximum time to wait for all operations to complete (default: `60m`)
  - Examples: `--maxWaitTime 30m`, `--maxWaitTime 2h`

- `--parallelism <number>` - Number of parallel copy operations to run (default: `1`)
  - Example: `--parallelism 5` (copies 5 files at a time)

## Complete Example

```bash
sbt "runMain com.pennsieve.publish.CsvS3CopyMain \
  --csv example-copy-requests.csv \
  --region us-east-1 \
  --maxPartSize 52428800 \
  --parallelism 3 \
  --maxWaitTime 120m"
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
      "Resource": "arn:aws:s3:::source-bucket/*"
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

## Features

- **Automatic Multi-part Handling**: Files larger than 5GB are automatically copied using multi-part operations
- **Version Support**: Supports copying specific versions of S3 objects
- **SHA256 Checksums**: Automatically calculates and stores SHA256 checksums
- **Controlled Parallelism**: Control how many files are copied simultaneously
- **Comprehensive Logging**: Detailed logging of all operations
- **Error Handling**: Continues processing even if some copies fail, provides summary at the end
- **Request Payer Support**: Uses requester-pays mode for S3 operations

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
