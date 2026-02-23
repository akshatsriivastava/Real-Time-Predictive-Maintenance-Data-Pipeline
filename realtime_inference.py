#!/usr/bin/env python3
"""
realtime_inference.py

Phase 5: Real-Time Inference for Predictive Maintenance.

This script:
- Loads a trained Random Forest model from `predictive_maintenance_rf_model.joblib`.
- Connects to AWS IoT Core over MQTT using X.509 certificates.
- Subscribes to the `factory/telemetry` topic.
- Runs real-time inference on incoming telemetry (temperature, vibration).
- Prints NORMAL vs ANOMALY alerts to the console.

Dependencies (install in your Python environment):
  pip install joblib pandas numpy paho-mqtt

Note: You must fill in the AWS IoT Core connection placeholders below.
"""

from __future__ import annotations

import json
import ssl
from typing import Any, Dict

import joblib
import numpy as np
import pandas as pd
import paho.mqtt.client as mqtt


# ==============================
# AWS IoT Core configuration
# ==============================

# TODO: Replace these placeholders with your actual AWS IoT Core configuration.
# - ENDPOINT: Your AWS IoT Device data endpoint (Settings → Device data endpoint),
#             e.g. "xxxxxxxxxxxx-ats.iot.us-east-1.amazonaws.com"
# - PATH_TO_CERT: Device certificate PEM file (e.g. "./certs/certificate.pem.crt")
# - PATH_TO_KEY: Private key PEM file (e.g. "./certs/private.pem.key")
# - PATH_TO_ROOT_CA: Root CA PEM file (e.g. "./certs/AmazonRootCA1.pem")

ENDPOINT = "a1bc8ob2maqsyv-ats.iot.us-east-1.amazonaws.com"
CLIENT_ID = "inference_client"
PATH_TO_CERT = "./certs/certificate.pem.crt"
PATH_TO_KEY = "./certs/private.pem.key"
PATH_TO_ROOT_CA = "./certs/AmazonRootCA1.pem"
TOPIC = "factory/telemetry"


# ANSI colors for console highlighting (works in most terminals).
COLOR_RESET = "\033[0m"
COLOR_GREEN = "\033[92m"
COLOR_RED = "\033[91m"
COLOR_BOLD = "\033[1m"


def load_model(path: str = "predictive_maintenance_rf_model.joblib"):
    """
    Load the trained Random Forest model from disk.
    """
    print(f"Loading model from: {path}")
    model = joblib.load(path)
    print("Model loaded successfully.")
    return model


def build_features(temperature: float, vibration: float) -> pd.DataFrame:
    """
    Create a one-row DataFrame with the same feature schema used during training.
    Adjust this if your training features change.
    """
    return pd.DataFrame(
        {
            "temperature": [float(temperature)],
            "vibration": [float(vibration)],
        }
    )


def on_connect(client: mqtt.Client, userdata: Dict[str, Any], flags: Dict[str, Any], rc: int):
    """
    MQTT on_connect callback: subscribe to the telemetry topic once connected.
    """
    if rc == 0:
        print(f"Connected to AWS IoT Core as client '{CLIENT_ID}'. Subscribing to '{TOPIC}'...")
        client.subscribe(TOPIC, qos=1)
    else:
        print(f"Connection failed with result code {rc}")


def on_message(client: mqtt.Client, userdata: Dict[str, Any], msg: mqtt.MQTTMessage):
    """
    MQTT on_message callback: parse telemetry, run inference, and print alerts.
    """
    model = userdata.get("model")
    if model is None:
        print("Model not found in userdata; cannot run inference.")
        return

    try:
        payload_str = msg.payload.decode("utf-8")
        data = json.loads(payload_str)
    except Exception as exc:
        print(f"Failed to decode/parse message on topic '{msg.topic}': {exc}")
        return

    # Extract features from the incoming telemetry.
    try:
        temperature = float(data["temperature"])
        vibration = float(data["vibration"])
    except (KeyError, TypeError, ValueError) as exc:
        print(f"Missing or invalid fields in payload: {data} ({exc})")
        return

    # Build feature matrix and run prediction.
    X = build_features(temperature, vibration)
    try:
        # Assuming the model's predict returns [0] or [1] for this single row.
        prediction = model.predict(X)[0]
    except Exception as exc:
        print(f"Model prediction failed: {exc}")
        return

    # Print human-friendly alerting output.
    if int(prediction) == 1:
        # High-visibility anomaly alert.
        msg_text = (
            f"{COLOR_RED}{COLOR_BOLD}🚨 [ALERT] ANOMALY DETECTED! "
            f"Machine failure imminent! Temp: {temperature:.2f}°C, Vib: {vibration:.2f} mm/s 🚨{COLOR_RESET}"
        )
    else:
        # Subtle normal message.
        msg_text = (
            f"{COLOR_GREEN}[NORMAL]{COLOR_RESET} "
            f"Temp: {temperature:.2f}°C, Vib: {vibration:.2f} mm/s"
        )

    print(msg_text)


def create_mqtt_client(model) -> mqtt.Client:
    """
    Create and configure a Paho MQTT client with TLS settings for AWS IoT Core.
    """
    client = mqtt.Client(client_id=CLIENT_ID)

    # Attach the model to userdata so callbacks can access it.
    client.user_data_set({"model": model})

    # Configure TLS using AWS IoT Core certificates.
    client.tls_set(
        ca_certs=PATH_TO_ROOT_CA,
        certfile=PATH_TO_CERT,
        keyfile=PATH_TO_KEY,
        cert_reqs=ssl.CERT_REQUIRED,
        tls_version=ssl.PROTOCOL_TLS_CLIENT,
        ciphers=None,
    )

    # AWS IoT Core requires Server Name Indication (SNI) with the endpoint hostname.
    client.tls_insecure_set(False)

    client.on_connect = on_connect
    client.on_message = on_message

    return client


def main() -> None:
    print("=== Real-Time Predictive Maintenance Inference ===")
    print("Loading model and connecting to AWS IoT Core...")
    model = load_model()

    client = create_mqtt_client(model)

    print(f"Connecting to {ENDPOINT} with client ID '{CLIENT_ID}'...")
    try:
        client.connect(ENDPOINT, port=8883, keepalive=60)
    except Exception as exc:
        print(f"Failed to connect to AWS IoT Core: {exc}")
        return

    print(f"Subscribed to topic '{TOPIC}'. Waiting for telemetry messages...")
    print("Press Ctrl+C to stop.")

    try:
        # Blocking network loop; use loop_start() if you want this non-blocking.
        client.loop_forever()
    except KeyboardInterrupt:
        print("\nInterrupted by user, disconnecting...")
    finally:
        try:
            client.disconnect()
        except Exception:
            pass
        print("Disconnected from AWS IoT Core.")


if __name__ == "__main__":
    main()

