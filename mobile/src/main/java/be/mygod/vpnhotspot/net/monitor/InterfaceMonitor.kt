package be.mygod.vpnhotspot.net.monitor

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.allInterfaceNames
import be.mygod.vpnhotspot.util.globalNetworkRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.regex.PatternSyntaxException

class InterfaceMonitor(private val ifaceRegex: String) : UpstreamMonitor() {
    companion object {
        private const val ROOT_POLL_INTERVAL_MS = 1_000L
    }

    private val iface = try {
        ifaceRegex.toRegex()::matches
    } catch (e: PatternSyntaxException) {
        Timber.d(e);
        { it == ifaceRegex }
    }
    private val request = globalNetworkRequestBuilder().apply {
        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.build()
    private var registered = false

    private val available = HashMap<Network, LinkProperties?>()
    private var syntheticProperties: LinkProperties? = null
    private var pollJob: Job? = null
    override val currentLinkProperties: LinkProperties?
        get() = currentNetwork?.let { available[it] } ?: syntheticProperties

    private fun buildSyntheticProperties(interfaceName: String): LinkProperties = LinkProperties().apply {
        this.interfaceName = interfaceName
    }

    private fun findSyntheticProperties(): LinkProperties? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: SocketException) {
            Timber.d(e)
            null
        } ?: return null
        for (networkInterface in Collections.list(interfaces)) {
            val name = networkInterface?.name ?: continue
            if (!iface(name)) continue
            val isUsable = try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (e: SocketException) {
                Timber.d(e)
                false
            }
            if (isUsable) return buildSyntheticProperties(name)
        }
        return null
    }

    private fun currentEffectivePropertiesLocked(): LinkProperties? = currentNetwork?.let { available[it] } ?: syntheticProperties

    private fun maybeUpdateSyntheticProperties() {
        val callbacks: List<Callback>
        val effectiveProperties: LinkProperties?
        synchronized(this) {
            if (!registered) return
            val oldEffective = currentEffectivePropertiesLocked()
            if (available.isNotEmpty()) {
                if (syntheticProperties == null) return
                syntheticProperties = null
                effectiveProperties = currentEffectivePropertiesLocked()
                if (sameInterfaceName(oldEffective, effectiveProperties)) return
            } else {
                val updated = findSyntheticProperties()
                if (sameInterfaceName(syntheticProperties, updated)) return
                syntheticProperties = updated
                effectiveProperties = currentEffectivePropertiesLocked()
                if (sameInterfaceName(oldEffective, effectiveProperties)) return
            }
            callbacks = this.callbacks.toList()
        }
        GlobalScope.launch { callbacks.forEach { it.onAvailable(effectiveProperties) } }
    }

    private fun sameInterfaceName(old: LinkProperties?, new: LinkProperties?) =
        old?.interfaceName == new?.interfaceName

    private fun ensurePollerStarted() {
        if (pollJob != null) return
        pollJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                maybeUpdateSyntheticProperties()
                delay(ROOT_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPoller() {
        pollJob?.cancel()
        pollJob = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val properties = Services.connectivity.getLinkProperties(network)
            if (properties?.allInterfaceNames?.any(iface) != true) return
            val callbacks = synchronized(this@InterfaceMonitor) {
                available[network] = properties
                currentNetwork = network
                syntheticProperties = null
                callbacks.toList()
            }
            GlobalScope.launch { callbacks.forEach { it.onAvailable(properties) } }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val matched = properties.allInterfaceNames.any(iface)
            val (callbacks, newProperties) = synchronized(this@InterfaceMonitor) {
                if (matched) {
                    available[network] = properties
                    if (currentNetwork == null) currentNetwork = network else if (currentNetwork != network) return
                    syntheticProperties = null
                    callbacks.toList() to properties
                } else {
                    available.remove(network)
                    if (currentNetwork != network) return
                    val nextBest = available.entries.firstOrNull()
                    currentNetwork = nextBest?.key
                    callbacks.toList() to nextBest?.value
                }
            }
            GlobalScope.launch { callbacks.forEach { it.onAvailable(newProperties) } }
            if (!matched || newProperties == null) maybeUpdateSyntheticProperties()
        }

        override fun onLost(network: Network) {
            var properties: LinkProperties? = null
            val callbacks = synchronized(this@InterfaceMonitor) {
                if (available.remove(network) == null || currentNetwork != network) return
                val next = available.entries.firstOrNull()
                currentNetwork = next?.run {
                    properties = value
                    key
                }
                callbacks.toList()
            }
            GlobalScope.launch { callbacks.forEach { it.onAvailable(properties) } }
            if (properties == null) maybeUpdateSyntheticProperties()
        }
    }

    override fun registerCallbackLocked(callback: Callback) {
        if (registered) {
            val currentLinkProperties = currentLinkProperties
            if (currentLinkProperties != null) GlobalScope.launch {
                callback.onAvailable(currentLinkProperties)
            }
        } else {
            Services.registerNetworkCallback(request, networkCallback)
            registered = true
            ensurePollerStarted()
            maybeUpdateSyntheticProperties()
        }
    }

    override fun destroyLocked() {
        if (!registered) return
        Services.connectivity.unregisterNetworkCallback(networkCallback)
        registered = false
        stopPoller()
        available.clear()
        currentNetwork = null
        syntheticProperties = null
    }
}
