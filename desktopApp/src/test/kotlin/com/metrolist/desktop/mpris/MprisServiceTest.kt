package com.metrolist.desktop.mpris

import com.metrolist.desktop.playback.PlayerController
import org.freedesktop.dbus.connections.BusAddress
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full MPRIS round-trip on an embedded D-Bus daemon: export the player,
 * call its methods/properties from a second connection and observe the
 * PropertiesChanged push. No session bus or mpv needed (mpv stays lazy).
 */
class MprisServiceTest {
    @Test
    fun exposesRootAndPlayerOverEmbeddedBus() {
        val address = TransportBuilder.createDynamicSession(TransportBuilder.getRegisteredBusTypes().first(), false)
        EmbeddedDBusDaemon(BusAddress.of("$address,listen=true")).use { daemon ->
            daemon.startInBackgroundAndWait(10_000)
            val controller = PlayerController()
            try {
                DBusConnectionBuilder.forAddress(BusAddress.of(address)).build().use { serviceConn ->
                    MprisService(serviceConn, controller)
                    DBusConnectionBuilder.forAddress(BusAddress.of(address)).build().use { clientConn ->
                        val unique = serviceConn.getUniqueName()
                        val root = clientConn.getRemoteObject(unique, MprisService.PATH, MprisRoot::class.java)
                        val player = clientConn.getRemoteObject(unique, MprisService.PATH, MprisPlayer::class.java)
                        val props = clientConn.getRemoteObject(unique, MprisService.PATH, Properties::class.java)

                        assertEquals("Metrolist", root.getIdentity())
                        assertEquals("Stopped", player.getPlaybackStatus())
                        assertTrue(player.isCanControl())

                        // org.freedesktop.DBus.Properties is served by the
                        // @DBusBoundProperty handler without implementing it.
                        val identity: String = props.Get("org.mpris.MediaPlayer2", "Identity")
                        assertEquals("Metrolist", identity)

                        val changed = CountDownLatch(1)
                        var pushedStatus: String? = null
                        clientConn.addSigHandler<DBusSignal>(
                            DBusMatchRuleBuilder.create().withInterface("org.freedesktop.DBus.Properties").build(),
                        ) { signal ->
                            if (signal is Properties.PropertiesChanged && signal.path == MprisService.PATH) {
                                val status = signal.propertiesChanged["PlaybackStatus"]?.value as? String
                                if (status != null) {
                                    pushedStatus = status
                                    changed.countDown()
                                }
                            }
                        }

                        controller.isPlaying.value = true
                        assertEquals("Playing", player.getPlaybackStatus())
                        assertTrue(changed.await(10, TimeUnit.SECONDS))
                        assertEquals("Playing", pushedStatus)

                        controller.isPlaying.value = false
                        assertEquals("Stopped", player.getPlaybackStatus())

                        // Transport calls must be safe with an empty queue.
                        player.playPause()
                        player.next()
                        player.previous()
                        player.stop()
                    }
                }
            } finally {
                controller.close()
            }
        }
    }
}
