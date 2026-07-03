# @kafitra/react-native-live-tracking

Real-time location tracking library for React Native with Firebase synchronization. Supports background tracking, offline caching, battery optimization, and fully flexible sync targets.

## Features

- **Background Location Tracking** — Android Foreground Service & iOS Background Modes
- **Flexible Firebase Sync** — Define unlimited sync targets with custom paths, write methods, and behavior
- **Offline-First** — Per-target offline queue with automatic sync when connection restores
- **Battery Optimization** — Distance/Time matrix filter + Motion Sleep Mode
- **Auto-Restart** — Resume tracking after device reboot (Android BOOT_COMPLETED)
- **Cross-Platform** — iOS 13+ & Android API 21+
- **New Architecture Ready** — Works with both TurboModules (New Arch) and legacy Bridge (Old Arch) via interop

## Installation

```bash
npm install @kafitra/react-native-live-tracking
# or
yarn add @kafitra/react-native-live-tracking
```

### iOS

#### 1. Podfile Setup

This library depends on `FirebaseDatabase` and `FirebaseFirestore`. If you use `react-native-firebase`, add the required modular headers and permissions in your `Podfile`:

```ruby
$RNFirebaseAsStaticFramework = true

require Pod::Executable.execute_command('node', ['-p',
  'require.resolve(
    "react-native/scripts/react_native_pods.rb",
    {paths: [process.argv[1]]},
  )', __dir__]).strip

require_relative '../node_modules/react-native-permissions/scripts/setup'

platform :ios, min_ios_version_supported
prepare_react_native_project!

target 'YourApp' do
  # Firebase modular headers (required for Swift static library integration)
  pod 'GoogleUtilities', :modular_headers => true
  pod 'FirebaseCore', :modular_headers => true
  pod 'FirebaseCoreExtension', :modular_headers => true
  pod 'FirebaseCoreInternal', :modular_headers => true
  pod 'FirebaseAppCheckInterop', :modular_headers => true
  pod 'FirebaseDatabase', :modular_headers => true
  pod 'FirebaseFirestore', :modular_headers => true
  pod 'FirebaseFirestoreInternal', :modular_headers => true
  pod 'leveldb-library', :modular_headers => true

  # Permissions required by live-tracking
  setup_permissions([
    'LocationAlways',
    'LocationWhenInUse',
    'Motion',
  ])

  config = use_native_modules!
  use_react_native!(:path => config[:reactNativePath])
end
```

#### 2. Install Pods

```bash
cd ios && pod install
```

## Quick Start

```typescript
import LiveTracking from '@kafitra/react-native-live-tracking';

// 1. Configure
await LiveTracking.configure({
  optimization: {
    intervalMs: 10000,         // Minimum 10 seconds between updates
    distanceFilterMeters: 10,  // Only update if moved 10+ meters
    stopWhenStill: true,       // Save battery when stationary
  },
  androidNotification: {
    title: "Recording Trip",
    text: "Your location is being shared.",
  },
  firebase: {
    service: 'RTDB', // or 'Firestore'
    targets: [
      {
        path: `users/${userId}/current_location`,
        method: 'set',  // overwrite — for real-time map marker
      },
      {
        path: `users/${userId}/location_history`,
        method: 'push',        // append — for track history
        batchSize: 15,         // send every 15 points
        offlineQueue: true,    // persist offline if no connection
      },
    ],
  },
});

// 2. Listen for updates
const subscription = LiveTracking.onLocationUpdate((location) => {
  console.log(location.latitude, location.longitude);
});

// 3. Start tracking
await LiveTracking.start();

// 4. Stop when done
await LiveTracking.stop();
subscription.remove();
```

## Usage with React Native (TrackingProvider Pattern)

A common pattern is to wrap tracking in a provider that reacts to backend status changes (e.g., via Firebase RTDB):

