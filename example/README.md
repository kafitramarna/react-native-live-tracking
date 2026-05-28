# react-native-live-tracking Example

Contoh penggunaan library `react-native-live-tracking` untuk pelacakan lokasi real-time dengan sinkronisasi Firebase.

## Prerequisites

- React Native >= 0.70
- Firebase project yang sudah dikonfigurasi
- Android Studio (untuk Android) atau Xcode (untuk iOS)

## Setup

### 1. Install Library

```bash
# Di project React Native Anda
npm install react-native-live-tracking
# atau
yarn add react-native-live-tracking
```

### 2. Firebase Setup

#### Android

1. Buat project di [Firebase Console](https://console.firebase.google.com/)
2. Download `google-services.json` dan letakkan di `android/app/`
3. Tambahkan Firebase SDK di `android/build.gradle`:

```groovy
// android/build.gradle (project level)
buildscript {
    dependencies {
        classpath 'com.google.gms:google-services:4.4.0'
    }
}
```

```groovy
// android/app/build.gradle
apply plugin: 'com.google.gms.google-services'

dependencies {
    implementation platform('com.google.firebase:firebase-bom:32.7.0')
    implementation 'com.google.firebase:firebase-database'
    // atau untuk Firestore:
    // implementation 'com.google.firebase:firebase-firestore'
}
```

#### iOS

1. Download `GoogleService-Info.plist` dari Firebase Console
2. Tambahkan ke project Xcode (drag ke folder project)
3. Tambahkan Firebase SDK via CocoaPods di `ios/Podfile`:

```ruby
# ios/Podfile
pod 'Firebase/Database'
# atau untuk Firestore:
# pod 'Firebase/Firestore'
```

4. Jalankan `cd ios && pod install`

### 3. Platform Configuration

#### Android - AndroidManifest.xml

Tambahkan permissions dan service berikut di `android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Location Permissions -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Foreground Service Permission (Android 9+) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

    <!-- Boot Receiver Permission -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Activity Recognition (for motion detection) -->
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" />

    <!-- Network State -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application ...>
        <!-- Foreground Service for background tracking -->
        <service
            android:name="com.livetracking.service.TrackingForegroundService"
            android:foregroundServiceType="location"
            android:exported="false" />

        <!-- Boot Receiver for auto-restart after reboot -->
        <receiver
            android:name="com.livetracking.receiver.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

#### iOS - Info.plist

Tambahkan entries berikut di `ios/YourApp/Info.plist`:

```xml
<!-- Location Usage Descriptions (REQUIRED) -->
<key>NSLocationWhenInUseUsageDescription</key>
<string>We need your location to track your position in real-time.</string>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>We need your location in the background to continue tracking your position.</string>

<key>NSLocationAlwaysUsageDescription</key>
<string>We need your location in the background to continue tracking your position.</string>

<!-- Motion Usage (for activity recognition / motion sleep) -->
<key>NSMotionUsageDescription</key>
<string>We use motion data to optimize battery usage when you are stationary.</string>

<!-- Background Modes -->
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
    <string>fetch</string>
</array>
```

Juga aktifkan Background Modes di Xcode:
1. Buka project di Xcode
2. Pilih target app → Signing & Capabilities
3. Tambahkan "Background Modes"
4. Centang: "Location updates" dan "Background fetch"

## Penggunaan

### Basic Flow

```typescript
import LiveTracking from 'react-native-live-tracking';

// 1. Configure
await LiveTracking.configure({
  optimization: {
    intervalMs: 10000,
    distanceFilterMeters: 10,
    stopWhenStill: true,
  },
  androidNotification: {
    title: 'Tracking Active',
    text: 'Your location is being tracked',
  },
  firebase: {
    service: 'RTDB',
    currentLocationPath: '/users/user123/currentLocation',
    historyPath: '/users/user123/locationHistory',
    historyBatchSize: 15,
  },
});

// 2. Listen for updates
const locationSub = LiveTracking.onLocationUpdate((location) => {
  console.log('New location:', location.latitude, location.longitude);
});

const errorSub = LiveTracking.onError((error) => {
  console.error('Tracking error:', error.code, error.message);
});

// 3. Start tracking
await LiveTracking.start();

// 4. Check status anytime
const status = await LiveTracking.getStatus();
console.log('State:', status.state);
console.log('Queued:', status.queuedLocations);

// 5. Stop when done
await LiveTracking.stop();

// 6. Clean up listeners
locationSub.remove();
errorSub.remove();
```

### Current Location Only (tanpa history)

```typescript
await LiveTracking.configure({
  optimization: { intervalMs: 5000, distanceFilterMeters: 5 },
  firebase: {
    service: 'RTDB',
    currentLocationPath: '/drivers/driver456/location',
    // historyPath tidak diset = history tidak disimpan
  },
});
```

### History Only (tanpa current location overwrite)

```typescript
await LiveTracking.configure({
  optimization: { intervalMs: 30000, distanceFilterMeters: 50 },
  firebase: {
    service: 'Firestore',
    // currentLocationPath tidak diset = current location tidak di-overwrite
    historyPath: 'trips/trip789/points',
    historyBatchSize: 20,
  },
});
```

### Firestore Instead of RTDB

```typescript
await LiveTracking.configure({
  optimization: { intervalMs: 10000, distanceFilterMeters: 10 },
  firebase: {
    service: 'Firestore',
    currentLocationPath: 'users/user123/currentLocation',
    historyPath: 'users/user123/locationHistory',
  },
});
```

## Running the Example

### Android

```bash
# Start Metro bundler
npx react-native start

# Run on Android emulator/device
npx react-native run-android
```

> **Note:** Untuk testing lokasi di Android Emulator, gunakan Extended Controls (⋮) → Location untuk mengirim koordinat GPS palsu.

### iOS

```bash
# Install pods
cd ios && pod install && cd ..

# Start Metro bundler
npx react-native start

# Run on iOS simulator/device
npx react-native run-ios
```

> **Note:** Untuk testing lokasi di iOS Simulator, gunakan menu Features → Location → Custom Location untuk mengatur koordinat.

## Troubleshooting

### Android

| Masalah | Solusi |
|---------|--------|
| Foreground service tidak muncul | Pastikan permission `FOREGROUND_SERVICE` dan `FOREGROUND_SERVICE_LOCATION` ada di manifest |
| Lokasi tidak update di background | Pastikan `ACCESS_BACKGROUND_LOCATION` diminta dan diizinkan |
| Firebase write gagal | Periksa `google-services.json` dan Firebase Security Rules |
| Boot receiver tidak bekerja | Pastikan `RECEIVE_BOOT_COMPLETED` permission ada |

### iOS

| Masalah | Solusi |
|---------|--------|
| Lokasi tidak update di background | Pastikan Background Modes "Location updates" aktif di Xcode |
| Permission dialog tidak muncul | Pastikan semua `NSLocation*UsageDescription` ada di Info.plist |
| App terminated, tracking berhenti | Significant location change monitoring akan restart otomatis |
| Firebase write gagal | Periksa `GoogleService-Info.plist` sudah ditambahkan ke Xcode project |

### Umum

| Masalah | Solusi |
|---------|--------|
| `NOT_CONFIGURED` error | Panggil `configure()` sebelum `start()` |
| `INVALID_CONFIG` error | Periksa format konfigurasi, minimal satu path Firebase harus diisi |
| Lokasi tidak akurat | Pastikan GPS aktif dan perangkat di area terbuka |
| Baterai boros | Naikkan `intervalMs` dan `distanceFilterMeters` |

## Firebase Security Rules

### Realtime Database

```json
{
  "rules": {
    "users": {
      "$userId": {
        "currentLocation": {
          ".read": "auth != null && auth.uid == $userId",
          ".write": "auth != null && auth.uid == $userId"
        },
        "locationHistory": {
          ".read": "auth != null && auth.uid == $userId",
          ".write": "auth != null && auth.uid == $userId"
        }
      }
    }
  }
}
```

### Firestore

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```
