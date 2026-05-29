package com.livetracking

import android.os.SystemClock
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Priority
import com.livetracking.location.LocationEngine
import com.livetracking.optimizer.MotionSleepManager
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MotionSleepManager.
 *
 * Tests the motion sleep threshold logic:
 * - STILL detection and duration tracking
 * - Sleep mode activation after 3-minute threshold
 * - Sleep mode deactivation on movement
 * - stopWhenStill flag behavior
 *
 * NOTE: Uses MockK to mock SystemClock.elapsedRealtime() for time-based tests.
 * LocationEngine is mocked to verify startLocationUpdates/stopLocationUpdates calls.
 */
class MotionSleepManagerTest {

    private lateinit var mockLocationEngine: LocationEngine
    private lateinit var mockListener: MotionSleepManager.MotionSleepListener
    private lateinit var manager: MotionSleepManager
    private lateinit var managerDisabled: MotionSleepManager

    companion object {
        private const val DEFAULT_INTERVAL_MS = 5000L
        private const val DEFAULT_DISTANCE_FILTER = 10.0f
        private const val THREE_MINUTES_MS = 180_000L
    }

    @Before
    fun setup() {
        mockLocationEngine = mockk(relaxed = true)
        mockListener = mockk(relaxed = true)

        manager = MotionSleepManager(
            locationEngine = mockLocationEngine,
            stopWhenStill = true,
            intervalMs = DEFAULT_INTERVAL_MS,
            distanceFilter = DEFAULT_DISTANCE_FILTER
        )
        manager.setListener(mockListener)

        managerDisabled = MotionSleepManager(
            locationEngine = mockLocationEngine,
            stopWhenStill = false,
            intervalMs = DEFAULT_INTERVAL_MS,
            distanceFilter = DEFAULT_DISTANCE_FILTER
        )
        managerDisabled.setListener(mockListener)

        // Mock SystemClock.elapsedRealtime() for time-based tests
        mockkStatic(SystemClock::class)
    }

    // --- STILL Threshold Tests ---

    @Test
    fun `STILL for less than 3 minutes does NOT activate sleep mode`() {
        val startTime = 1000000L

        // First STILL detection - starts tracking
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        assertFalse("Should not be in sleep mode yet", manager.isInSleepMode())

        // Second STILL detection at 2 minutes (120,000ms) - still under threshold
        every { SystemClock.elapsedRealtime() } returns startTime + 120_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertFalse("Should not be in sleep mode at 2 minutes", manager.isInSleepMode())
        verify(exactly = 0) { mockListener.onSleepModeActivated() }
    }

    @Test
    fun `STILL for exactly 3 minutes does NOT activate sleep mode`() {
        val startTime = 1000000L

        // First STILL detection
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        // At exactly 3 minutes (threshold is > 3 minutes, not >=)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS
        manager.onActivityDetected(DetectedActivity.STILL)

        assertFalse("Should not be in sleep mode at exactly 3 minutes", manager.isInSleepMode())
        verify(exactly = 0) { mockListener.onSleepModeActivated() }
    }

    @Test
    fun `STILL for more than 3 minutes activates sleep mode`() {
        val startTime = 1000000L

        // First STILL detection - starts tracking
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        // Second STILL detection after 3+ minutes
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode after 3+ minutes", manager.isInSleepMode())
        verify(exactly = 1) { mockListener.onSleepModeActivated() }
    }

    @Test
    fun `STILL for 5 minutes activates sleep mode`() {
        val startTime = 1000000L

        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + 300_000L // 5 minutes
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode after 5 minutes", manager.isInSleepMode())
    }

    // --- Movement After Sleep Mode Tests ---

    @Test
    fun `ON_FOOT after sleep mode deactivates it`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode", manager.isInSleepMode())

        // Movement detected - ON_FOOT
        manager.onActivityDetected(DetectedActivity.ON_FOOT)

