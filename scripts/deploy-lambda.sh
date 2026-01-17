#!/bin/bash
# Deploy a single Lambda function to AWS
# Usage: ./deploy-lambda.sh <project-folder-name> [--skip-build]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
CONFIG_FILE="$PROJECT_ROOT/lambda-config.json"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo -e "${RED}Error: jq is required but not installed.${NC}"
    echo "Install it with: sudo apt-get install jq (Ubuntu) or brew install jq (Mac)"
    exit 1
fi

# Check arguments
if [ -z "$1" ]; then
    echo -e "${RED}Error: Please provide a Lambda project folder name${NC}"
    echo "Usage: $0 <project-folder-name> [--skip-build]"
    echo ""
    echo "Available Lambda projects:"
    jq -r '.lambdas | keys[]' "$CONFIG_FILE"
    exit 1
fi

PROJECT_NAME="$1"
SKIP_BUILD=false

if [ "$2" == "--skip-build" ]; then
    SKIP_BUILD=true
fi

# Check if project folder exists
PROJECT_DIR="$PROJECT_ROOT/$PROJECT_NAME"
if [ ! -d "$PROJECT_DIR" ]; then
    echo -e "${RED}Error: Project folder '$PROJECT_NAME' not found${NC}"
    exit 1
fi

# Read configuration
FUNCTION_NAME=$(jq -r ".lambdas[\"$PROJECT_NAME\"].functionName // empty" "$CONFIG_FILE")
HANDLER=$(jq -r ".lambdas[\"$PROJECT_NAME\"].handler // empty" "$CONFIG_FILE")
REGION=$(jq -r ".region // \"us-east-1\"" "$CONFIG_FILE")
RUNTIME=$(jq -r ".runtime // \"java21\"" "$CONFIG_FILE")
TIMEOUT=$(jq -r ".timeout // 30" "$CONFIG_FILE")
MEMORY=$(jq -r ".memorySize // 512" "$CONFIG_FILE")

# Check if Lambda is configured
if [ -z "$FUNCTION_NAME" ]; then
    echo -e "${RED}Error: Lambda '$PROJECT_NAME' not found in lambda-config.json${NC}"
    echo "Please add configuration for this Lambda in lambda-config.json"
    exit 1
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deploying: $FUNCTION_NAME${NC}"
echo -e "${GREEN}========================================${NC}"
echo "Project: $PROJECT_NAME"
echo "Handler: $HANDLER"
echo "Region: $REGION"
echo "Runtime: $RUNTIME"
echo ""

# Build the project
if [ "$SKIP_BUILD" = false ]; then
    echo -e "${YELLOW}Building project...${NC}"
    cd "$PROJECT_DIR"
    mvn clean package -q -DskipTests
    echo -e "${GREEN}Build successful!${NC}"
else
    echo -e "${YELLOW}Skipping build (--skip-build flag set)${NC}"
fi

# Find the JAR file
JAR_FILE=$(find "$PROJECT_DIR/target" -name "*.jar" ! -name "*original*" -type f 2>/dev/null | head -1)

if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}Error: No JAR file found in target directory${NC}"
    exit 1
fi

echo "JAR file: $JAR_FILE"

# Check if function exists
echo -e "${YELLOW}Checking if function exists...${NC}"
if aws lambda get-function --function-name "$FUNCTION_NAME" --region "$REGION" &> /dev/null; then
    # Update existing function
    echo -e "${YELLOW}Updating existing function...${NC}"
    aws lambda update-function-code \
        --function-name "$FUNCTION_NAME" \
        --zip-file "fileb://$JAR_FILE" \
        --region "$REGION" \
        --no-cli-pager

    # Wait for update to complete
    echo -e "${YELLOW}Waiting for update to complete...${NC}"
    aws lambda wait function-updated --function-name "$FUNCTION_NAME" --region "$REGION"

    # Update configuration if needed
    aws lambda update-function-configuration \
        --function-name "$FUNCTION_NAME" \
        --handler "$HANDLER" \
        --timeout "$TIMEOUT" \
        --memory-size "$MEMORY" \
        --region "$REGION" \
        --no-cli-pager &> /dev/null || true

    echo -e "${GREEN}✓ Function updated successfully!${NC}"
else
    echo -e "${RED}Error: Function '$FUNCTION_NAME' does not exist.${NC}"
    echo "This script only updates existing functions."
    echo "Please create the function first in AWS Console or use AWS CLI."
    exit 1
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment complete: $FUNCTION_NAME${NC}"
echo -e "${GREEN}========================================${NC}"
