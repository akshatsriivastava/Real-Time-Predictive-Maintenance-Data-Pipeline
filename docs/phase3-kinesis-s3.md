# Phase 3: Kinesis → S3 (Data Lake)

This phase adds **Kinesis Data Firehose** to read from the Phase 2 Kinesis stream and deliver telemetry to **Amazon S3** (Data Lake).

## What gets created

| Resource | Purpose |
|----------|--------|
| **S3 bucket** | Data lake for telemetry (prefix `telemetry/`) |
| **Firehose delivery stream** | Reads from `predictive-maintenance-telemetry` Kinesis stream, writes to S3 |
| **IAM role** | Lets Firehose read from Kinesis and write to S3 |

Data is buffered (5 MB or 5 minutes) before writing to S3, compressed with GZIP.

## Prerequisites

- **Phase 2** deployed (`predictive-maintenance-phase2` stack with Kinesis stream).
- AWS CLI configured (same as Phase 2).

## Deploy

From the project root:

```bash
./infrastructure/deploy-phase3.sh
```

Or manually:

```bash
aws cloudformation deploy \
  --template-file infrastructure/phase3-kinesis-s3.yaml \
  --stack-name predictive-maintenance-phase3 \
  --capabilities CAPABILITY_NAMED_IAM
```

Optional parameters:
- `Phase2StackName` – if Phase 2 stack has a different name (default: `predictive-maintenance-phase2`)
- `DataLakeBucketNameOverride` – custom S3 bucket name (default: `predictive-maintenance-datalake-ACCOUNT_ID`)

## Verify

1. **Run the simulator** (if not already): `./mvnw exec:java`
2. **Wait for buffering** – Firehose flushes every 5 MB or 5 minutes; for low volume, wait up to 5 minutes.
3. **Check S3** – In AWS Console → S3, open the data lake bucket and look under prefix `telemetry/`. You should see objects (e.g. `telemetry/2024/01/15/14/...` depending on Firehose’s default structure).

## Tear down

```bash
aws cloudformation delete-stack --stack-name predictive-maintenance-phase3
```

Empty the S3 bucket first if needed, or delete the bucket after the stack fails (bucket must be empty to delete).

## Next (Phase 4)

Use the S3 telemetry data to train a **SageMaker** model for anomaly or failure prediction.
