package com.desklink.android.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.DiscoveredServer
import com.desklink.android.domain.transport.PeerDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeskLink"
private const val MULTICAST_TAG = "desklink-nsd"

/** Reads the advertised OS version from a resolved service's TXT record, if present. */
private fun osOf(info: NsdServiceInfo): String? =
    info.attributes[ProtocolConstants.TXT_KEY_OS]?.let { String(it, Charsets.UTF_8) }

/**
 * NsdManager-backed [PeerDiscovery]: browses for the Mac's advertised
 * [ProtocolConstants.SERVICE_TYPE] over Wi-Fi and resolves each to a host/port.
 *
 * Requires the NEARBY_WIFI_DEVICES runtime permission on Android 13+ (the caller
 * requests it before collecting); without it, discovery yields nothing. A multicast
 * lock is held while browsing so mDNS replies are received on devices that filter
 * multicast by default (needs CHANGE_WIFI_MULTICAST_STATE).
 *
 * Resolution uses the modern registerServiceInfoCallback on API 34+, falling back to
 * resolveService below 34 (the only resolve API on the minSdk 28 baseline, where it is
 * not yet deprecated).
 *
 * NOTE: mDNS behavior is device/OS-specific; this path needs on-device verification.
 */
@Singleton
class NsdPeerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) : PeerDiscovery {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val nsd = nsdManager
        if (nsd == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val registry = DiscoveredServerRegistry()
        val emit: (List<DiscoveredServer>) -> Unit = { trySend(it) }

        // mDNS replies arrive via multicast, which some devices drop unless a lock is held.
        val multicastLock = wifiManager?.createMulticastLock(MULTICAST_TAG)?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
                .onFailure { Log.w(TAG, "multicast lock acquire failed: ${it.message}") }
        }

        val resolver: ServiceResolver =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                CallbackResolver(nsd, context.mainExecutor, registry, emit)
            } else {
                LegacyResolver(nsd, registry, emit)
            }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolver.resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                emit(registry.remove(serviceInfo.serviceName))
            }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
            }
        }

        runCatching {
            nsd.discoverServices(
                ProtocolConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure {
            Log.w(TAG, "discoverServices threw: ${it.message}")
            trySend(emptyList())
        }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            resolver.cancelAll()
            multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        }
    }

    /** Resolves a found service to a host/port and updates the [registry]. */
    private interface ServiceResolver {
        fun resolve(info: NsdServiceInfo)
        fun cancelAll()
    }

    /** API 34+: continuous updates, no single-resolve limitation. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class CallbackResolver(
        private val nsd: NsdManager,
        private val executor: Executor,
        private val registry: DiscoveredServerRegistry,
        private val emit: (List<DiscoveredServer>) -> Unit,
    ) : ServiceResolver {
        private val callbacks = CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()

        override fun resolve(info: NsdServiceInfo) {
            val name = info.serviceName
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.w(TAG, "resolve register failed for $name: $errorCode")
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    val host = updated.hostAddresses.firstOrNull()?.hostAddress ?: return
                    emit(registry.upsert(DiscoveredServer(name, host, updated.port, osOf(updated))))
                }

                override fun onServiceLost() {
                    emit(registry.remove(name))
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
            callbacks.add(callback)
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { Log.w(TAG, "registerServiceInfoCallback threw: ${it.message}") }
        }

        override fun cancelAll() {
            callbacks.forEach { cb -> runCatching { nsd.unregisterServiceInfoCallback(cb) } }
            callbacks.clear()
        }
    }

    /** Below API 34: resolveService is the only resolve API (not yet deprecated there). */
    private class LegacyResolver(
        private val nsd: NsdManager,
        private val registry: DiscoveredServerRegistry,
        private val emit: (List<DiscoveredServer>) -> Unit,
    ) : ServiceResolver {
        override fun resolve(info: NsdServiceInfo) {
            // A fresh listener per call avoids the "listener already in use" error.
            @Suppress("DEPRECATION")
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: return
                    emit(
                        registry.upsert(
                            DiscoveredServer(resolved.serviceName, host, resolved.port, osOf(resolved)),
                        ),
                    )
                }
            }
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        }

        override fun cancelAll() {
            // Legacy resolves are fire-and-forget; nothing to unregister.
        }
    }
}
