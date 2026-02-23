#!/bin/sh
# Deploy Phase 2: IoT Core → Kinesis Data Streams
# Requires: AWS CLI and credentials (aws configure), or set AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$SCRIPT_DIR"

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
echo "Deploying Phase 2 stack (predictive-maintenance-phase2) in region $REGION..."

# Use project venv AWS CLI if present (path kept quoted for spaces)
if [ -x "$PROJECT_ROOT/.venv/bin/python" ]; then
  "$PROJECT_ROOT/.venv/bin/python" -m awscli cloudformation deploy \
    --template-file phase2-iot-kinesis.yaml \
    --stack-name predictive-maintenance-phase2 \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"
else
  aws cloudformation deploy \
    --template-file phase2-iot-kinesis.yaml \
    --stack-name predictive-maintenance-phase2 \
    --capabilities CAPABILITY_NAMED_IAM \
    --region "$REGION"
fi

echo "Done. Stack: predictive-maintenance-phase2"
echo "Kinesis stream: predictive-maintenance-telemetry"
echo "IoT rule: TelemetryToKinesis-predictive-maintenance-phase2"
echo ""
echo "Run your simulator (./mvnw exec:java) and check IoT Core / Kinesis for data."
