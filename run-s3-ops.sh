#!/bin/bash
set -e

# S3 Copy Machine - Convenience wrapper script
# Runs the CSV S3 Copy tool via sbt or Docker

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Default values
RUN_METHOD="sbt"  # or "docker"
CSV_FILE_PATH=""
AWS_REGION="${AWS_REGION:-us-east-1}"
PARALLELISM="${PARALLELISM:-1}"
MAX_PART_SIZE="${MAX_PART_SIZE:-52428800}"
MAX_WAIT_TIME="${MAX_WAIT_TIME:-60m}"
DOCKER_IMAGE="pennsieve/s3-ops-machine:latest"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_usage() {
    cat << EOF
${BLUE}S3 Copy Machine${NC} - Bulk S3 object copy tool

${GREEN}Usage:${NC}
    $0 [--method sbt|docker] --csv <path> [options]

${GREEN}Required:${NC}
    --csv <path>              Path to CSV file (local or s3://bucket/key)

${GREEN}Options:${NC}
    --method <sbt|docker>     Execution method (default: sbt)
    --region <region>         AWS region (default: us-east-1)
    --parallelism <n>         Number of parallel operations (default: 1)
    --max-part-size <bytes>   Maximum part size in bytes (default: 52428800)
    --max-wait-time <time>    Maximum wait time (e.g., "60m", "2h") (default: 60m)
    --docker-image <image>    Docker image to use (default: pennsieve/s3-ops-machine:latest)
    -h, --help                Show this help message

${GREEN}Environment Variables:${NC}
    CSV_FILE_PATH             Path to CSV file
    AWS_REGION                AWS region
    PARALLELISM               Number of parallel operations
    MAX_PART_SIZE             Maximum part size in bytes
    MAX_WAIT_TIME             Maximum wait time
    AWS_ACCESS_KEY_ID         AWS access key (for Docker method)
    AWS_SECRET_ACCESS_KEY     AWS secret key (for Docker method)

${GREEN}Examples:${NC}
    # Run with sbt (local development)
    $0 --csv example-operation-requests.csv

    # Run with Docker
    $0 --method docker --csv s3://my-bucket/copy-list.csv

    # Run with parallelism
    $0 --csv example.csv --parallelism 5 --region us-west-2

    # Use environment variables
    export CSV_FILE_PATH=s3://bucket/file.csv
    export PARALLELISM=10
    $0

${GREEN}CSV File Format:${NC}
    Required columns:
      - source_bucket
      - source_key
      - destination_bucket
      - destination_key
    Optional columns:
      - source_version_id

${GREEN}Methods:${NC}
    ${YELLOW}sbt${NC}     - Run directly via sbt (requires Scala/sbt installed)
             - Best for development and local testing
             - No Docker required

    ${YELLOW}docker${NC}  - Run via Docker container
             - Requires Docker installed and AWS credentials
             - Best for production-like testing
             - Consistent environment

${GREEN}AWS Credentials:${NC}
    ${YELLOW}sbt method:${NC}
        Uses default AWS credentials chain:
        - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
        - ~/.aws/credentials file
        - IAM instance profile

    ${YELLOW}docker method:${NC}
        Requires explicit environment variables:
        - AWS_ACCESS_KEY_ID
        - AWS_SECRET_ACCESS_KEY
        Or mount ~/.aws directory:
        - Automatically handled by this script

EOF
}

error() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

info() {
    echo -e "${BLUE}→${NC} $1"
}

success() {
    echo -e "${GREEN}✓${NC} $1"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --csv)
            CSV_FILE_PATH="$2"
            shift 2
            ;;
        --method)
            RUN_METHOD="$2"
            shift 2
            ;;
        --region)
            AWS_REGION="$2"
            shift 2
            ;;
        --parallelism)
            PARALLELISM="$2"
            shift 2
            ;;
        --max-part-size)
            MAX_PART_SIZE="$2"
            shift 2
            ;;
        --max-wait-time)
            MAX_WAIT_TIME="$2"
            shift 2
            ;;
        --docker-image)
            DOCKER_IMAGE="$2"
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            error "Unknown option: $1\nUse --help for usage information"
            ;;
    esac
done

# Validate required parameters
if [ -z "$CSV_FILE_PATH" ]; then
    error "CSV file path is required. Use --csv <path> or set CSV_FILE_PATH environment variable"
