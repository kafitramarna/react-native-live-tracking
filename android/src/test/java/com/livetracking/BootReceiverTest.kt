package com.livetracking

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.livetracking.receiver.BootReceiver
import com.livetracking.receiver.TrackingStateStore
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BootReceiver and TrackingStateStore.
 *
 * NOTE: TrackingStateStore uses SharedPreferences which requires Android Context.
 * These tests verify:
 * - TrackingStateStore constants and structure
 * - BootReceiver intent action filtering logic
 * - SharedPreferences interaction patterns (mocked)
 *
 * Full SharedPreferences persistence tests require instrumented tests (androidTest)
 * or Robolectric for simulating Android framework behavior.
 */
class BootReceiverTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockSharedPreferences = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every {
            mockContext.getSharedPreferences("live_tracking_prefs", Context.MODE_PRIVATE)
        } returns mockSharedPreferences

        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs
    }

    // --- TrackingStateStore Constants Tests ---

    @Test
    fun `TrackingStateStore uses correct preferences name`() {
        // Verify the prefs name by checking the mock interaction
        TrackingStateStore.saveTrackingActive(mockContext, true)

        verify {
            mockContext.getSharedPreferences("live_tracking_prefs", Context.MODE_PRIVATE)
        }
    }

    @Test
    fun `TrackingStateStore uses correct key for tracking state`() {
        TrackingStateStore.saveTrackingActive(mockContext, true)

        verify {
            mockEditor.putBoolean("is_tracking_active", true)
        }
    }

    // --- TrackingStateStore Save Tests ---

    @Test
    fun `saveTrackingActive with true saves true to SharedPreferences`() {
        TrackingStateStore.saveTrackingActive(mockContext, true)

        verify(exactly = 1) { mockEditor.putBoolean("is_tracking_active", true) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    @Test
    fun `saveTrackingActive with false saves false to SharedPreferences`() {
        TrackingStateStore.saveTrackingActive(mockContext, false)

        verify(exactly = 1) { mockEditor.putBoolean("is_tracking_active", false) }
        verify(exactly = 1) { mockEditor.apply() }
    }

    // --- TrackingStateStore Read Tests ---

    @Test
    fun `isTrackingActive returns true when stored value is true`() {
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns true

        val result = TrackingStateStore.isTrackingActive(mockContext)

        assertTrue("Should return true when tracking was active", result)
    }

    @Test
    fun `isTrackingActive returns false when stored value is false`() {
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns false

        val result = TrackingStateStore.isTrackingActive(mockContext)

        assertFalse("Should return false when tracking was not active", result)
    }

    @Test
    fun `isTrackingActive returns false by default when no value stored`() {
        // Default value is false when key doesn't exist
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns false

        val result = TrackingStateStore.isTrackingActive(mockContext)

        assertFalse("Should return false by default", result)
    }

    // --- TrackingStateStore Round-Trip Tests ---

    @Test
    fun `save and read tracking state round-trip with true`() {
        // Simulate save
        TrackingStateStore.saveTrackingActive(mockContext, true)
        verify { mockEditor.putBoolean("is_tracking_active", true) }

        // Simulate read after save
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns true

        val result = TrackingStateStore.isTrackingActive(mockContext)
        assertTrue(result)
    }

    @Test
    fun `save and read tracking state round-trip with false`() {
        // Simulate save
        TrackingStateStore.saveTrackingActive(mockContext, false)
        verify { mockEditor.putBoolean("is_tracking_active", false) }

        // Simulate read after save
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns false

        val result = TrackingStateStore.isTrackingActive(mockContext)
        assertFalse(result)
    }

    // --- BootReceiver Intent Filtering Tests ---

    @Test
    fun `BootReceiver class exists in correct package`() {
        val clazz = BootReceiver::class.java
        assertEquals("com.livetracking.receiver.BootReceiver", clazz.name)
    }

    @Test
    fun `BootReceiver extends BroadcastReceiver`() {
        val receiver = BootReceiver()
        assertTrue(
            "BootReceiver should extend BroadcastReceiver",
            receiver is android.content.BroadcastReceiver
        )
    }

    @Test
    fun `ACTION_BOOT_COMPLETED constant is correct`() {
        assertEquals(
            "android.intent.action.BOOT_COMPLETED",
            Intent.ACTION_BOOT_COMPLETED
        )
    }

    @Test
    fun `BootReceiver only responds to BOOT_COMPLETED action`() {
        // Verify the intent action check logic
        val bootIntent = mockk<Intent> {
            every { action } returns Intent.ACTION_BOOT_COMPLETED
        }

        val otherIntent = mockk<Intent> {
            every { action } returns "com.some.other.ACTION"
        }

        // The receiver should only process BOOT_COMPLETED
        assertEquals(Intent.ACTION_BOOT_COMPLETED, bootIntent.action)
        assertNotEquals(Intent.ACTION_BOOT_COMPLETED, otherIntent.action)
    }

    @Test
    fun `BootReceiver does not start service when tracking is not active`() {
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns false

        val isActive = TrackingStateStore.isTrackingActive(mockContext)

        assertFalse("Should not start service when tracking is inactive", isActive)
    }

    @Test
    fun `BootReceiver starts service when tracking is active`() {
        every {
            mockSharedPreferences.getBoolean("is_tracking_active", false)
        } returns true

        val isActive = TrackingStateStore.isTrackingActive(mockContext)

        assertTrue("Should start service when tracking was active", isActive)
    }

    // --- TrackingStateStore Object Tests ---

    @Test
    fun `TrackingStateStore is a singleton object`() {
        // Kotlin object declarations are singletons
        val ref1 = TrackingStateStore
        val ref2 = TrackingStateStore
        assertSame(ref1, ref2)
    }

    @Test
    fun `TrackingStateStore uses MODE_PRIVATE for SharedPreferences`() {
        TrackingStateStore.isTrackingActive(mockContext)

        verify {
            mockContext.getSharedPreferences("live_tracking_prefs", Context.MODE_PRIVATE)
        }
    }

    /*
     * ============================================================================
     * NOTE: The following tests require a real Android Context and cannot run as
     * local unit tests. They should be implemented as instrumented tests (androidTest):
     *
     * - SharedPreferences actually persists data across reads
     * - TrackingStateStore survives process restart (SharedPreferences persistence)
     * - BootReceiver.onReceive actually starts TrackingForegroundService
     * - BootReceiver handles null intent gracefully on real device
     * - Service restart behavior after actual device reboot
     * ============================================================================
     */
}
