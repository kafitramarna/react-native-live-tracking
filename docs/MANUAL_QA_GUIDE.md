# 📋 Manual QA & Edge Case Testing Guide

## react-native-live-tracking — Physical Device Testing

> **Penting:** Panduan ini HARUS dijalankan di perangkat fisik (bukan emulator/simulator) untuk hasil yang akurat. GPS, background process, dan battery profiling tidak dapat diuji secara reliable di emulator.

---

## 📱 Perangkat yang Dibutuhkan

| Platform | Minimum | Rekomendasi |
|----------|---------|-------------|
| Android | API 21 (Android 5.0) | API 29+ (Android 10+) untuk test background location permission |
| iOS | iOS 13.0 | iOS 15+ untuk test terbaru |

**Tools yang dibutuhkan:**
- Android: ADB, Android Studio Logcat, Battery Historian
- iOS: Xcode Instruments, Console.app
- Firebase Console (untuk verifikasi data)
- Stopwatch / timer

---

## 🧪 Test Scenario 1: Airplane Mode (Offline Caching & Batch Upload Recovery)

### Tujuan
Memastikan tidak ada data lokasi yang hilang saat perangkat offline, dan semua data terkirim saat koneksi pulih.

### Pre-conditions
- App sudah ter-install dan configured
- Firebase RTDB/Firestore sudah aktif
- `historyPath` dan `currentLocationPath` keduanya dikonfigurasi
- `historyBatchSize: 5` (set rendah untuk mempercepat testing)

### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Buka app, panggil `configure()` lalu `start()` | Tracking aktif, notifikasi muncul (Android) | |
| 2 | Berjalan selama 1-2 menit, pastikan lokasi terupdate di Firebase | Data muncul di Firebase Console | |
| 3 | Catat jumlah entry di Firebase history path | Baseline count: ___ | |
| 4 | **Aktifkan Airplane Mode** | Tidak ada crash, app tetap berjalan | |
| 5 | Berjalan selama 3-5 menit (pastikan bergerak > distanceFilter) | App tetap tracking (cek via `onLocationUpdate` callback) | |
| 6 | Panggil `getQueuedLocations()` | Harus return angka > 0 (lokasi ter-queue) | |
| 7 | Panggil `getStatus()` | `isOnline: false`, `queuedLocations > 0` | |
| 8 | **Matikan Airplane Mode** (koneksi pulih) | Tunggu 5-10 detik | |
| 9 | Panggil `getQueuedLocations()` setelah 10 detik | Harus berkurang atau 0 (batch terkirim) | |
| 10 | Cek Firebase Console — history path | Entry baru muncul sesuai jumlah yang di-queue | |
| 11 | Cek Firebase Console — current location path | Lokasi terbaru ter-update | |

### Kriteria PASS
- ✅ Tidak ada lokasi yang hilang (jumlah entry Firebase = jumlah update yang diterima)
- ✅ Batch dikirim otomatis saat koneksi pulih
- ✅ App tidak crash saat toggle airplane mode
- ✅ `currentLocationPath` ter-update dengan lokasi terbaru setelah online

### Kriteria FAIL
- ❌ Ada gap di data history (lokasi hilang)
- ❌ Queue tidak ter-flush setelah koneksi pulih (> 30 detik)
- ❌ App crash saat airplane mode on/off
- ❌ Firebase write error tidak di-retry

---

## 🧪 Test Scenario 2: OS Background Kills

### 2A. Android — Doze Mode & App Standby

#### Pre-conditions
- Android 6.0+ (API 23+)
- App tracking aktif dengan foreground notification visible

#### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Start tracking, pastikan notifikasi muncul | Foreground service aktif | |
| 2 | Tekan Home, biarkan app di background | Notifikasi tetap ada | |
| 3 | Biarkan perangkat idle selama 15 menit (layar mati) | — | |
| 4 | Simulasi Doze: `adb shell dumpsys deviceidle force-idle` | Perangkat masuk Doze mode | |
| 5 | Tunggu 5 menit, lalu: `adb shell dumpsys deviceidle unforce` | Keluar dari Doze | |
| 6 | Buka app, cek `getStatus()` | `state: "tracking"`, lokasi masih terupdate | |
| 7 | Cek Firebase — apakah ada data selama Doze? | Mungkin ada delay, tapi data harus ada setelah Doze selesai | |
| 8 | Force stop app: Settings > Apps > Force Stop | App berhenti | |
| 9 | Buka app kembali, cek queue | Queue harus masih ada (Room DB persisten) | |

