package com.wireguard.android.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.collection.ArraySet
import com.wireguard.android.Application
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.model.Tunnel
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.android.util.SharedLibraryLoader
import com.wireguard.config.Config
import com.wireguard.crypto.KeyEncoding
import java9.util.concurrent.CompletableFuture
import timber.log.Timber
import java.util.Formatter
import java.util.Objects
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GoBackend(context: Context) : Backend {

    private var context: Context
    private var currentTunnel: Tunnel? = null
    private var currentTunnelHandle = -1

    private external fun wgGetSocketV4(handle: Int): Int

    private external fun wgGetSocketV6(handle: Int): Int

    private external fun wgTurnOff(handle: Int)

    private external fun wgTurnOn(ifName: String, tunFd: Int, settings: String): Int

    private external fun wgVersion(): String

    init {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go")
        this.context = context
        Timber.tag(TAG)
    }

    override fun applyConfig(tunnel: Tunnel?, config: Config?): Config? {
        if (tunnel?.state == Tunnel.State.UP) {
            // Restart the tunnel to apply the new config.
            setStateInternal(tunnel, tunnel.getConfig(), Tunnel.State.DOWN)
            try {
                setStateInternal(tunnel, config, Tunnel.State.UP)
            } catch (e: Exception) {
                // The new configuration didn't work, so try to go back to the old one.
                setStateInternal(tunnel, tunnel.getConfig(), Tunnel.State.UP)
                throw e
            }
        }
        return config
    }

    override fun enumerate(): Set<String>? {
        currentTunnel?.let {
            val runningTunnels = ArraySet<String>()
            runningTunnels.add(it.name)
            return runningTunnels
        }
        return emptySet()
    }

    override fun getState(tunnel: Tunnel?): Tunnel.State? {
        return if (currentTunnel == tunnel) Tunnel.State.UP else Tunnel.State.DOWN
    }

    override fun getStatistics(tunnel: Tunnel?): Tunnel.Statistics? {
        return Tunnel.Statistics()
    }

    override fun setState(tunnel: Tunnel?, state: Tunnel.State?): Tunnel.State? {
        val originalState = getState(tunnel)
        var finalState = state
        if (state == Tunnel.State.TOGGLE)
            finalState = if (originalState == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        if (state == originalState)
            return originalState
        if (state == Tunnel.State.UP && currentTunnel != null)
            throw IllegalStateException("Only one userspace tunnel can run at a time")
        Timber.d("Changing tunnel %s to state %s ", tunnel?.name, finalState)
        setStateInternal(tunnel, tunnel?.getConfig(), finalState)
        return getState(tunnel)
    }

    override fun getVersion(): String? {
        return wgVersion()
    }

    override fun getTypeName(): String {
        return "Go userspace"
    }

    @Throws(Exception::class)
    private fun setStateInternal(tunnel: Tunnel?, config: Config?, state: Tunnel.State?) {
        if (state == Tunnel.State.UP) {
            Timber.i("Bringing tunnel up")

            Objects.requireNonNull<Config>(config, "Trying to bring up a tunnel with no config")

            val service: VpnService
            if (!vpnService.isDone)
                startVpnService()

            try {
                service = vpnService.get(2, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw Exception("Unable to start Android VPN service", e)
            }

            if (currentTunnelHandle != -1) {
                Timber.w("Tunnel already up")
                return
            }

            // Build config
            val iface = config!!.`interface`
            var goConfig = ""
            Formatter(StringBuilder()).use { fmt ->
                fmt.format("replace_peers=true\n")
                iface.getPrivateKey()?.let {
                    fmt.format(
                        "private_key=%s\n",
                        KeyEncoding.keyToHex(KeyEncoding.keyFromBase64(it))
                    )
                }
                if (iface.getListenPort() != 0) {
                    fmt.format("listen_port=%d\n", iface.getListenPort())
                }
                config.getPeers().forEach { peer ->
                    peer.publicKey?.let {
                        fmt.format("public_key=%s\n", KeyEncoding.keyToHex(KeyEncoding.keyFromBase64(it)))
                    }
                    peer.preSharedKey?.let {
                        fmt.format(
                            "preshared_key=%s\n",
                            KeyEncoding.keyToHex(KeyEncoding.keyFromBase64(it))
                        )
                    }
                    peer.endpoint?.let {
                        fmt.format("endpoint=%s\n", peer.resolvedEndpointString)
                    }
                    if (peer.persistentKeepalive != 0)
                        fmt.format("persistent_keepalive_interval=%d\n", peer.persistentKeepalive)
                    peer.allowedIPs.forEach {
                        fmt.format("allowed_ip=%s\n", it.toString())
                    }
                }
                goConfig = fmt.toString()
            }

            // Create the vpn tunnel with android API
            val builder = service.getBuilder()
            builder.setSession(tunnel?.name)

            val configureIntent = Intent(context, MainActivity::class.java)
            configureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setConfigureIntent(PendingIntent.getActivity(context, 0, configureIntent, 0))

            config.`interface`.getExcludedApplications().forEach { excludedApplication ->
                builder.addDisallowedApplication(excludedApplication)
            }

            config.`interface`.getAddresses().forEach { addr ->
                builder.addAddress(addr.address, addr.mask)
            }

            config.`interface`.getDnses().forEach { dns ->
                builder.addDnsServer(dns.hostAddress)
            }

            config.getPeers().forEach { peer ->
                peer.allowedIPs.forEach { addr ->
                    builder.addRoute(addr.address, addr.mask)
                }
            }

            val mtu = if (config.`interface`.getMtu() != 0) config.`interface`.getMtu() else 1280
            builder.setMtu(mtu)

            builder.setBlocking(true)
            builder.establish().use { tun ->
                if (tun == null)
                    throw Exception("Unable to create tun device")
                Timber.d("Go backend v%s", wgVersion())
                currentTunnelHandle = wgTurnOn(tunnel!!.name, tun.detachFd(), goConfig)
            }
            if (currentTunnelHandle < 0)
                throw Exception("Unable to turn tunnel on (wgTurnOn return $currentTunnelHandle)")

            currentTunnel = tunnel

            service.protect(wgGetSocketV4(currentTunnelHandle))
            service.protect(wgGetSocketV6(currentTunnelHandle))
        } else {
            Timber.i("Bringing tunnel down")

            if (currentTunnelHandle == -1) {
                Timber.w("Tunnel already down")
                return
            }

            wgTurnOff(currentTunnelHandle)
            currentTunnel = null
            currentTunnelHandle = -1
        }
    }

    private fun startVpnService() {
        Timber.d("Requesting to start VpnService")
        context.startService(Intent(context, VpnService::class.java))
    }

    class VpnService : android.net.VpnService() {
        fun getBuilder(): android.net.VpnService.Builder {
            return Builder()
        }

        override fun onCreate() {
            vpnService.complete(this)
            super.onCreate()
        }

        override fun onDestroy() {
            Application.tunnelManager.getTunnels().thenAccept { tunnels ->
                tunnels.forEach { tunnel ->
                    if (tunnel != null && tunnel.state != Tunnel.State.DOWN)
                        tunnel.setState(Tunnel.State.DOWN)
                }
            }

            vpnService = vpnService.newIncompleteFuture<VpnService>()
            super.onDestroy()
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            vpnService.complete(this)
            if (intent == null || intent.component == null || intent.component?.packageName != packageName) {
                Timber.d("Service started by Always-on VPN feature")
                Application.tunnelManager.restoreState(true).whenComplete(ExceptionLoggers.D)
            }
            return super.onStartCommand(intent, flags, startId)
        }

        companion object {
            fun prepare(context: Context): Intent? {
                return android.net.VpnService.prepare(context)
            }
        }
    }

    companion object {
        private val TAG = "WireGuard/" + GoBackend::class.java.simpleName
        private var vpnService = CompletableFuture<VpnService>()
    }
}