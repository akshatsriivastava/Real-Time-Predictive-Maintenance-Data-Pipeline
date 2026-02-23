#!/bin/sh
# Deploy Phase 3: Kinesis → S3 Data Lake
# Requires: Phase 2 stack deployed, AWS CLI and credentials

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
echo "Deploying Phase 3 stack (predictive-maintenance-phase3) in region $REGION..."

if [ -x "$PROJECT_ROOT/.venv/bin/python" ]; then
  "$PROJECT_ROOT/.venv/bin/python" -m awscli cloudformation deploy \
    --template-file phase3-kinesis-s3.yaml \
    --stack-name predictive-maintenance-phase3 \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"
else
  aws cloudformation deploy \
    --template-file phase3-kinesis-s3.yaml \
    --stack-name predictive-maintenance-phase3 \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"
fi

echo "Done. Stack: predictive-maintenance-phase3"
echo "S3 bucket: predictive-maintenance-datalake-ACCOUNT_ID (or override)"
echo "Firehose: predictive-maintenance-telemetry-firehose"
echo ""
echo "Run the simulator (./mvnw exec:java) and check S3 for telemetry/ objects after buffering (5 MB or 5 min)."