```typescript
import React, { useEffect } from 'react';
import LiveTracking from '@kafitra/react-native-live-tracking';
import database from '@react-native-firebase/database';

const TRACKING_STATUS = { READY: 0, ON_PROGRESS: 1, COMPLETED: 2 } as const;

async function startLiveTracking(deliveryNoteId: string | number) {
  await LiveTracking.configure({
    firebase: {
      service: 'RTDB',
      targets: [
        { path: `delivery/${deliveryNoteId}/current`, method: 'set' },
        { path: `delivery/${deliveryNoteId}/history`, method: 'push', batchSize: 10, offlineQueue: true },
      ],
    },
    optimization: { intervalMs: 10000, distanceFilterMeters: 10, stopWhenStill: true },
    androidNotification: {
      title: 'Delivery In Progress',
      text: 'Tracking your location...',
    },
  });
  await LiveTracking.start();
}

export function TrackingProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    const statusRef = database().ref(`delivery/188/status`);
    const onValue = statusRef.on('value', (snapshot) => {
      const status = snapshot.val();
      if (status === TRACKING_STATUS.ON_PROGRESS) {
        startLiveTracking(188).catch(console.warn);
      } else if (status === TRACKING_STATUS.COMPLETED) {
        LiveTracking.stop().catch(console.warn);
      }
    });
    return () => statusRef.off('value', onValue);
  }, []);

  return <>{children}</>;
}
```

## Sync Targets

Each sync target defines a **path**, **method**, and optional behavior. You have full control over your Firebase database structure.

```typescript
interface SyncTarget {
  /** Firebase path to write to */
  path: string;
  /** Write method: 'set' (overwrite), 'push' (append), 'update' (merge) */
  method: 'set' | 'push' | 'update';
  /** Number of points to accumulate before writing. Default: 1 (immediate) */
  batchSize?: number;
  /** Whether to persist data offline when there is no connection. Default: false */
  offlineQueue?: boolean;
}
```

### Write Methods

| Method | Behavior | Use Case |
|--------|----------|----------|
| `set` | Overwrite data at path | Real-time map marker |
| `push` | Append with auto-generated key | Track history |
| `update` | Merge fields without removing existing ones | Partial data update |

### Example: Multiple Targets

```typescript
firebase: {
  service: 'Firestore',
  targets: [
    // Real-time marker (overwrite, no queue)
    { path: `drivers/${driverId}/location`, method: 'set' },

    // Trip history (batch + offline queue)
    { path: `trips/${tripId}/points`, method: 'push', batchSize: 20, offlineQueue: true },

    // Status update (merge into existing document)
    { path: `drivers/${driverId}/status`, method: 'update' },
  ],
}
```

## Configuration

### Optimization

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `optimization.intervalMs` | number | 10000 | Minimum interval between updates (ms) |
| `optimization.distanceFilterMeters` | number | 10 | Minimum distance to trigger update (meters) |
| `optimization.stopWhenStill` | boolean | true | Enable Motion Sleep Mode |
| `optimization.mode` | `'interval' \| 'distance' \| 'both'` | `'both'` | Filter strategy: time only, distance only, or both |

### Firebase

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `firebase.service` | `'RTDB' \| 'Firestore'` | ✅ | Firebase service type |
| `firebase.targets` | `SyncTarget[]` | ✅ | Array of sync targets (min 1, max 20) |

### SyncTarget Options

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | string | — | Firebase path (1–768 characters) |
| `method` | `'set' \| 'push' \| 'update'` | — | Write method |
| `batchSize` | number | 1 | Points per batch (1–1000) |
| `offlineQueue` | boolean | false | Persist data offline when no connection |

### Android Notification

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `androidNotification.enabled` | boolean | `true` | Show the foreground service notification |
| `androidNotification.title` | string | — | Foreground service notification title (required when enabled) |
| `androidNotification.text` | string | — | Notification text (required when enabled) |
| `androidNotification.icon` | string? | — | Icon resource name |
| `androidNotification.channelId` | string? | — | Notification channel ID |
| `androidNotification.channelName` | string? | — | Notification channel name |

> **Note:** Android requires a foreground service notification to run background tracking. When `enabled: false`, the service still runs but shows a minimal default notification.

### iOS Notification

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `iosNotification.enabled` | boolean | `true` | Show the persistent local notification |
| `iosNotification.title` | string | — | Notification title (required when enabled) |
| `iosNotification.text` | string | — | Notification body (required when enabled) |

## How It Works

### Distance/Time Matrix
The filtering strategy is controlled by `optimization.mode`:

| Mode | Behavior |
|------|----------|
| `both` (default) | Update only when **both** time and distance conditions are met |
| `interval` | Update only when time since last update ≥ `intervalMs` |
| `distance` | Update only when distance from last location ≥ `distanceFilterMeters` |

