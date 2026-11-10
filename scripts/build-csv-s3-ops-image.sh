#!/bin/bash
set -e

# Script to build and optionally push the CSV S3 Copy Docker image
# Usage:
#   ./scripts/build-csv-s3-ops-image.sh              # Build only
#   ./scripts/build-csv-s3-ops-image.sh push [tag]   # Build and push to Docker Hub
#   ./scripts/build-csv-s3-ops-image.sh push-ecr [tag] # Build and push to ECR

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_JAR_FILE=csv-s3-ops.jar
cd "$PROJECT_ROOT"
echo "SCRIPT_DIR: $SCRIPT_DIR"
echo "PROJECT_ROOT: $PROJECT_ROOT"
echo "TARGET_JAR_FILE: $TARGET_JAR_FILE"

# Default values
PUSH_IMAGE=false
PUSH_TO_ECR=false
IMAGE_TAG="${2:-latest}"
IMAGE_NAME="s3-ops-machine"
DOCKER_HUB_ORG="pennsieve"

# Parse arguments
if [ "$1" == "push" ]; then
    PUSH_IMAGE=true
    PUSH_TO_ECR=false
elif [ "$1" == "push-ecr" ]; then
    PUSH_IMAGE=true
    PUSH_TO_ECR=true
fi

echo "==================================="
echo "Building S3 Copy Machine Docker Image"
echo "==================================="
echo "Image: $DOCKER_HUB_ORG/$IMAGE_NAME:$IMAGE_TAG"
echo "Push: $PUSH_IMAGE"
if [ "$PUSH_IMAGE" == "true" ]; then
    if [ "$PUSH_TO_ECR" == "true" ]; then
        echo "Registry: ECR"
    else
        echo "Registry: Docker Hub"
    fi
fi
echo ""

# Step 1: Build the assembly JAR
echo "Step 1: Building assembly JAR..."
sbt assembly

# Check if assembly was successful
SOURCE_JAR_FILE=`/bin/ls -1 $PROJECT_ROOT/discover-publish/target/scala-2.13/multipart-uploader-assembly-bootstrap-*.jar`
if [ ! -f $SOURCE_JAR_FILE ]; then
    echo "ERROR: Assembly JAR not found!"
    exit 1
fi

echo "Assembly JAR built successfully"
echo "$SOURCE_JAR_FILE"
echo ""

cp $SOURCE_JAR_FILE $TARGET_JAR_FILE

# Step 2: Build Docker image
echo "Step 2: Building Docker image..."
docker build \
    --build-arg JAR_FILE=$JAR_FILE \
    -f Dockerfile.csv-s3-ops \
    -t "$DOCKER_HUB_ORG/$IMAGE_NAME:$IMAGE_TAG" \
    .

echo "Docker image built successfully"
echo ""
rm -f $TARGET_JAR_FILE

# Step 3: Push image (if requested)
if [ "$PUSH_IMAGE" == "true" ]; then
    if [ "$PUSH_TO_ECR" == "true" ]; then
        # Push to ECR
        if [ -z "$ECR_REGISTRY" ]; then
            echo "ERROR: ECR_REGISTRY environment variable not set"
            echo "Example: export ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com"
            exit 1
        fi

        if [ -z "$AWS_REGION" ]; then
            export AWS_REGION="us-east-1"
            echo "Using default AWS region: $AWS_REGION"
        fi

        echo "Step 3: Tagging image for ECR..."
        docker tag "$DOCKER_HUB_ORG/$IMAGE_NAME:$IMAGE_TAG" "$ECR_REGISTRY/$IMAGE_NAME:$IMAGE_TAG"
        echo ""

        echo "Step 4: Logging in to ECR..."
        aws ecr get-login-password --region "$AWS_REGION" | \
            docker login --username AWS --password-stdin "$ECR_REGISTRY"
        echo ""

        echo "Step 5: Pushing image to ECR..."
        docker push "$ECR_REGISTRY/$IMAGE_NAME:$IMAGE_TAG"
        echo ""

        echo "==================================="
        echo "Image pushed successfully to ECR!"
        echo "Image: $ECR_REGISTRY/$IMAGE_NAME:$IMAGE_TAG"
        echo "==================================="
    else
        # Push to Docker Hub
        DOCKER_HUB_IMAGE="$DOCKER_HUB_ORG/$IMAGE_NAME:$IMAGE_TAG"

        echo "Step 3: Pushing to Docker Hub..."
        echo "Image: $DOCKER_HUB_IMAGE"
        echo ""

        echo "Step 4: Logging in to Docker Hub..."
        echo "Please log in to Docker Hub (organization: $DOCKER_HUB_ORG)"
        docker login
        echo ""

        echo "Step 5: Pushing image to Docker Hub..."
        docker push "$DOCKER_HUB_IMAGE"
        echo ""

        echo "==================================="
        echo "Image pushed successfully to Docker Hub!"
        echo "Image: $DOCKER_HUB_IMAGE"
        echo ""
        echo "To pull this image:"
        echo "  docker pull $DOCKER_HUB_IMAGE"
        echo "==================================="
    fi
else
    echo "==================================="
    echo "Build complete!"
    echo "Image: $DOCKER_HUB_ORG/$IMAGE_NAME:$IMAGE_TAG"
    echo ""
    echo "To push to Docker Hub, run:"
    echo "  ./scripts/build-csv-s3-ops-image.sh push $IMAGE_TAG"
    echo ""
    echo "To push to ECR, run:"
    echo "  export ECR_REGISTRY=<your-ecr-registry>"
    echo "  export AWS_REGION=<your-region>"
    echo "  ./scripts/build-csv-s3-ops-image.sh push-ecr $IMAGE_TAG"
    echo "==================================="
fi
