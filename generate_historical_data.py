#!/usr/bin/env python3
"""
generate_historical_data.py

Generates a synthetic historical telemetry dataset for predictive maintenance.

Output:
  - historical_telemetry_data.csv (in the current working directory)

Data schema:
  - timestamp      : 1-minute interval timestamps (pandas datetime)
  - machineId      : constant string ("NC_Machine_AC")
  - temperature    : float (°C)
  - vibration      : float (mm/s)
  - is_anomaly     : int (0 = normal, 1 = anomaly/failure spike)

Requirements:
  pip install pandas numpy
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd


def main() -> None:
    print("Starting synthetic historical telemetry generation...")

    n_rows = 10_000
    machine_id = "NC_Machine_AC"

    # Create timestamps at 1-minute intervals.
    # We generate a continuous window ending 'now' (UTC) so it looks like historical data.
    end_ts = datetime.now(timezone.utc).replace(second=0, microsecond=0)
    start_ts = end_ts - timedelta(minutes=n_rows - 1)
    timestamps = pd.date_range(start=start_ts, periods=n_rows, freq="1min", tz="UTC")

    # Initialize arrays for telemetry.
    temperature = np.empty(n_rows, dtype=float)
    vibration = np.empty(n_rows, dtype=float)
    is_anomaly = np.zeros(n_rows, dtype=np.int32)

    # Choose anomaly indices: 5% of data.
    anomaly_count = int(round(n_rows * 0.05))
    rng = np.random.default_rng()  # non-deterministic seed; change to default_rng(42) for reproducibility
    anomaly_idx = rng.choice(n_rows, size=anomaly_count, replace=False)
    is_anomaly[anomaly_idx] = 1

    # Normal operations for the rest (95% of rows).
    normal_mask = is_anomaly == 0
    normal_count = int(normal_mask.sum())
    temperature[normal_mask] = rng.normal(loc=68.0, scale=3.0, size=normal_count)
    vibration[normal_mask] = rng.normal(loc=1.8, scale=0.3, size=normal_count)

    # Anomalies/failures (spikes).
    temperature[anomaly_idx] = rng.uniform(low=80.0, high=100.0, size=anomaly_count)
    vibration[anomaly_idx] = rng.uniform(low=3.0, high=5.0, size=anomaly_count)

    # Optional: guardrails for realism (ensure vibration can't be negative from the normal distribution).
    vibration = np.clip(vibration, a_min=0.0, a_max=None)

    df = pd.DataFrame(
        {
            "timestamp": timestamps,
            "machineId": machine_id,
            "temperature": temperature,
            "vibration": vibration,
            "is_anomaly": is_anomaly,
        }
    )

    output_name = "historical_telemetry_data.csv"
    output_path = os.path.join(os.getcwd(), output_name)
    df.to_csv(output_path, index=False)

    print(f"Finished. Generated {len(df):,} rows.")
    print(f"Saved CSV to: {output_path}")
    print("Sample rows:")
    print(df.head(5).to_string(index=False))


if __name__ == "__main__":
    main()

