package com.axio.reelz.transfer

import android.net.Network

/**
 * Process-wide holder for the currently-bound hotspot Network.
 *
 * WifiDirectManager (receiver side) sets this once ConnectivityManager confirms
 * the join. TransferService reads it when opening its sending socket, so the
 * connection is guaranteed to go over the hotspot Wi-Fi link even on OEMs that
 * don't reliably honor bindProcessToNetwork() for background Service traffic.
 *
 * On the sender side this stays null, which is fine — the sender created the
 * hotspot itself, so its default route already is the hotspot interface.
 */
object NetworkBindingHolder {
    @Volatile var current: Network? = null
}