#### Verifikasi Foreground Service Persistence
```bash
# Cek apakah service masih berjalan
adb shell dumpsys activity services com.livetracking

# Cek foreground service notification
adb shell dumpsys notification | grep "live_tracking"
```

### 2B. iOS — Background App Refresh & App Termination

#### Pre-conditions
- iOS 13+
- Background Modes enabled (Location updates)
- "Always" location permission granted

#### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Start tracking | Location indicator muncul di status bar | |
| 2 | Tekan Home, biarkan app di background | Location indicator tetap ada (arrow icon) | |
| 3 | Buka app lain yang memory-intensive | Simulasi memory pressure | |
| 4 | Tunggu 10 menit | — | |
| 5 | Buka app kembali | App harus masih tracking atau restart via significant location | |
| 6 | Swipe-kill app dari app switcher | App terminated | |
| 7 | Berjalan 500+ meter (trigger significant location change) | App harus relaunch di background | |
| 8 | Buka app | Tracking harus aktif kembali (atau data dari significant location tersimpan) | |

#### Verifikasi via Console.app
```
# Filter log untuk library
subsystem:com.livetracking
```

### Kriteria PASS
- ✅ Android: Foreground service tetap hidup selama Doze
- ✅ Android: Data di Room DB persisten setelah force stop
- ✅ iOS: Background location tetap aktif selama di background
- ✅ iOS: Significant location change me-relaunch app setelah termination

### Kriteria FAIL
- ❌ Service mati tanpa notifikasi ke user
- ❌ Data hilang setelah background kill
- ❌ App tidak bisa resume tracking setelah relaunch

---

## 🧪 Test Scenario 3: Permission Denied & GPS Turned Off

### 3A. Permission Denied

#### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Fresh install app (belum ada permission) | — | |
| 2 | Panggil `configure()` lalu `start()` | Error callback dengan code `PERMISSION_DENIED` | |
| 3 | Verifikasi app TIDAK crash | App tetap responsive | |
| 4 | Grant "While Using" permission saja | — | |
| 5 | Panggil `start()` lagi | Tracking mulai (foreground) | |
| 6 | Revoke permission via Settings saat tracking aktif | Error callback `PERMISSION_DENIED`, tracking berhenti gracefully | |
| 7 | **Android only:** Deny "Allow all the time" | `start()` returns error `PERMISSION_DENIED` dengan pesan tentang background | |

### 3B. GPS Turned Off

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Matikan GPS/Location Services di Settings | — | |
| 2 | Panggil `start()` | Error callback dengan code `GPS_DISABLED` | |
| 3 | Verifikasi app TIDAK crash | App tetap responsive | |
| 4 | Nyalakan GPS kembali | — | |
| 5 | Panggil `start()` lagi | Tracking mulai normal | |
| 6 | Matikan GPS saat tracking aktif | Error callback `GPS_DISABLED` | |

### Kriteria PASS
- ✅ Error code yang jelas dan deskriptif
- ✅ App TIDAK crash dalam kondisi apapun
- ✅ Recovery possible setelah permission granted / GPS enabled
- ✅ Error message cukup informatif untuk developer

### Kriteria FAIL
- ❌ App crash saat permission denied
- ❌ Silent failure (tidak ada error callback)
- ❌ Infinite loop retry tanpa informasi ke user
- ❌ Error code tidak sesuai dokumentasi

---

## 🧪 Test Scenario 4: Device Reboot (Auto-restart via BOOT_COMPLETED)

> **Platform:** Android only (iOS uses significant location change for relaunch)

### Pre-conditions
- Android device
- App sudah configured dan tracking aktif
- `RECEIVE_BOOT_COMPLETED` permission granted

### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Start tracking, pastikan notifikasi muncul | Tracking aktif | |
| 2 | Verifikasi SharedPreferences: `is_tracking_active = true` | State tersimpan | |
| 3 | **Restart perangkat** (power off → power on) | — | |
| 4 | Tunggu sampai boot selesai (home screen muncul) | — | |
| 5 | Cek apakah notifikasi foreground service muncul | Notifikasi harus muncul otomatis | |
| 6 | Buka app, panggil `getStatus()` | `state: "tracking"` | |
| 7 | Verifikasi lokasi terupdate di Firebase | Data baru muncul setelah reboot | |
| 8 | Panggil `stop()` sebelum reboot | — | |
| 9 | Restart perangkat lagi | — | |
| 10 | Cek setelah boot | Tracking TIDAK auto-start (karena sudah di-stop) | |

