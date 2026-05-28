/**
 * Example App for react-native-live-tracking
 *
 * Demonstrates the complete lifecycle:
 * 1. Configure the library with Firebase and optimization settings
 * 2. Start location tracking
 * 3. Listen for location updates and errors
 * 4. Display location data in real-time
 * 5. Stop tracking and clean up
 *
 * Prerequisites:
 * - Firebase configured in your native project (google-services.json / GoogleService-Info.plist)
 * - Location permissions granted
 * - See README.md for full setup instructions
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  ScrollView,
  Alert,
  Platform,
} from 'react-native';

// Import the library and types
import LiveTracking, {
  type LocationData,
  type TrackingError,
  type TrackingStatus,
  type Subscription,
  TrackingState,
} from 'react-native-live-tracking';

export default function App() {
  // ─── State ───────────────────────────────────────────────────────────────────

  const [status, setStatus] = useState<TrackingStatus | null>(null);
  const [lastLocation, setLastLocation] = useState<LocationData | null>(null);
  const [lastError, setLastError] = useState<TrackingError | null>(null);
  const [locationCount, setLocationCount] = useState(0);
  const [isConfigured, setIsConfigured] = useState(false);

  // Keep subscription refs so we can clean up on unmount
  const locationSubRef = useRef<Subscription | null>(null);
  const errorSubRef = useRef<Subscription | null>(null);

  // ─── Configuration ───────────────────────────────────────────────────────────

  /**
   * Step 1: Configure the library.
   *
   * This sets up:
   * - Optimization: 10s interval, 10m distance filter, motion sleep enabled
   * - Android notification: shown while tracking in background
   * - Firebase: RTDB with both current location and history paths
   */
  const handleConfigure = useCallback(async () => {
    try {
      await LiveTracking.configure({
        optimization: {
          intervalMs: 10000, // Update every 10 seconds minimum
          distanceFilterMeters: 10, // Only update if moved 10+ meters
          stopWhenStill: true, // Enable motion sleep mode
        },
        // Android foreground service notification (required for Android)
        androidNotification: {
          title: 'Live Tracking Active',
          text: 'Your location is being tracked',
          icon: 'ic_notification', // Must exist in android/app/src/main/res/drawable
          channelId: 'live-tracking-channel',
          channelName: 'Location Tracking',
        },
        firebase: {
          service: 'RTDB', // Use Firebase Realtime Database
          currentLocationPath: '/users/user123/currentLocation', // Overwrite mode
          historyPath: '/users/user123/locationHistory', // Append mode
          historyBatchSize: 15, // Send batch every 15 locations
        },
      });

      setIsConfigured(true);
      setLastError(null);
      Alert.alert('Success', 'Library configured successfully');
    } catch (error: any) {
      // Configuration errors are descriptive - they tell you which field is invalid
      setLastError({
        code: 'INVALID_CONFIG',
        message: error.message,
        recoverable: true,
      });
      Alert.alert('Configuration Error', error.message);
    }
  }, []);

  // ─── Start Tracking ──────────────────────────────────────────────────────────

  /**
   * Step 2: Start location tracking.
   *
   * This will:
   * - Request location permissions if not granted
   * - Start the Android foreground service (with notification)
   * - Enable iOS background location updates
   * - Begin receiving location updates through the listener
   */
  const handleStart = useCallback(async () => {
    try {
      await LiveTracking.start();
      setLastError(null);

      // Refresh status after starting
      const currentStatus = await LiveTracking.getStatus();
      setStatus(currentStatus);
    } catch (error: any) {
      // Common errors: PERMISSION_DENIED, GPS_DISABLED, NOT_CONFIGURED
      setLastError({
        code: error.code || 'UNKNOWN',
        message: error.message,
        recoverable: true,
      });
      Alert.alert('Start Error', error.message);
    }
  }, []);

  // ─── Stop Tracking ───────────────────────────────────────────────────────────

  /**
   * Step 3: Stop location tracking.
   *
   * This will:
   * - Stop the Android foreground service and remove notification
   * - Stop iOS background location updates
   * - Stop sending events to listeners (listeners remain registered)
   * - Queued locations will be sent on next start
   */
  const handleStop = useCallback(async () => {
    try {
      await LiveTracking.stop();
      setLastError(null);

      // Refresh status after stopping
      const currentStatus = await LiveTracking.getStatus();
      setStatus(currentStatus);
    } catch (error: any) {
      Alert.alert('Stop Error', error.message);
    }
  }, []);

  // ─── Event Listeners ─────────────────────────────────────────────────────────

  /**
   * Step 4: Register event listeners.
   *
   * onLocationUpdate: Called every time a valid location passes the
   * Distance/Time Matrix filter. Contains lat, lng, timestamp, accuracy, speed.
   *
   * onError: Called when tracking errors occur (permission denied, GPS off,
   * Firebase write failures, etc.)
   */
  useEffect(() => {
    // Register location update listener
    locationSubRef.current = LiveTracking.onLocationUpdate(
      (location: LocationData) => {
        setLastLocation(location);
        setLocationCount((prev) => prev + 1);
      }
    );

    // Register error listener
    errorSubRef.current = LiveTracking.onError((error: TrackingError) => {
      setLastError(error);
      console.warn('[LiveTracking Error]', error.code, error.message);
    });

    // Cleanup: remove listeners on unmount
    return () => {
      locationSubRef.current?.remove();
      errorSubRef.current?.remove();
    };
  }, []);

  // ─── Status Polling ──────────────────────────────────────────────────────────

  /**
   * Periodically refresh the tracking status to show current state,
   * queue size, and battery optimization mode.
   */
  const refreshStatus = useCallback(async () => {
    try {
      const currentStatus = await LiveTracking.getStatus();
      setStatus(currentStatus);
    } catch (error: any) {
      console.warn('Failed to get status:', error.message);
    }
  }, []);

  // ─── Render ──────────────────────────────────────────────────────────────────

  const isTracking = status?.state === TrackingState.TRACKING ||
    status?.state === TrackingState.MOTION_SLEEP;

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>react-native-live-tracking</Text>
        <Text style={styles.subtitle}>Example App</Text>

        {/* Status Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Status</Text>
          <Text style={styles.info}>
            State: {status?.state ?? 'unknown'}
          </Text>
          <Text style={styles.info}>
            Online: {status?.isOnline ? '✅ Yes' : '❌ No'}
          </Text>
          <Text style={styles.info}>
            Queued: {status?.queuedLocations ?? 0} locations
          </Text>
          <Text style={styles.info}>
            Battery Mode: {status?.batteryOptimization ?? 'N/A'}
          </Text>
          <Text style={styles.info}>
            Updates Received: {locationCount}
          </Text>
        </View>

        {/* Last Location Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Last Location</Text>
          {lastLocation ? (
            <>
              <Text style={styles.info}>
                📍 {lastLocation.latitude.toFixed(6)}, {lastLocation.longitude.toFixed(6)}
              </Text>
              <Text style={styles.info}>
                Accuracy: {lastLocation.accuracy.toFixed(1)}m
              </Text>
              <Text style={styles.info}>
                Speed: {lastLocation.speed != null ? `${lastLocation.speed.toFixed(1)} m/s` : 'N/A'}
              </Text>
              <Text style={styles.info}>
                Time: {new Date(lastLocation.timestamp).toLocaleTimeString()}
              </Text>
            </>
          ) : (
            <Text style={styles.info}>No location received yet</Text>
          )}
        </View>

        {/* Error Section */}
        {lastError && (
          <View style={[styles.section, styles.errorSection]}>
            <Text style={styles.sectionTitle}>Last Error</Text>
            <Text style={styles.errorText}>
              [{lastError.code}] {lastError.message}
            </Text>
            <Text style={styles.info}>
              Recoverable: {lastError.recoverable ? 'Yes' : 'No'}
            </Text>
          </View>
        )}

        {/* Control Buttons */}
        <View style={styles.buttonContainer}>
          <TouchableOpacity
            style={[styles.button, styles.configureButton]}
            onPress={handleConfigure}
            disabled={isTracking}
          >
            <Text style={styles.buttonText}>1. Configure</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.button,
              styles.startButton,
              (!isConfigured || isTracking) && styles.buttonDisabled,
            ]}
            onPress={handleStart}
            disabled={!isConfigured || isTracking}
          >
            <Text style={styles.buttonText}>2. Start Tracking</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.button,
              styles.stopButton,
              !isTracking && styles.buttonDisabled,
            ]}
            onPress={handleStop}
            disabled={!isTracking}
          >
            <Text style={styles.buttonText}>3. Stop Tracking</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.button, styles.statusButton]}
            onPress={refreshStatus}
          >
            <Text style={styles.buttonText}>Refresh Status</Text>
          </TouchableOpacity>
        </View>

        {/* Platform Info */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Platform</Text>
          <Text style={styles.info}>
            {Platform.OS} {Platform.Version}
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  content: {
    padding: 20,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    color: '#333',
  },
  subtitle: {
    fontSize: 14,
    textAlign: 'center',
    color: '#666',
    marginBottom: 20,
  },
  section: {
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 16,
    marginBottom: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
    elevation: 2,
  },
  errorSection: {
    backgroundColor: '#fff3f3',
    borderLeftWidth: 4,
    borderLeftColor: '#e74c3c',
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 8,
    color: '#333',
  },
  info: {
    fontSize: 14,
    color: '#555',
    marginBottom: 4,
  },
  errorText: {
    fontSize: 14,
    color: '#e74c3c',
    marginBottom: 4,
  },
  buttonContainer: {
    marginVertical: 16,
    gap: 10,
  },
  button: {
    paddingVertical: 14,
    paddingHorizontal: 20,
    borderRadius: 8,
    alignItems: 'center',
  },
  configureButton: {
    backgroundColor: '#3498db',
  },
  startButton: {
    backgroundColor: '#27ae60',
  },
  stopButton: {
    backgroundColor: '#e74c3c',
  },
  statusButton: {
    backgroundColor: '#8e44ad',
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
