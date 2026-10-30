package com.livetracking.runtime

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * StatusWatcher watches an RTDB path natively via a ValueEventListener.
 *
 * Lives independently of the RN/JS layer — it is armed while tracking is active
 * and requests a stop when the watched value equals `stopValue`. Because it runs
 * in the service process, remote stop signals (e.g. delivery status -> COMPLETED)
 * are honored even when the React context is dead.
 *
 * The first snapshot after arm() also delivers the current value, so a stop
 * signal that was missed while the app was dead is still honored on arm.
 */
class StatusWatcher(
    private val stopValue: Int,
    private val onStopRequested: () -> Unit
) {

    private var reference: DatabaseReference? = null
    private var listener: ValueEventListener? = null

    /** The path currently being watched, or null when disarmed. */
    var armedPath: String? = null
        private set

    val isArmed: Boolean
        get() = listener != null

    /**
     * Start watching [path]. Idempotent — re-arming the same path is a no-op,
     * arming a different path disarms the previous listener first.
     */
    fun arm(path: String) {
        if (armedPath == path && listener != null) return
        disarm()

        val ref = FirebaseDatabase.getInstance().getReference(path)
        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isStopValue(snapshot.value)) {
                    onStopRequested()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Keep the listener attached — transient errors (network, auth
                // refresh) recover on their own; disarming would lose the stop path.
            }
        }

        ref.addValueEventListener(valueListener)
        reference = ref
        listener = valueListener
        armedPath = path
    }

    /**
     * Stop watching and detach the listener.
     */
    fun disarm() {
        val l = listener
        val r = reference
        if (l != null && r != null) {
            r.removeEventListener(l)
        }
        listener = null
        reference = null
        armedPath = null
    }

    companion object {
        /**
         * Whether an RTDB snapshot value represents the stop signal.
         * RTDB numeric values may arrive as Long or Double; string values are
         * also parsed defensively.
         */
        fun isStopValue(value: Any?, stopValue: Int): Boolean = when (value) {
            is Number -> value.toInt() == stopValue
            is String -> value.trim().toIntOrNull() == stopValue
            else -> false
        }
    }

    private fun isStopValue(value: Any?): Boolean = isStopValue(value, stopValue)
}