#### Verifikasi via ADB
```bash
# Cek apakah BootReceiver terdaftar
adb shell dumpsys package com.yourapp | grep -A 5 "BootReceiver"

# Cek SharedPreferences
adb shell run-as com.yourapp cat /data/data/com.yourapp/shared_prefs/live_tracking_prefs.xml

# Monitor boot broadcast
adb logcat -s "BootReceiver"
```

### Kriteria PASS
- ✅ Tracking auto-restart setelah reboot (jika sebelumnya aktif)
- ✅ Tracking TIDAK auto-start jika sebelumnya di-stop
- ✅ Foreground notification muncul setelah auto-restart
- ✅ Firebase sync resume setelah reboot

### Kriteria FAIL
- ❌ Tracking tidak restart setelah reboot
- ❌ Tracking restart padahal sebelumnya sudah di-stop
- ❌ Crash saat boot (BootReceiver error)
- ❌ SharedPreferences state tidak konsisten

---

## 🧪 Test Scenario 5: Battery Profiling (< 5% per hour KPI)

### Tujuan
Memastikan library mengkonsumsi baterai kurang dari 5% per jam dalam kondisi tracking aktif.

### Pre-conditions
- Perangkat fully charged (100%) atau minimal 80%
- Semua app lain ditutup
- WiFi OFF, mobile data ON (kondisi real-world)
- Screen brightness 50%
- `optimization.intervalMs: 10000` (10 detik)
- `optimization.distanceFilterMeters: 10` (10 meter)
- `optimization.stopWhenStill: true`

### Test A: Active Movement (Walking/Driving)

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Catat battery level awal: ___% | — | |
| 2 | Start tracking | Tracking aktif | |
| 3 | Berjalan/berkendara selama **1 jam** | Lokasi terupdate secara berkala | |
| 4 | Catat battery level akhir: ___% | — | |
| 5 | Hitung: `battery_awal - battery_akhir` | Harus < 5% | |

### Test B: Stationary (Motion Sleep Mode)

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Catat battery level awal: ___% | — | |
| 2 | Start tracking | Tracking aktif | |
| 3 | Letakkan perangkat diam selama **1 jam** | Motion Sleep Mode harus aktif setelah 3 menit | |
| 4 | Panggil `getStatus()` setelah 5 menit | `batteryOptimization: "low_power"` | |
| 5 | Catat battery level akhir: ___% | — | |
| 6 | Hitung: `battery_awal - battery_akhir` | Harus < 2% (karena sleep mode) | |

### Test C: Baseline (Tanpa Library)

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Catat battery level awal: ___% | — | |
| 2 | JANGAN start tracking | App idle | |
| 3 | Biarkan perangkat idle selama **1 jam** | — | |
| 4 | Catat battery level akhir: ___% | — | |
| 5 | Hitung baseline drain: ___% | Referensi untuk perbandingan | |

### Profiling Tools

#### Android — Battery Historian
```bash
# Reset battery stats
adb shell dumpsys batterystats --reset

# Jalankan test selama 1 jam...

# Dump battery stats
adb bugreport > bugreport.zip

# Upload ke Battery Historian (https://bathist.ef.lc/)
# Atau jalankan lokal: docker run -p 9999:9999 gcr.io/android-battery-historian/stable:3.1 --port 9999
```

#### Android — ADB Battery Stats
```bash
# Cek battery usage per app
adb shell dumpsys batterystats --charged com.yourapp

# Cek wakelock usage
adb shell dumpsys power | grep -i "wake"

# Monitor GPS usage
adb shell dumpsys location | grep -A 10 "com.yourapp"
```

#### iOS — Xcode Instruments
1. Buka Xcode → Product → Profile (Cmd+I)
2. Pilih template **Energy Log**
3. Jalankan selama 1 jam
4. Analisis:
   - GPS Usage (High/Low/Off)
   - CPU Usage
   - Network Activity
   - Overhead Energy

#### iOS — Settings Battery Usage
1. Settings → Battery
2. Scroll ke "Battery Usage by App"
3. Cek persentase untuk app yang ditest
4. Tap untuk melihat "Screen On" vs "Background" usage

### Kriteria PASS
- ✅ Active movement: < 5% battery drain per jam
- ✅ Stationary (sleep mode): < 2% battery drain per jam
- ✅ Motion Sleep Mode aktif setelah 3 menit diam
- ✅ GPS kembali ke high accuracy saat bergerak

### Kriteria FAIL
- ❌ Battery drain > 5% per jam saat active movement
- ❌ Battery drain > 3% per jam saat stationary
- ❌ Motion Sleep Mode tidak aktif (GPS tetap high accuracy saat diam)
- ❌ Wakelock tidak dilepas saat sleep mode