fi

# Validate run method
if [[ "$RUN_METHOD" != "sbt" && "$RUN_METHOD" != "docker" ]]; then
    error "Invalid method: $RUN_METHOD. Must be 'sbt' or 'docker'"
fi

# Print configuration
echo ""
echo -e "${BLUE}════════════════════════════════════════${NC}"
echo -e "${BLUE}       S3 Copy Machine${NC}"
echo -e "${BLUE}════════════════════════════════════════${NC}"
echo ""
info "Configuration:"
echo "  CSV File:      $CSV_FILE_PATH"
echo "  Method:        $RUN_METHOD"
echo "  Region:        $AWS_REGION"
echo "  Parallelism:   $PARALLELISM"
echo "  Max Part Size: $MAX_PART_SIZE bytes"
echo "  Max Wait Time: $MAX_WAIT_TIME"
if [ "$RUN_METHOD" = "docker" ]; then
    echo "  Docker Image:  $DOCKER_IMAGE"
fi
echo ""

# Run based on method
if [ "$RUN_METHOD" = "sbt" ]; then
    info "Running via sbt..."
    echo ""

    # Check if sbt is installed
    if ! command -v sbt &> /dev/null; then
        error "sbt is not installed. Install sbt or use --method docker"
    fi

    # Export environment variables for the application
    export CSV_FILE_PATH
    export AWS_REGION
    export PARALLELISM
    export MAX_PART_SIZE
    export MAX_WAIT_TIME

    # Run via sbt
    sbt "runMain com.pennsieve.publish.CsvS3OpsMain"

elif [ "$RUN_METHOD" = "docker" ]; then
    info "Running via Docker..."
    echo ""

    # Check if Docker is installed
    if ! command -v docker &> /dev/null; then
        error "Docker is not installed. Install Docker or use --method sbt"
    fi

    # Check for AWS credentials
    if [ -z "$AWS_ACCESS_KEY_ID" ] && [ ! -f "$HOME/.aws/credentials" ]; then
        error "AWS credentials not found. Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY or configure ~/.aws/credentials"
    fi

    # Build docker run command
    DOCKER_ARGS=(
        "run"
        "--rm"
        "-e" "CSV_FILE_PATH=$CSV_FILE_PATH"
        "-e" "AWS_REGION=$AWS_REGION"
        "-e" "PARALLELISM=$PARALLELISM"
        "-e" "MAX_PART_SIZE=$MAX_PART_SIZE"
        "-e" "MAX_WAIT_TIME=$MAX_WAIT_TIME"
    )

    # Add AWS credentials
    if [ -n "$AWS_ACCESS_KEY_ID" ]; then
        DOCKER_ARGS+=("-e" "AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID")
        DOCKER_ARGS+=("-e" "AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY")
        if [ -n "$AWS_SESSION_TOKEN" ]; then
            DOCKER_ARGS+=("-e" "AWS_SESSION_TOKEN=$AWS_SESSION_TOKEN")
        fi
    else
        # Mount AWS credentials directory
        DOCKER_ARGS+=("-v" "$HOME/.aws:/root/.aws:ro")
    fi

    # If CSV file is local, mount it
    if [[ ! "$CSV_FILE_PATH" =~ ^s3:// ]]; then
        # Get absolute path
        CSV_ABS_PATH=$(cd "$(dirname "$CSV_FILE_PATH")" && pwd)/$(basename "$CSV_FILE_PATH")
        CSV_DIR=$(dirname "$CSV_ABS_PATH")
        CSV_FILENAME=$(basename "$CSV_ABS_PATH")

        if [ ! -f "$CSV_ABS_PATH" ]; then
            error "CSV file not found: $CSV_FILE_PATH"
        fi

        info "Mounting local CSV file..."
        DOCKER_ARGS+=("-v" "$CSV_DIR:/data:ro")
        DOCKER_ARGS+=("-e" "CSV_FILE_PATH=/data/$CSV_FILENAME")
    fi

    # Add image name
    DOCKER_ARGS+=("$DOCKER_IMAGE")

    # Run Docker
    info "Executing: docker ${DOCKER_ARGS[*]}"
    echo ""
    docker "${DOCKER_ARGS[@]}"
fi

echo ""
success "Complete!"
echo ""
