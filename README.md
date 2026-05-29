# @kafitra/react-native-live-tracking

Real-time location tracking library for React Native with Firebase synchronization. Supports background tracking, offline caching, battery optimization, and fully flexible sync targets.

## Features

- **Background Location Tracking** — Android Foreground Service & iOS Background Modes
- **Flexible Firebase Sync** — Define unlimited sync targets with custom paths, write methods, and behavior
- **Offline-First** — Per-target offline queue with automatic sync when connection restores
- **Battery Optimization** — Distance/Time matrix filter + Motion Sleep Mode
- **Auto-Restart** — Resume tracking after device reboot (Android BOOT_COMPLETED)
- **Cross-Platform** — iOS 13+ & Android API 21+
- **New Architecture Ready** — Supports TurboModules + legacy Bridge

## Installation

```bash
npm install @kafitra/react-native-live-tracking
# or
yarn add @kafitra/react-native-live-tracking
```

### iOS
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
| `androidNotification.title` | string | — | Foreground service notification title |
| `androidNotification.text` | string | — | Notification text |
| `androidNotification.icon` | string? | — | Icon resource name |
| `androidNotification.channelId` | string? | — | Notification channel ID |

## How It Works

### Distance/Time Matrix (AND logic)
Location updates are only sent when **BOTH** conditions are met:
- Time since last update ≥ `intervalMs`
- Distance from last location ≥ `distanceFilterMeters`

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

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### iOS

Add to `Info.plist`:
```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Your location usage description</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Your background location usage description</string>
<key>NSMotionUsageDescription</key>
<string>Used to optimize battery when stationary</string>
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

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
| `INVALID_CONFIG` | Invalid configuration parameters |
| `NOT_CONFIGURED` | Method called before `configure()` |
| `FIREBASE_WRITE_FAILED` | Firebase write failed after max retries |
| `DEPRECATED_FIELD` | Using old config fields (currentLocationPath/historyPath) |
| `QUEUE_OVERFLOW` | Offline queue reached 10,000 cap (oldest evicted) |

## License

MIT
