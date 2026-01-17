#!/bin/bash
# Deploy all Lambda functions or specific ones
# Usage: 
#   ./deploy-all.sh              - Deploy all lambdas
#   ./deploy-all.sh --changed    - Deploy only changed lambdas (based on git)
#   ./deploy-all.sh --list       - List all configured lambdas
#   ./deploy-all.sh Lambda1 Lambda2  - Deploy specific lambdas

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
CONFIG_FILE="$PROJECT_ROOT/lambda-config.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo -e "${RED}Error: jq is required but not installed.${NC}"
    exit 1
fi

# Get all lambda names from config
get_all_lambdas() {
    jq -r '.lambdas | keys[]' "$CONFIG_FILE"
}

# Get changed lambdas based on git diff
get_changed_lambdas() {
    local base_ref="${1:-HEAD~1}"
    local changed_dirs=$(git diff --name-only "$base_ref" HEAD 2>/dev/null | grep "^Lambda" | cut -d'/' -f1 | sort -u)
    
    if [ -z "$changed_dirs" ]; then
        echo ""
        return
    fi
    
    # Filter to only include configured lambdas
    for dir in $changed_dirs; do
        if jq -e ".lambdas[\"$dir\"]" "$CONFIG_FILE" &> /dev/null; then
            echo "$dir"
        fi
    done
}

# Show help
show_help() {
    echo "Deploy Lambda Functions"
    echo ""
    echo "Usage:"
    echo "  $0                    Deploy all configured lambdas"
    echo "  $0 --changed          Deploy only changed lambdas (git diff)"
    echo "  $0 --changed-from REF Deploy lambdas changed since REF"
    echo "  $0 --list             List all configured lambdas"
    echo "  $0 --parallel         Deploy all lambdas in parallel"
    echo "  $0 Lambda1 Lambda2    Deploy specific lambdas"
    echo ""
    echo "Examples:"
    echo "  $0 LambdaUploadOrchestrator LambdaTokenChecker"
    echo "  $0 --changed-from origin/main"
}

# List all lambdas
list_lambdas() {
    echo -e "${BLUE}Configured Lambda Functions:${NC}"
    echo ""
    jq -r '.lambdas | to_entries[] | "  \(.key) -> \(.value.functionName)"' "$CONFIG_FILE"
}

# Deploy a single lambda
deploy_lambda() {
    local lambda_name="$1"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    "$SCRIPT_DIR/deploy-lambda.sh" "$lambda_name"
}

# Main logic
LAMBDAS_TO_DEPLOY=()
PARALLEL=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)
            show_help
            exit 0
            ;;
        --list)
            list_lambdas
            exit 0
            ;;
        --changed)
            mapfile -t LAMBDAS_TO_DEPLOY < <(get_changed_lambdas)
            shift
            ;;
        --changed-from)
            mapfile -t LAMBDAS_TO_DEPLOY < <(get_changed_lambdas "$2")
            shift 2
            ;;
        --parallel)
            PARALLEL=true
            shift
            ;;
        *)
            LAMBDAS_TO_DEPLOY+=("$1")
            shift
            ;;
    esac
done

# If no specific lambdas provided, deploy all
if [ ${#LAMBDAS_TO_DEPLOY[@]} -eq 0 ]; then
    mapfile -t LAMBDAS_TO_DEPLOY < <(get_all_lambdas)
fi

# Check if we have anything to deploy
if [ ${#LAMBDAS_TO_DEPLOY[@]} -eq 0 ]; then
    echo -e "${YELLOW}No lambdas to deploy.${NC}"
    exit 0
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Lambda Deployment${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Lambdas to deploy (${#LAMBDAS_TO_DEPLOY[@]}):${NC}"
for lambda in "${LAMBDAS_TO_DEPLOY[@]}"; do
    echo "  - $lambda"
done
echo ""

# Track results
SUCCESSFUL=()
FAILED=()

# Deploy lambdas
if [ "$PARALLEL" = true ]; then
    echo -e "${YELLOW}Deploying in parallel...${NC}"
    
    # Build all first
    for lambda in "${LAMBDAS_TO_DEPLOY[@]}"; do
        (
            cd "$PROJECT_ROOT/$lambda"
            mvn clean package -q -DskipTests
        ) &
    done
    wait
    
    # Then deploy all
    for lambda in "${LAMBDAS_TO_DEPLOY[@]}"; do
        "$SCRIPT_DIR/deploy-lambda.sh" "$lambda" --skip-build &
    done
    wait
else
    for lambda in "${LAMBDAS_TO_DEPLOY[@]}"; do
        if deploy_lambda "$lambda"; then
            SUCCESSFUL+=("$lambda")
        else
            FAILED+=("$lambda")
        fi
    done
fi

# Summary
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment Summary${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Successful: ${#SUCCESSFUL[@]}${NC}"
for lambda in "${SUCCESSFUL[@]}"; do
    echo -e "  ${GREEN}✓${NC} $lambda"
done

if [ ${#FAILED[@]} -gt 0 ]; then
    echo -e "${RED}Failed: ${#FAILED[@]}${NC}"
    for lambda in "${FAILED[@]}"; do
        echo -e "  ${RED}✗${NC} $lambda"
    done
    exit 1
fi

echo ""
echo -e "${GREEN}All deployments completed successfully!${NC}"
