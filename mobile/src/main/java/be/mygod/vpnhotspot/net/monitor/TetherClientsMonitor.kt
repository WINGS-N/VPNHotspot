package be.mygod.vpnhotspot.net.monitor

import android.net.LinkAddress
import android.net.MacAddress
import android.os.Build
import android.os.Parcelable
import androidx.annotation.RequiresApi
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.TetheringCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

object TetherClientsMonitor {
    private val classTetheredClient by lazy { Class.forName("android.net.TetheredClient") }
    private val getMacAddress by lazy { classTetheredClient.getDeclaredMethod("getMacAddress") }
    private val getAddresses by lazy { classTetheredClient.getDeclaredMethod("getAddresses") }
    private val getTetheringType by lazy { classTetheredClient.getDeclaredMethod("getTetheringType") }

    private val classAddressInfo by lazy { Class.forName("android.net.TetheredClient\$AddressInfo") }
    private val getAddress by lazy { classAddressInfo.getDeclaredMethod("getAddress") }

    interface Callback {
        fun onClientsChanged(clients: Collection<ClientInfo>) { }
    }

    data class ClientInfo(
        val macAddress: MacAddress,
        val fallbackType: TetherType,
        val addresses: List<LinkAddress>,
    )

    private val callbacks = LinkedHashSet<Callback>()
    private var callbackJob: Job? = null
    private var currentClients: List<ClientInfo> = emptyList()

    @JvmStatic
    fun registerCallback(callback: Callback) {
        val snapshot = synchronized(this) {
            if (!callbacks.add(callback)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ensureStartedLocked()
            }
            currentClients.toList()
        }
        if (snapshot.isNotEmpty()) {
            GlobalScope.launch { callback.onClientsChanged(snapshot) }
        }
    }

    @JvmStatic
    fun unregisterCallback(callback: Callback) {
        synchronized(this) {
            if (!callbacks.remove(callback)) return
            if (callbacks.isEmpty()) {
                callbackJob?.cancel()
                callbackJob = null
                currentClients = emptyList()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ensureStartedLocked() {
        if (callbackJob != null) return
        callbackJob = GlobalScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    RootManager.use { server ->
                        handleChannel(server.create(TetheringCommands.RegisterTetheringEventCallback(), this))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.w(error)
                    delay(1_000L)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun handleChannel(channel: ReceiveChannel<TetheringCommands.OnClientsChanged>) {
        channel.consumeEach { event ->
            val parsed = parseClients(event.clients)
            val registeredCallbacks = synchronized(this) {
                currentClients = parsed
                callbacks.toList()
            }
            Timber.i("TetherClientsMonitor: ${parsed.size} clients")
            for (callback in registeredCallbacks) {
                callback.onClientsChanged(parsed)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun parseClients(rawClients: Collection<Parcelable>): List<ClientInfo> = rawClients.mapNotNull { client ->
        try {
            ClientInfo(
                getMacAddress.invoke(client) as MacAddress,
                TetherType.fromTetheringType(getTetheringType.invoke(client) as Int),
                (getAddresses.invoke(client) as List<*>).mapNotNull { info ->
                    getAddress.invoke(info) as? LinkAddress
                }
            )
        } catch (error: Throwable) {
            Timber.w(error)
            null
        }
    }
}
