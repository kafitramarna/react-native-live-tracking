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
    intervalMs: 10000,         // Update setiap 10 detik minimum
    distanceFilterMeters: 10,  // Hanya update jika bergerak 10+ meter
    stopWhenStill: true,       // Hemat baterai saat diam
  },
  androidNotification: {
    title: "Merekam Perjalanan",
    text: "Lokasi Anda sedang dibagikan.",
  },
  firebase: {
    service: 'RTDB', // atau 'Firestore'
    targets: [
      {
        path: `users/${userId}/current_location`,
        method: 'set',  // overwrite — untuk marker real-time di peta
      },
      {
        path: `users/${userId}/location_history`,
        method: 'push',        // append — untuk rekam jejak
        batchSize: 15,         // kirim tiap 15 titik
        offlineQueue: true,    // simpan offline jika tidak ada koneksi
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

Setiap sync target mendefinisikan **path**, **method**, dan behavior opsional. Kamu bebas menentukan struktur database Firebase sesuka hati.

```typescript
interface SyncTarget {
  /** Firebase path untuk ditulis */
  path: string;
  /** Metode write: 'set' (overwrite), 'push' (append), 'update' (merge) */
  method: 'set' | 'push' | 'update';
  /** Jumlah titik yang diakumulasi sebelum ditulis. Default: 1 (langsung tulis) */
  batchSize?: number;
  /** Apakah data disimpan offline saat tidak ada koneksi. Default: false */
  offlineQueue?: boolean;
}
```

### Write Methods

| Method | Behavior | Use Case |
|--------|----------|----------|
| `set` | Overwrite data di path | Marker real-time di peta |
| `push` | Append dengan auto-generated key | Rekam jejak / history |
| `update` | Merge fields tanpa hapus field lain | Update partial data |

### Contoh: Banyak Target

```typescript
firebase: {
  service: 'Firestore',
  targets: [
    // Real-time marker (overwrite, tanpa queue)
    { path: `drivers/${driverId}/location`, method: 'set' },

    // History perjalanan (batch + offline queue)
    { path: `trips/${tripId}/points`, method: 'push', batchSize: 20, offlineQueue: true },

    // Status update (merge ke dokumen yang sudah ada)
    { path: `drivers/${driverId}/status`, method: 'update' },
  ],
}
```

## Configuration

### Optimization

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `optimization.intervalMs` | number | 10000 | Minimum interval antar update (ms) |
| `optimization.distanceFilterMeters` | number | 10 | Minimum jarak untuk trigger update (meter) |
| `optimization.stopWhenStill` | boolean | true | Aktifkan Motion Sleep Mode |

### Firebase

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `firebase.service` | `'RTDB' \| 'Firestore'` | ✅ | Tipe Firebase service |
| `firebase.targets` | `SyncTarget[]` | ✅ | Array sync targets (min 1, max 20) |

### SyncTarget Options

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `path` | string | — | Firebase path (1–768 karakter) |
| `method` | `'set' \| 'push' \| 'update'` | — | Metode write |
| `batchSize` | number | 1 | Jumlah titik per batch (1–1000) |
| `offlineQueue` | boolean | false | Simpan data offline saat tidak ada koneksi |

### Android Notification

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `androidNotification.title` | string | — | Judul notifikasi foreground service |
| `androidNotification.text` | string | — | Teks notifikasi |
| `androidNotification.icon` | string? | — | Nama resource icon |
| `androidNotification.channelId` | string? | — | Notification channel ID |

## How It Works

### Distance/Time Matrix (AND logic)
Location updates hanya dikirim ketika **KEDUA** kondisi terpenuhi:
- Waktu sejak update terakhir ≥ `intervalMs`
- Jarak dari lokasi terakhir ≥ `distanceFilterMeters`

### Motion Sleep Mode
Ketika `stopWhenStill: true` dan device diam > 3 menit:
- Akurasi GPS diturunkan untuk hemat baterai
- Kembali ke akurasi penuh saat terdeteksi pergerakan

### Per-Target Batch Processing
- Target dengan `batchSize > 1` mengakumulasi titik di memori
- Ditulis ke Firebase dalam satu operasi batch saat penuh
- Partial batch otomatis di-flush saat `stop()` dipanggil atau setelah 30 detik tidak ada update baru

### Per-Target Offline Queue
- Target dengan `offlineQueue: true` menyimpan data ke SQLite (Android) / CoreData (iOS) saat offline
- Otomatis sync saat koneksi kembali (chronological order, oldest first)
- Maksimum 10.000 data point per target (oldest evicted saat penuh)
- Data persist across app restart dan process termination

### Retry with Exponential Backoff
- Setiap target retry secara independen (tidak blocking target lain)
- `set`/`update`: max 3 retry
- `push`: max 5 retry
- Base delay 1000ms, multiplier 2x, jitter ±200ms
- Error non-transient (permission denied) langsung gagal tanpa retry

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
| `configure(config)` | `Promise<void>` | Konfigurasi library |
| `start()` | `Promise<void>` | Mulai tracking |
| `stop()` | `Promise<void>` | Stop tracking (flush semua batch) |
| `getStatus()` | `Promise<TrackingStatus>` | Status tracking saat ini |
| `getQueuedLocations()` | `Promise<number>` | Total offline queue count |
| `getQueuedLocationsByTarget()` | `Promise<Record<string, number>>` | Queue count per target path |
| `onLocationUpdate(cb)` | `Subscription` | Listen location updates |
| `onError(cb)` | `Subscription` | Listen errors |

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