---

## 🧪 Test Scenario 6: Distance/Time Matrix Filter Verification

### Tujuan
Memastikan lokasi hanya diupdate jika KEDUA kondisi terpenuhi (AND logic).

### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Configure dengan `intervalMs: 30000` (30 detik), `distanceFilterMeters: 50` (50 meter) | — | |
| 2 | Start tracking | — | |
| 3 | Diam di tempat selama 1 menit | TIDAK ada update (jarak < 50m) | |
| 4 | Bergerak 10 meter dalam 5 detik | TIDAK ada update (waktu < 30 detik) | |
| 5 | Bergerak 60 meter dan tunggu 35 detik | Update HARUS terjadi (kedua kondisi terpenuhi) | |
| 6 | Hitung jumlah `onLocationUpdate` callbacks | Harus sesuai dengan kondisi AND | |

### Kriteria PASS
- ✅ Tidak ada update saat hanya 1 kondisi terpenuhi
- ✅ Update terjadi saat KEDUA kondisi terpenuhi
- ✅ First location selalu diterima (tanpa filter)

---

## 🧪 Test Scenario 7: Dual-Path Configuration

### Tujuan
Memastikan `currentLocationPath` dan `historyPath` bekerja independen.

### Steps

| # | Action | Expected Result | ✅/❌ |
|---|--------|-----------------|-------|
| 1 | Configure dengan HANYA `currentLocationPath` | — | |
| 2 | Start, bergerak | Firebase current path terupdate, history path KOSONG | |
| 3 | Stop, reconfigure dengan HANYA `historyPath` | — | |
| 4 | Start, bergerak | Firebase history path terisi, current path TIDAK terupdate | |
| 5 | Stop, reconfigure dengan KEDUA path | — | |
| 6 | Start, bergerak | KEDUA path terupdate | |

---

## 📊 Test Report Template

```
=== MANUAL QA REPORT ===
Date: ___________
Tester: ___________
Device: ___________ (Model, OS Version)
Library Version: ___________
Firebase Project: ___________

SCENARIO 1 - Airplane Mode:     [PASS/FAIL] Notes: ___
SCENARIO 2A - Android Doze:     [PASS/FAIL] Notes: ___
SCENARIO 2B - iOS Background:   [PASS/FAIL] Notes: ___
SCENARIO 3A - Permission Denied:[PASS/FAIL] Notes: ___
SCENARIO 3B - GPS Off:          [PASS/FAIL] Notes: ___
SCENARIO 4 - Device Reboot:     [PASS/FAIL] Notes: ___
SCENARIO 5A - Battery Active:   [PASS/FAIL] Drain: ___%/hr
SCENARIO 5B - Battery Sleep:    [PASS/FAIL] Drain: ___%/hr
SCENARIO 6 - Distance/Time:     [PASS/FAIL] Notes: ___
SCENARIO 7 - Dual-Path:         [PASS/FAIL] Notes: ___

Overall: [PASS/FAIL]
Blocking Issues: ___
```

---

## 🐛 Known Edge Cases & Workarounds

| Edge Case | Platform | Behavior | Workaround |
|-----------|----------|----------|------------|
| Doze mode delays location | Android 6+ | Updates delayed until maintenance window | Foreground service exempts from Doze |
| Battery Saver kills background | Android | Some OEMs aggressively kill services | User must whitelist app from battery optimization |
| Significant location ~500m accuracy | iOS | After termination, only significant changes detected | Acceptable — full tracking resumes on relaunch |
| First location after cold start slow | Both | GPS needs time to get fix | First update may take 5-30 seconds |
| Indoor GPS drift | Both | Location jumps when indoors | Distance filter helps, but some false updates possible |
| Xiaomi/Huawei aggressive kill | Android | Custom OS kills foreground services | User must disable "battery optimization" for app |

---

## 📝 Catatan untuk QA Team

1. **Selalu test di perangkat fisik** — Emulator tidak bisa mensimulasikan GPS, battery drain, atau OS background behavior secara akurat.
2. **Test di berbagai OEM** — Samsung, Xiaomi, Huawei, Oppo memiliki battery management yang berbeda-beda.
3. **Gunakan Firebase Console** untuk real-time verification — buka di browser terpisah saat testing.
4. **Screenshot setiap anomali** — termasuk logcat/console output.
5. **Battery test harus dilakukan 3x** untuk mendapatkan rata-rata yang reliable.
6. **Jangan lupa test dengan WiFi only, Mobile Data only, dan keduanya** — behavior bisa berbeda.
