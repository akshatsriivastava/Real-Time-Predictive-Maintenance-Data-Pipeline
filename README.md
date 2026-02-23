# Real-Time Predictive Maintenance Data Pipeline – Phase 1: IoT Simulator

Phase 1 is a **Java Maven** project that simulates a factory machine and publishes telemetry to **AWS IoT Core** over MQTT with X.509 authentication.

## Prerequisites

- **Java 17 or later** (JDK). Either:
  - **Use the project-local JDK** (in `.jdk/`): put it on your PATH, then use `./mvnw`:
    ```bash
    export JAVA_HOME="$(pwd)/.jdk/jdk-17.0.18+8/Contents/Home"
    export PATH="$JAVA_HOME/bin:$PATH"
    ```
    Or in this directory run `source env.sh` (same effect for the current shell). To make it permanent, add those two lines to `~/.zshrc` (or `~/.bashrc`).
  - Or use `./run.sh` instead of `./mvnw` (run.sh sets JAVA_HOME for you).
  - Or install Java globally (Homebrew, [Temurin](https://adoptium.net/), etc.) and use `./mvnw` with that.
- **Maven** is not required globally: the project includes **Maven Wrapper** (`./mvnw`). The first run downloads Maven automatically (network and `~/.m2` write access required).

## Architecture (overview)

1. **Java IoT Simulator** (this repo) → 2. AWS IoT Core (MQTT) → 3. Amazon Kinesis → 4. S3 (Data Lake) → 5. SageMaker (ML) → 6. Lambda & SNS (Alerting)

**Phase 2 (IoT → Kinesis):** Deploy `infrastructure/phase2-iot-kinesis.yaml` so telemetry is forwarded to a Kinesis stream. See **[docs/phase2-iot-kinesis.md](docs/phase2-iot-kinesis.md)**.

**Phase 3 (Kinesis → S3 Data Lake):** Deploy `infrastructure/phase3-kinesis-s3.yaml` so Firehose delivers telemetry from Kinesis to S3. See **[docs/phase3-kinesis-s3.md](docs/phase3-kinesis-s3.md)**.

## Project layout

```
├── pom.xml
├── config.properties.example
├── infrastructure/
│   ├── phase2-iot-kinesis.yaml   # IoT → Kinesis (Phase 2)
│   └── phase3-kinesis-s3.yaml    # Kinesis → S3 Data Lake (Phase 3)
├── docs/
│   ├── phase2-iot-kinesis.md     # Phase 2 deploy & verify
│   └── phase3-kinesis-s3.md      # Phase 3 deploy & verify
└── src/main/java/com/predictivemaintenance/simulator/
    ├── AwsIotSslUtil.java   # X.509 → SSLSocketFactory for AWS IoT
    └── IoTSimulator.java   # Telemetry generation + MQTT publish
```

## Build and run

From the project root. Ensure Java 17+ is on your PATH (e.g. `source env.sh` to use the project JDK, or use `./run.sh` which sets it for you).

- **Build:** `./mvnw clean compile`
- **Run:** `./mvnw exec:java`  
  Or build a fat JAR: `./mvnw assembly:single` then  
  `java -jar target/iot-simulator-1.0.0-SNAPSHOT-jar-with-dependencies.jar`

## Configuration (certificates and endpoint)

1. **Put your AWS files in place**
   - Place the downloaded **Root CA**, **device certificate**, and **private key** in the `certs/` directory (e.g. `certs/AmazonRootCA1.pem`, `certs/certificate.pem.crt`, `certs/private.pem.key`).

2. **Configure the simulator** (use one of these):
   - **Option A (recommended):** Copy the example config and edit:
     ```bash
     cp config.properties.example config.properties
     ```
     Edit `config.properties`: set `iot.endpoint` to your **Device data endpoint** (AWS IoT Core → Settings, e.g. `xxxxxxxxxxxx.iot.us-east-1.amazonaws.com`). Adjust `iot.rootCaPath`, `iot.certPath`, `iot.privateKeyPath` if your filenames differ.
   - **Option B:** Set environment variables: `AWS_IOT_BROKER`, `AWS_IOT_ROOT_CA`, `AWS_IOT_CERT`, `AWS_IOT_PRIVATE_KEY`.
   - **Option C:** Edit the defaults in `IoTSimulator.java` (lines ~78–81).

3. **Attach an IoT Policy** to your certificate so the device can **connect** and **publish** to `factory/telemetry`. In IoT Core → Security → Policies, create or edit a policy with:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "iot:Connect",
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "iot:Publish",
      "Resource": "arn:aws:iot:REGION:ACCOUNT_ID:topic/factory/telemetry"
    }
  ]
}
```

Replace `REGION` and `ACCOUNT_ID` with your values.

## Phase 4: Machine Learning Model Training (AWS SageMaker)

This phase turns the raw telemetry into a production-style anomaly detection model, fully aligned with cloud and FinOps best practices.

- **Environment**
  - Spun up an **`ml.t3.medium` Jupyter Notebook** instance on **AWS SageMaker** to perform interactive data exploration, feature engineering, and model training.

- **Data ingestion**
  - Loaded **10,000 rows of synthetic historical telemetry data** directly from the **Amazon S3 Data Lake** into a `pandas` DataFrame.
  - Data mirrored the real-time simulator schema: `timestamp`, `machineId`, `temperature`, `vibration`, and `is_anomaly`.

- **Features & target**
  - **Target:** `is_anomaly` – binary indicator of pre-failure / anomalous machine state.
  - **Features:** `temperature` and `vibration` readings, matching the live telemetry produced by the Java IoT simulator.

- **Algorithm & training**
  - Trained a **Random Forest Classifier** using **scikit-learn**, chosen for its robustness and ease of interpretation for a first production prototype.
  - Used the synthetic historical dataset to learn decision boundaries separating normal operation from pre-failure spikes.

- **Results**
  - Achieved **perfect precision and recall (1.00 / 1.00)** on held-out synthetic data due to the **linearly separable anomaly baselines**, establishing a strong prototype boundary for anomaly detection.
  - The model cleanly distinguishes failures (high temperature and vibration) from nominal behavior, ideal for driving downstream alerting.

- **Model artifact**
  - Serialized the trained model using **`joblib`** into a file named **`predictive_maintenance_rf_model.joblib`**.
  - Downloaded the artifact for **local real-time inference**, so the same decision logic can be applied close to the stream (e.g., in Lambda or an edge service).

- **FinOps considerations**
  - The **SageMaker `ml.t3.medium` notebook instance was manually stopped immediately after training** completed.
  - This minimizes idle compute spend and demonstrates **strong FinOps discipline** by ensuring ML experimentation does not leave long‑running cloud resources accumulating unnecessary cost.

## Telemetry format

Each message is a JSON object on topic **`factory/telemetry`**:

- `machineId`: e.g. `"CNC_Machine_A"`
- `temperature`: double, °C (normal 65–70; anomaly +15)
- `vibration`: double, mm/s (normal 1.2–1.5; anomaly +2.0)
- `timestamp`: Unix time (seconds)

About **10%** of messages are anomalies (spike in temperature and vibration) to simulate pre-failure for ML and alerting.
