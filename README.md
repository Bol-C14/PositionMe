# 📍 PositionMe

**Indoor & Outdoor Positioning System**

PositionMe is a mobile Android application for **real-time indoor/outdoor positioning**. It fuses **WiFi**, **GNSS**, and **IMU (PDR)** data to provide accurate, robust user localization within university buildings like **The Nucleus** and **The Noreen and Kenneth Murray Library**.


## 🧠 Core Features

- 🔄 **Real-Time Sensor Fusion:** Combines PDR, GNSS, and WiFi using Kalman and Particle filters.
- 🧭 **Interactive Map Interface:** Live visualization of position, tagging, and sensor info.
- 📱 **Sensor Dashboard**: View all IMU, GNSS, and WiFi specs.
- 🧪 **Manual Tagging System:** Add calibrated anchor points for ML training.
- 📼 **Replay Mode:** Revisit recorded sessions with map visualization.
- 🧠 **AI-based WiFi Prediction:** Predicts location using a TFLite ML model (offline-trained).
- 🧰 **Developer-Friendly Architecture:** Modular components, extensible map utilities, and offline training pipelines.

## 📦 APK Installation (For End-Users)

1. Install APK on Android (Android 9+ recommended).
2. Enable location, Wi-Fi, and sensor access.
3. Use bottom nav bar to switch between Explore, Replay, Device, Settings.

## 🚀 Getting Started (Developers)

### ✅ Prerequisites

| Tool             | Version               |
|------------------|------------------------|
| Android Studio   | Arctic Fox (2020.3.1) or newer |
| Android SDK      | API Level 34           |
| Gradle           | 8.0+                   |
| Java JDK         | 17+                    |
| Git              | Latest stable          |

**Device Requirements:**
- Physical Android device with GNSS, WiFi, and IMU sensors.
- Developer mode enabled.
- Internet connection for OpenPositioning API.

### 🛠️ Setup Instructions

1. **Clone Repository**
   ```bash
   git clone https://github.com/Bol-C14/PositionMe_EWireless
   cd PositionMe
   ```

2. **Open Project**
   - Launch Android Studio → "Open existing project"
   - Select project root → wait for Gradle sync

3. **API Key Setup**
   Create `secrets.properties` in project root:
   ```
   MAPS_API_KEY=your_google_maps_key
   OPENPOSITIONING_API_KEY=your_openpositioning_key
   OPENPOSITIONING_MASTER_KEY=your_openpositioning_master_key
   ```
   > ⚠️ Add `secrets.properties` to `.gitignore` to avoid leaking keys.

4. **Build and Run**
   - Connect Android device via USB
   - Enable USB Debugging
   - Click `Run > Run 'app'` in Android Studio
   - Grant permissions when prompted

---

### 📁 Project Structure

```
com.openpositioning.PositionMe/
├── data/                # File handling & API comms
├── domain/              # Core algorithm logic
├── presentation/        # UI components
├── sensors/             # Sensor reading & fusion
├── utils/               # Utilities
└── Traj.java            # Trajectory data structure
```

## ⚙️ Core Functionalities

### 🔴 Real-Time Tracking (Explore)
- Live fused positioning using GNSS, WiFi, and IMU
- Toggle modes: GNSS / PDR / WiFi / Fused
- Tagging support for indoor calibration
- Fragment: `RecordingFragment.java`

### 🧭 Calibration
- Manually anchor ground truth positions
- Saved for ML training or evaluation
- Fragment: `CalibrationFragment.java`

### 📊 Sensor Fusion
Implemented in `SensorFusion.java` using:
- `EKF.java` – Extended Kalman Filter
- `ParticleFilter.java` – Sampling-based estimator
- Fallback ML model: `SensorDataPredictor.java` (TensorFlow Lite)

### 🔁 Replay
- Visualize past paths with interactive playback
- Reposition start point manually
- Fragment: `ReplayFragment.java`

## 🧠 Fusion & AI Modules

### 1. `SensorFusion.java`
- Central controller for EKF, PF, and fallback models.

### 2. `EKF.java`
- Tracks state `[x, y, z, θ]`.
- Updates from PDR, GNSS, WiFi, and optional barometer.

### 3. `ParticleFilter.java`
- Monte Carlo sampling-based backup.
- Used to control drift when EKF fails.

### 4. `SensorDataPredictor.java`
- TFLite fallback ML model using top-20 BSSID+RSSI fingerprints.


## 🗺️ Map Visualization Modules

| File                     | Purpose                                  |
|--------------------------|------------------------------------------|
| `TrajectoryPlotter.java` | Draw real-time paths (raw, fusion, etc.) |
| `TrajectoryMapWall.java` | Render wall outlines                     |
| `BuildingPolygonPlotter` | Draw building boundaries                 |
| `TrajectoryMapMaker`     | Add markers (e.g., toilet, lifts)        |

> Tip: Add new building or floor support by editing coordinate lists.

## 🧪 Machine Learning Pipeline (Offline)

All scripts located in:
```
/projectSourceFolder/machine_learning/
```

### 🧭 Alignment – `alignment.py`
- Uses **piecewise Procrustes alignment** between anchor points
- Corrects PDR drift using user-tagged positions

### 🤖 Training – `embedding_train_LSTM.py`
1. Parse `.json` logs (recorded via app)
2. Align PDR segments
3. Extract WiFi top-20 BSSID-RSSI features
4. Use aligned positions as labels
5. Train and visualize prediction performance

## 📱 User Interface Guide

### 🌍 Explore Tab
- Switch between GNSS / WiFi / PDR / Fused modes
- Live sensor panel (accelerometer, gyroscope, etc.)
- Tagging panel for collecting ground truth
- Save and upload trajectories automatically

### 🔁 Replay Tab
- Download and view previous routes
- Manual replay with adjustable start points

### 📟 Device Tab
- List all sensors (type, vendor, resolution, power)

### ⚙️ Settings Tab
- Manual stride length
- Floor height setting
- Data sync over WiFi/mobile
- Edit advanced constants

## Trajectory Files

Trajectory recordings are stored as JSON files named `trajectory_<timestamp>.txt`.
The timestamp follows `dd-MM-yy-HH-mm-ss`. Files are written and read via the
`TrajectoryIO` helper which converts between the protobuf `Traj.Trajectory`
and its JSON representation.

## 🧩 Common Issues & Fixes

| Issue                          | Fix                                                        |
|-------------------------------|-------------------------------------------------------------|
| App crashes on launch         | Check permissions, enable location services                |
| API keys not working          | Confirm `secrets.properties` setup                         |
| GNSS/WiFi data missing        | Use a physical device and allow all permissions            |
| Old WiFi data                 | Disable WiFi scan throttling in Developer Options          |
