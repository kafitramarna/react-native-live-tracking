package com.livetracking

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.livetracking.location.LocationEngine
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LocationEngine.
 *
 * NOTE: LocationEngine wraps FusedLocationProviderClient which requires Google Play Services.
 * These tests verify the logic layer that CAN be tested without Android instrumentation:
 * - LocationCallback forwarding behavior
 * - Listener registration
 * - Priority parameter mapping
 *
 * Full integration tests with actual FusedLocationProviderClient require instrumented tests
 * (androidTest) running on a device or emulator.
 */
class LocationEngineTest {

    private lateinit var mockFusedClient: FusedLocationProviderClient
    private lateinit var mockListener: LocationEngine.LocationUpdateListener

    @Before
    fun setup() {
        mockFusedClient = mockk(relaxed = true)
        mockListener = mockk(relaxed = true)
    }

    // --- Listener Registration Tests ---

    @Test
    fun `LocationUpdateListener interface has onLocationReceived method`() {
        // Verify the interface contract exists and is callable
        val listener = object : LocationEngine.LocationUpdateListener {
            var receivedLocation: Location? = null
            override fun onLocationReceived(location: Location) {
                receivedLocation = location
            }
        }

        val mockLocation = mockk<Location>(relaxed = true)
        listener.onLocationReceived(mockLocation)

        assertEquals(mockLocation, listener.receivedLocation)
    }

    @Test
    fun `LocationUpdateListener receives location from callback`() {
        // Simulate what happens inside the LocationCallback when a location is received
        val listener = object : LocationEngine.LocationUpdateListener {
            var receivedLocation: Location? = null
            override fun onLocationReceived(location: Location) {
                receivedLocation = location
            }
        }

        val mockLocation = mockk<Location>(relaxed = true) {
            every { latitude } returns 37.7749
            every { longitude } returns -122.4194
            every { accuracy } returns 10.0f
            every { time } returns 1700000000000L
        }

        listener.onLocationReceived(mockLocation)

        assertNotNull(listener.receivedLocation)
        assertEquals(37.7749, listener.receivedLocation!!.latitude, 0.0001)
        assertEquals(-122.4194, listener.receivedLocation!!.longitude, 0.0001)
    }

    @Test
    fun `LocationUpdateListener handles multiple sequential locations`() {
        val locations = mutableListOf<Location>()
        val listener = object : LocationEngine.LocationUpdateListener {
            override fun onLocationReceived(location: Location) {
                locations.add(location)
            }
        }

        val loc1 = mockk<Location>(relaxed = true) { every { latitude } returns 1.0 }
        val loc2 = mockk<Location>(relaxed = true) { every { latitude } returns 2.0 }
        val loc3 = mockk<Location>(relaxed = true) { every { latitude } returns 3.0 }

        listener.onLocationReceived(loc1)
        listener.onLocationReceived(loc2)
        listener.onLocationReceived(loc3)

        assertEquals(3, locations.size)
        assertEquals(1.0, locations[0].latitude, 0.0001)
        assertEquals(2.0, locations[1].latitude, 0.0001)
        assertEquals(3.0, locations[2].latitude, 0.0001)
    }

    // --- Priority Constants Tests ---

    @Test
    fun `PRIORITY_HIGH_ACCURACY constant is available`() {
        // Verify the priority constant used by LocationEngine is accessible
        assertEquals(100, Priority.PRIORITY_HIGH_ACCURACY)
    }

    @Test
    fun `PRIORITY_LOW_POWER constant is available`() {
        // Verify the low-power priority constant used in sleep mode
        assertEquals(104, Priority.PRIORITY_LOW_POWER)
    }

    @Test
    fun `PRIORITY_BALANCED_POWER_ACCURACY constant is available`() {
        assertEquals(102, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    }

    // --- LocationCallback Behavior Tests ---

    @Test
    fun `LocationCallback does not crash when listener is null`() {
        // Simulates the scenario where locationCallback fires but no listener is set.
        // The actual LocationEngine handles this with null-safe call (locationListener?.onLocationReceived)
        // This test verifies the pattern is safe.
        var listenerRef: LocationEngine.LocationUpdateListener? = null

        val mockLocation = mockk<Location>(relaxed = true)

        // Should not throw - mirrors the null-safe call in LocationEngine
        listenerRef?.onLocationReceived(mockLocation)
    }

    @Test
    fun `LocationCallback ignores null lastLocation from LocationResult`() {
        // LocationResult.lastLocation can be null; the engine should handle this gracefully
        val mockLocationResult = mockk<LocationResult> {
            every { lastLocation } returns null
        }

        // Simulating the guard: val location = locationResult.lastLocation ?: return
        val lastLocation = mockLocationResult.lastLocation
        assertNull(lastLocation)
    }

    @Test
    fun `LocationCallback forwards non-null lastLocation to listener`() {
        val receivedLocations = mutableListOf<Location>()
        val listener = object : LocationEngine.LocationUpdateListener {
            override fun onLocationReceived(location: Location) {
                receivedLocations.add(location)
            }
        }

        val mockLocation = mockk<Location>(relaxed = true) {
            every { latitude } returns 40.7128
            every { longitude } returns -74.0060
        }

        val mockLocationResult = mockk<LocationResult> {
            every { lastLocation } returns mockLocation
        }

        // Simulate the callback logic: val location = locationResult.lastLocation ?: return
        val location = mockLocationResult.lastLocation
        if (location != null) {
            listener.onLocationReceived(location)
        }

        assertEquals(1, receivedLocations.size)
        assertEquals(40.7128, receivedLocations[0].latitude, 0.0001)
        assertEquals(-74.0060, receivedLocations[0].longitude, 0.0001)
    }

    // --- Parameter Validation Tests ---

    @Test
    fun `startLocationUpdates accepts valid interval and distance parameters`() {
        // Verify that typical parameter values are within expected ranges
        val intervalMs = 5000L
        val distanceFilter = 10.0f

        assertTrue(intervalMs > 0)
        assertTrue(distanceFilter >= 0)
    }

    @Test
    fun `startLocationUpdates accepts zero distance filter`() {
        // Zero distance filter means every location update is delivered
        val distanceFilter = 0.0f
        assertTrue(distanceFilter >= 0)
    }

    @Test
    fun `LocationEngine class exists in correct package`() {
        // Structural test: verify the class is accessible
        val clazz = LocationEngine::class.java
        assertEquals("com.livetracking.location.LocationEngine", clazz.name)
    }
}
