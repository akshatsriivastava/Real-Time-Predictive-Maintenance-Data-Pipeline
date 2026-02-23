package com.predictivemaintenance.simulator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * IoT Simulator for the Real-Time Predictive Maintenance Data Pipeline.
 * <p>
 * Simulates a single factory machine (e.g. CNC_Machine_A) by publishing telemetry
 * JSON payloads to an MQTT topic every second. Intended to connect to AWS IoT Core,
 * which can then forward messages to Kinesis → S3 (Data Lake) and SageMaker for
 * predictive maintenance ML.
 * <p>
 * Payload fields: machineId, temperature (°C), vibration (mm/s), timestamp (Unix).
 * About 10% of messages are "anomalies" (elevated temperature and vibration) to
 * simulate pre-failure conditions for ML training and alerting.
 */
public class IoTSimulator {

    /** MQTT topic used for factory telemetry (consumed by AWS IoT rules → Kinesis, etc.). */
    public static final String TOPIC_TELEMETRY = "factory/telemetry";

    /** Simulated machine identifier. */
    private static final String MACHINE_ID = "NC_Machine_AC";

    /** Normal temperature range (°C). */
    private static final double TEMP_MIN = 65.0;
    private static final double TEMP_MAX = 70.0;

    /** Normal vibration range (mm/s). */
    private static final double VIBRATION_MIN = 1.2;
    private static final double VIBRATION_MAX = 1.5;

    /** Anomaly: extra temperature and vibration to simulate pre-failure. */
    private static final double ANOMALY_TEMP_DELTA = 15.0;
    private static final double ANOMALY_VIBRATION_DELTA = 2.0;

    /** Probability of emitting an anomaly (0.10 = 10%). */
    private static final double ANOMALY_PROBABILITY = 0.10;

    /** Publish interval in seconds. */
    private static final int PUBLISH_INTERVAL_SECONDS = 1;

    /** QoS for MQTT publish (0 = at most once, 1 = at least once, 2 = exactly once). */
    private static final int MQTT_QOS = 1;

    private final Random random = new Random();
    private final Gson gson = new GsonBuilder().create();

    /**
     * Telemetry payload matching the pipeline schema (machineId, temperature, vibration, timestamp).
     */
    public static class TelemetryPayload {
        public final String machineId;
        public final double temperature;
        public final double vibration;
        public final long timestamp;

        public TelemetryPayload(String machineId, double temperature, double vibration, long timestamp) {
            this.machineId = machineId;
            this.temperature = temperature;
            this.vibration = vibration;
            this.timestamp = timestamp;
        }
    }

    public static void main(String[] args) {
        // Load config: config.properties (if present) → env vars → defaults.
        String broker = "ssl://a1bc8ob2maqsyv-ats.iot.ap-south-1.amazonaws.com:8883";
        String rootCaPath = "/Users/akshat/Real-Time Predictive Maintenance Data Pipeline/certs/AmazonRootCA1.pem";
        String certPath = "/Users/akshat/Real-Time Predictive Maintenance Data Pipeline/certs/certificate.pem.crt";
        String privateKeyPath = "/Users/akshat/Real-Time Predictive Maintenance Data Pipeline/certs/private.pem.key";

        Path configPath = Path.of(System.getProperty("user.dir", "."), "config.properties");
        if (Files.isRegularFile(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                Properties p = new Properties();
                p.load(in);
                if (p.getProperty("iot.endpoint") != null) {
                    String endpoint = p.getProperty("iot.endpoint").trim();
                    broker = "ssl://" + endpoint + ":8883";
                }
                if (p.getProperty("iot.rootCaPath") != null) rootCaPath = p.getProperty("iot.rootCaPath").trim();
                if (p.getProperty("iot.certPath") != null) certPath = p.getProperty("iot.certPath").trim();
                if (p.getProperty("iot.privateKeyPath") != null) privateKeyPath = p.getProperty("iot.privateKeyPath").trim();
            } catch (Exception e) {
                System.err.println("Warning: could not load config.properties: " + e.getMessage());
            }
        }

        if (System.getenv("AWS_IOT_BROKER") != null) broker = System.getenv("AWS_IOT_BROKER");
        if (System.getenv("AWS_IOT_ROOT_CA") != null) rootCaPath = System.getenv("AWS_IOT_ROOT_CA");
        if (System.getenv("AWS_IOT_CERT") != null) certPath = System.getenv("AWS_IOT_CERT");
        if (System.getenv("AWS_IOT_PRIVATE_KEY") != null) privateKeyPath = System.getenv("AWS_IOT_PRIVATE_KEY");

        IoTSimulator simulator = new IoTSimulator();
        try {
            simulator.run(broker, rootCaPath, certPath, privateKeyPath);
        } catch (Exception e) {
            System.err.println("Simulator failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Connects to the MQTT broker using X.509 certificates and publishes telemetry every second.
     */
    public void run(String broker, String rootCaPath, String certPath, String privateKeyPath) throws Exception {
        // Build SSL socket factory from AWS IoT PEM files (Root CA + device cert + private key).
        SSLSocketFactory socketFactory = AwsIotSslUtil.createSocketFactory(rootCaPath, certPath, privateKeyPath);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setSocketFactory(socketFactory);
        options.setCleanSession(true);
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);

        // Client ID is set on the MqttClient constructor (unique per connection; AWS IoT may use it for shadow/logs).
        String clientId = MACHINE_ID + "_sim_" + System.currentTimeMillis();
        try (MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence())) {
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.err.println("Connection lost: " + (cause != null ? cause.getMessage() : "unknown"));
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    // We only publish; no subscriptions needed for this simulator.
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Optional: log delivery success/failure for QoS 1/2.
                }
            });

            System.out.println("Connecting to " + broker + " ...");
            client.connect(options);
            System.out.println("Connected. Publishing telemetry to topic: " + TOPIC_TELEMETRY);

            while (true) {
                boolean isAnomaly = random.nextDouble() < ANOMALY_PROBABILITY;
                TelemetryPayload payload = nextPayload(isAnomaly);
                String json = gson.toJson(payload);

                MqttMessage message = new MqttMessage(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                message.setQos(MQTT_QOS);
                message.setRetained(false);

                client.publish(TOPIC_TELEMETRY, message);
                System.out.println("[PUB] " + json + (isAnomaly ? " [ANOMALY]" : ""));

                TimeUnit.SECONDS.sleep(PUBLISH_INTERVAL_SECONDS);
            }
        }
    }

    /**
     * Generates one telemetry payload. If {@code anomaly} is true, values are
     * boosted to simulate a pre-failure spike (temperature +15°C, vibration +2.0 mm/s).
     */
    private TelemetryPayload nextPayload(boolean anomaly) {
        double temperature = nextDouble(TEMP_MIN, TEMP_MAX);
        double vibration = nextDouble(VIBRATION_MIN, VIBRATION_MAX);
        if (anomaly) {
            temperature += ANOMALY_TEMP_DELTA;
            vibration += ANOMALY_VIBRATION_DELTA;
        }

        long timestamp = System.currentTimeMillis() / 1000;
        return new TelemetryPayload(MACHINE_ID, temperature, vibration, timestamp);
    }

    private double nextDouble(double minInclusive, double maxInclusive) {
        return minInclusive + (maxInclusive - minInclusive) * random.nextDouble();
    }
}