### GPS Disabled During Tracking
The library monitors GPS/location service status. If GPS is disabled while tracking is active:
- Tracking is automatically paused
- An `onError` event with code `GPS_DISABLED` is emitted
- When GPS is re-enabled, tracking automatically resumes
- A `GPS_ENABLED` event is emitted on resume

### Motion Sleep Mode
When `stopWhenStill: true` and device is stationary for > 3 minutes:
- GPS accuracy is reduced to save battery
- Resumes full accuracy when movement is detected

### Per-Target Batch Processing
- Targets with `batchSize > 1` accumulate points in memory
- Written to Firebase in a single batch operation when full
- Partial batches are automatically flushed on `stop()` or after 30 seconds of inactivity

### Per-Target Offline Queue
- Targets with `offlineQueue: true` persist data to SQLite (Android) / CoreData (iOS) when offline
- Automatically synced when connection restores (chronological order, oldest first)
- Maximum 10,000 data points per target (oldest evicted when full)
- Data persists across app restart and process termination

### Retry with Exponential Backoff
- Each target retries independently (does not block other targets)
- `set`/`update`: max 3 retries
- `push`: max 5 retries
- Base delay 1000ms, multiplier 2x, jitter ±200ms
- Non-transient errors (permission denied) fail immediately without retry

## Platform Setup

### Android

#### Permissions

Add to `AndroidManifest.xml`:
```xml
<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Foreground Service (required for background tracking notification) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<!-- Optional: Notification permission (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Optional: Auto-restart tracking after device reboot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Optional: Motion Sleep Mode (reduce battery when stationary) -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />

<!-- Network state detection for offline queue sync -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> **Note:** You must request runtime permissions (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`) before calling `LiveTracking.start()`. The library will reject with `PERMISSION_DENIED` if required permissions are not granted.

#### Firebase Setup

Ensure your app has `google-services.json` in `android/app/` and the Google Services plugin applied in your `android/app/build.gradle`:
```groovy
apply plugin: 'com.google.gms.google-services'
```

### iOS

#### Info.plist

Add the following keys to your `Info.plist`:
```xml
<!-- Required: Location permission descriptions -->
<key>NSLocationWhenInUseUsageDescription</key>
<string>App needs your location to track deliveries in real-time.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>App needs background location access to track deliveries while the app is in the background.</string>

<!-- Optional: Motion permission for stopWhenStill battery optimization -->
<key>NSMotionUsageDescription</key>
<string>Used to optimize battery usage when stationary.</string>

<!-- Required: Enable background location updates -->
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

#### Firebase Setup

Ensure your app has `GoogleService-Info.plist` added to the Xcode project.

## API Reference

| Method | Returns | Description |
|--------|---------|-------------|
| `configure(config)` | `Promise<void>` | Configure the library |
| `start()` | `Promise<void>` | Start tracking |
| `stop()` | `Promise<void>` | Stop tracking (flushes all batches) |
| `getStatus()` | `Promise<TrackingStatus>` | Get current tracking status |
| `getQueuedLocations()` | `Promise<number>` | Get total offline queue count |
| `getQueuedLocationsByTarget()` | `Promise<Record<string, number>>` | Get queue count per target path |
| `onLocationUpdate(cb)` | `Subscription` | Listen for location updates |
| `onError(cb)` | `Subscription` | Listen for errors |

## Error Codes

| Code | Description |
|------|-------------|
| `PERMISSION_DENIED` | Location permission not granted |
| `GPS_DISABLED` | GPS/Location services turned off |
| `GPS_ENABLED` | GPS/Location services turned back on (auto-resume) |
| `PERMISSION_REVOKED` | Permission revoked while tracking was paused |
| `INVALID_CONFIG` | Invalid configuration parameters |
| `NOT_CONFIGURED` | Method called before `configure()` |
| `FIREBASE_WRITE_FAILED` | Firebase write failed after max retries |
| `DEPRECATED_FIELD` | Using old config fields (currentLocationPath/historyPath) |
| `QUEUE_OVERFLOW` | Offline queue reached 10,000 cap (oldest evicted) |
| `LOCATION_UNKNOWN` | Location temporarily unavailable |
| `NETWORK_ERROR` | Location network error |
| `LOCATION_ERROR` | Generic location update failure |

## License

MIT