        assertFalse("Should exit sleep mode on ON_FOOT", manager.isInSleepMode())
        verify(exactly = 1) { mockListener.onSleepModeDeactivated() }
    }

    @Test
    fun `IN_VEHICLE after sleep mode deactivates it`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode", manager.isInSleepMode())

        // Movement detected - IN_VEHICLE
        manager.onActivityDetected(DetectedActivity.IN_VEHICLE)

        assertFalse("Should exit sleep mode on IN_VEHICLE", manager.isInSleepMode())
        verify(exactly = 1) { mockListener.onSleepModeDeactivated() }
    }

    @Test
    fun `WALKING after sleep mode deactivates it`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode", manager.isInSleepMode())

        // Movement detected - WALKING
        manager.onActivityDetected(DetectedActivity.WALKING)

        assertFalse("Should exit sleep mode on WALKING", manager.isInSleepMode())
        verify(exactly = 1) { mockListener.onSleepModeDeactivated() }
    }

    @Test
    fun `RUNNING after sleep mode deactivates it`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode", manager.isInSleepMode())

        // Movement detected - RUNNING
        manager.onActivityDetected(DetectedActivity.RUNNING)

        assertFalse("Should exit sleep mode on RUNNING", manager.isInSleepMode())
    }

    @Test
    fun `ON_BICYCLE after sleep mode deactivates it`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue("Should be in sleep mode", manager.isInSleepMode())

        // Movement detected - ON_BICYCLE
        manager.onActivityDetected(DetectedActivity.ON_BICYCLE)

        assertFalse("Should exit sleep mode on ON_BICYCLE", manager.isInSleepMode())
    }

    // --- stopWhenStill=false Tests ---

    @Test
    fun `stopWhenStill false makes onActivityDetected a no-op for STILL`() {
        val startTime = 1000000L

        every { SystemClock.elapsedRealtime() } returns startTime
        managerDisabled.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        managerDisabled.onActivityDetected(DetectedActivity.STILL)

        assertFalse("Should never enter sleep mode when disabled", managerDisabled.isInSleepMode())
        verify(exactly = 0) { mockListener.onSleepModeActivated() }
    }

    @Test
    fun `stopWhenStill false makes onActivityDetected a no-op for ON_FOOT`() {
        managerDisabled.onActivityDetected(DetectedActivity.ON_FOOT)

        assertFalse(managerDisabled.isInSleepMode())
        verify(exactly = 0) { mockListener.onSleepModeDeactivated() }
    }

    @Test
    fun `stopWhenStill false makes onActivityDetected a no-op for IN_VEHICLE`() {
        managerDisabled.onActivityDetected(DetectedActivity.IN_VEHICLE)

        assertFalse(managerDisabled.isInSleepMode())
        verify(exactly = 0) { mockListener.onSleepModeDeactivated() }
    }

    // --- isInSleepMode State Tests ---

    @Test
    fun `isInSleepMode returns false initially`() {
        assertFalse(manager.isInSleepMode())
    }

    @Test
    fun `isInSleepMode returns true after entering sleep mode`() {
        val startTime = 1000000L

        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue(manager.isInSleepMode())
    }

    @Test
    fun `isInSleepMode returns false after exiting sleep mode`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertTrue(manager.isInSleepMode())

        // Exit sleep mode
        manager.onActivityDetected(DetectedActivity.ON_FOOT)

        assertFalse(manager.isInSleepMode())
    }

    // --- LocationEngine Interaction Tests ---

    @Test
    fun `entering sleep mode switches to low-power location updates`() {
        val startTime = 1000000L

        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        // Verify location engine was stopped and restarted with low power
        verify(exactly = 1) { mockLocationEngine.stopLocationUpdates() }
        verify(exactly = 1) {
            mockLocationEngine.startLocationUpdates(
                DEFAULT_INTERVAL_MS * 5,  // 5x interval in sleep mode
                DEFAULT_DISTANCE_FILTER,
                Priority.PRIORITY_LOW_POWER
            )
        }
    }

    @Test
    fun `exiting sleep mode restores high-accuracy location updates`() {
        val startTime = 1000000L

        // Enter sleep mode
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)
        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        clearMocks(mockLocationEngine, answers = false)

        // Exit sleep mode
        manager.onActivityDetected(DetectedActivity.ON_FOOT)

        // Verify location engine was stopped and restarted with high accuracy
        verify(exactly = 1) { mockLocationEngine.stopLocationUpdates() }
        verify(exactly = 1) {
            mockLocationEngine.startLocationUpdates(
                DEFAULT_INTERVAL_MS,
                DEFAULT_DISTANCE_FILTER,
                Priority.PRIORITY_HIGH_ACCURACY
            )
        }
    }

    // --- Edge Cases ---

    @Test
    fun `multiple STILL detections before threshold do not activate sleep mode`() {
        val startTime = 1000000L

        // Multiple STILL detections, each 1 minute apart (under threshold)
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + 60_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + 120_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertFalse("Should not be in sleep mode at 2 minutes", manager.isInSleepMode())
    }

    @Test
    fun `movement resets still timer`() {
        val startTime = 1000000L

        // STILL for 2 minutes
        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + 120_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        // Brief movement
        manager.onActivityDetected(DetectedActivity.ON_FOOT)

        // STILL again for 2 minutes (should NOT trigger sleep because timer was reset)
        every { SystemClock.elapsedRealtime() } returns startTime + 200_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + 320_000L
        manager.onActivityDetected(DetectedActivity.STILL)

        assertFalse(
            "Should not be in sleep mode - timer was reset by movement",
            manager.isInSleepMode()
        )
    }

    @Test
    fun `movement when not in sleep mode does not call deactivated listener`() {
        // No sleep mode entered, just movement
        manager.onActivityDetected(DetectedActivity.ON_FOOT)

        verify(exactly = 0) { mockListener.onSleepModeDeactivated() }
    }

    @Test
    fun `STILL_THRESHOLD_MS constant is 180000`() {
        assertEquals(180_000L, MotionSleepManager.STILL_THRESHOLD_MS)
    }

    @Test
    fun `sleep mode interval is 5x normal interval`() {
        val startTime = 1000000L

        every { SystemClock.elapsedRealtime() } returns startTime
        manager.onActivityDetected(DetectedActivity.STILL)

        every { SystemClock.elapsedRealtime() } returns startTime + THREE_MINUTES_MS + 1000L
        manager.onActivityDetected(DetectedActivity.STILL)

        // Verify the sleep mode interval is 5x the configured interval
        verify {
            mockLocationEngine.startLocationUpdates(
                eq(DEFAULT_INTERVAL_MS * 5),
                any(),
                any()
            )
        }
    }
}
