package com.livetracking.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Listener interface for network state changes.
 * Implementations receive callbacks when connectivity is gained or lost.
 */
interface NetworkStateListener {
    /**
     * Called when network connectivity is restored.
     * Use this to trigger queue flush for pending location data.
     */
    fun onNetworkAvailable()

    /**
     * Called when network connectivity is lost.
     */
    fun onNetworkLost()
}

/**
 * NetworkListener monitors device network connectivity using ConnectivityManager.
 * When connectivity is restored after being offline, it notifies the registered listener
 * so that pending queued locations can be flushed to Firebase.
 *
 * Requires API 24+ for registerDefaultNetworkCallback.
 *
 * Usage:
 * ```
 * val listener = NetworkListener(context)
 * listener.setNetworkStateListener(object : NetworkStateListener {
 *     override fun onNetworkAvailable() { /* flush queue */ }
 *     override fun onNetworkLost() { /* mark offline */ }
 * })
 * listener.startListening()
 * // ...
 * listener.stopListening()
 * ```
 */
class NetworkListener(private val context: Context) {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkStateListener: NetworkStateListener? = null
    private var isListening = false
    private var currentlyOnline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentlyOnline = true
            networkStateListener?.onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            currentlyOnline = false
            networkStateListener?.onNetworkLost()
        }
    }

    init {
        // Initialize current connectivity state
        currentlyOnline = checkCurrentConnectivity()
    }

    /**
     * Set the listener that will receive network state change callbacks.
     *
     * @param listener The NetworkStateListener implementation to notify
     */
    fun setNetworkStateListener(listener: NetworkStateListener) {
        this.networkStateListener = listener
    }

    /**
     * Start listening for network connectivity changes.
     * Registers a default network callback with ConnectivityManager (API 24+).
     * If already listening, this is a no-op.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun startListening() {
        if (isListening) return
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        isListening = true
    }

    /**
     * Stop listening for network connectivity changes.
     * Unregisters the network callback from ConnectivityManager.
     * If not currently listening, this is a no-op.
     */
    fun stopListening() {
        if (!isListening) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Callback was not registered, ignore
        }
        isListening = false
    }

    /**
     * Returns the current network connectivity state.
     *
     * @return true if the device currently has network connectivity, false otherwise
     */
    fun isOnline(): Boolean {
        return currentlyOnline
    }

    /**
     * Check current connectivity state using ConnectivityManager.
     * Uses NetworkCapabilities for API 23+ for accurate detection.
     */
    private fun checkCurrentConnectivity(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
