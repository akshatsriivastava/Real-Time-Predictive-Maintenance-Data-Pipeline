# Phase 2: AWS IoT Core → Kinesis Data Streams

This phase forwards MQTT messages from the topic **`factory/telemetry`** to an **Amazon Kinesis Data Stream**, so downstream (Firehose → S3, Lambda, etc.) can process telemetry in real time.

## What gets created

| Resource | Purpose |
|----------|--------|
| **Kinesis Data Stream** | Holds telemetry records (e.g. `predictive-maintenance-telemetry`) |
| **IAM role** | Lets the IoT rule write to the stream |
| **IoT Topic Rule** | SQL `SELECT * FROM 'factory/telemetry'` → Kinesis `PutRecord` |

Each simulator payload is written to Kinesis with **partition key** = `machineId` (so one machine’s data tends to stay on the same shard).

## Prerequisites

- Phase 1 simulator is publishing to AWS IoT Core on topic `factory/telemetry`.
- AWS CLI installed and configured (`aws configure`) with permissions to create Kinesis streams, IAM roles, and IoT rules.

## Deploy (one-time)

**Option A – from project root:**

```bash
aws cloudformation deploy \
  --template-file infrastructure/phase2-iot-kinesis.yaml \
  --stack-name predictive-maintenance-phase2 \
  --capabilities CAPABILITY_NAMED_IAM
```

**Option B – run the script:**

```bash
./infrastructure/deploy-phase2.sh
```

**AWS CLI and credentials:** If you don’t have the AWS CLI, from the project root run:
```bash
python3 -m venv .venv && .venv/bin/pip install awscli
```
Then configure once (use your AWS access key and region, e.g. `us-east-1`):
```bash
.venv/bin/python -m awscli configure
# Or export: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION
```
The script `infrastructure/deploy-phase2.sh` will use the project’s `.venv` AWS CLI if present and defaults to region `us-east-1` unless `AWS_DEFAULT_REGION` is set.

Optional overrides:

- `StreamName` – Kinesis stream name (default: `predictive-maintenance-telemetry`).
- `IoTTelemetryTopic` – MQTT topic (default: `factory/telemetry`).

Example with parameters:

```bash
aws cloudformation deploy \
  --template-file infrastructure/phase2-iot-kinesis.yaml \
  --stack-name predictive-maintenance-phase2 \
  --parameter-overrides StreamName=my-telemetry-stream \
  --capabilities CAPABILITY_NAMED_IAM
```

## Verify

1. **Run the simulator** (if not already):  
   `./mvnw exec:java`

2. **Check Kinesis for data** (replace `STREAM_NAME` and `REGION` if you changed them):

   - **Console:** Kinesis → Data streams → your stream → “Monitoring” or use “Process data” / “Get records” in the stream view.
   - **CLI:** List shards (On-Demand streams create shards as needed), then get records:
     ```bash
     SHARD_ID=$(aws kinesis list-shards --stream-name STREAM_NAME --region REGION --query 'Shards[0].ShardId' --output text)
     ITER=$(aws kinesis get-shard-iterator --stream-name STREAM_NAME --shard-id $SHARD_ID --shard-iterator-type TRIM_HORIZON --region REGION --query 'ShardIterator' --output text)
     aws kinesis get-records --shard-iterator $ITER --region REGION
     ```
     Decode the `Data` field (base64) to see your JSON telemetry.

3. **IoT rule metrics** (optional):  
   In **AWS Console → IoT Core → Message routing → Rules**, open the rule `TelemetryToKinesis-...` and check the **Metrics** tab for invocations and successes.

## Tear down

To remove Phase 2 resources:

```bash
aws cloudformation delete-stack --stack-name predictive-maintenance-phase2
```

Empty the Kinesis stream first if it has data (or wait for retention to expire).

## Next (Phase 3)

Use the same Kinesis stream (or a new one) with **Kinesis Data Firehose** to persist telemetry to **S3** (Data Lake) and/or trigger **Lambda** for transformation or alerting.
