/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * Part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.player

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.schabi.newpipe.player.mediabrowser.MediaBrowserService
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi
import org.schabi.newpipe.player.notification.NotificationPlayerUi
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.Objects
import java.util.function.Consumer

/**
 * One service for all players.
 */
class PlayerService : Service() {
    private var player: Player? = null

    private val mBinder: IBinder = LocalBinder(this)

    var runAfterConnected: (() -> Unit)? = null

    var mediaBrowserService: MediaBrowserService? = null
    private val mediaBrowserConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                className: ComponentName?,
                service: IBinder?,
            ) {
                val binder = service as MediaBrowserService.LocalBinder
                mediaBrowserService = binder.getService()
                runAfterConnected?.invoke()
            }

            override fun onServiceDisconnected(className: ComponentName?) {
                mediaBrowserService = null
            }
        }

    private val playerInitializedListeners = LinkedList<PlayerInitializedListener>()

    fun addPlayerInitializedListener(listener: PlayerInitializedListener) {
        playerInitializedListeners.push(listener)
        if (player != null) {
            listener.onPlayerInitialized(player!!)
        }
    }

    fun removePlayerInitializedListener(listener: PlayerInitializedListener) {
        playerInitializedListeners.remove(listener)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Service's LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate() {
        super.onCreate()

        if (DEBUG) {
            Log.d(TAG, "onCreate() called")
        }
        Localization.assureCorrectAppLanguage(this)
        ThemeHelper.setTheme(this)

        bindMediaBrowserService(this)
    }

    private fun initializePlayerIfNeeded() {
        if (player == null) {
            player = Player(this, mediaBrowserService!!)
            /*
            Create the player notification and start immediately the service in foreground,
            otherwise if nothing is played or initializing the player and its components (especially
            loading stream metadata) takes a lot of time, the app would crash on Android 8+ as the
            service would never be put in the foreground while we said to the system we would do so
             */
            player!!
                .UIs()
                .get<NotificationPlayerUi?>(NotificationPlayerUi::class.java)
                .ifPresent(Consumer { obj: NotificationPlayerUi? -> obj!!.createNotificationAndStartForeground() })

            playerInitializedListeners.forEach {
                it.onPlayerInitialized(player!!)
            }
        }
    }

    var bound = false

    private fun bindMediaBrowserService(context: Context) {
        if (DEBUG) {
            Log.d(TAG, "bindMediaBrowserService() called")
        }

        val serviceIntent = Intent(context, MediaBrowserService::class.java)
        bound =
            context.bindService(
                serviceIntent,
                mediaBrowserConnection,
                BIND_AUTO_CREATE,
            )
        if (!bound) {
            context.unbindService(mediaBrowserConnection)
        }
    }

    private fun unbindMediaBrowserService(context: Context) {
        if (DEBUG) {
            Log.d(TAG, "unbindMediaBrowserService() called")
        }

        if (bound) {
            context.unbindService(mediaBrowserConnection)
            bound = false
        }
    }

    override fun onStartCommand(
        intent: Intent,
        flags: Int,
        startId: Int,
    ): Int {
        if (DEBUG) {
            Log.d(
                TAG,
                (
                    "onStartCommand() called with: intent = [" + intent +
                        "], flags = [" + flags + "], startId = [" + startId + "]"
                    ),
            )
        }

        /*
        Be sure that the player notification is set and the service is started in foreground,
        otherwise, the app may crash on Android 8+ as the service would never be put in the
        foreground while we said to the system we would do so
        The service is always requested to be started in foreground, so always creating a
        notification if there is no one already and starting the service in foreground should
        not create any issues
        If the service is already started in foreground, requesting it to be started shouldn't
        do anything
         */
        if (player != null) {
            player!!
                .UIs()
                .get<NotificationPlayerUi?>(NotificationPlayerUi::class.java)
                .ifPresent(Consumer { obj: NotificationPlayerUi? -> obj!!.createNotificationAndStartForeground() })
        }

        if (Intent.ACTION_MEDIA_BUTTON == intent.getAction() &&
            (player == null || player!!.getPlayQueue() == null)
        ) {
            /*
            No need to process media button's actions if the player is not working, otherwise
            the player service would strangely start with nothing to play
            Stop the service in this case, which will be removed from the foreground and its
            notification cancelled in its destruction
             */
            stopSelf()
            return START_NOT_STICKY
        }

        runAfterConnected =

            fun() {
                initializePlayerIfNeeded()
                Objects.requireNonNull<Player?>(player).handleIntent(intent)
                player!!
                    .UIs()
                    .get<MediaSessionPlayerUi?>(MediaSessionPlayerUi::class.java)
                    .ifPresent(
                        Consumer { ui: MediaSessionPlayerUi? ->
                            ui!!.handleMediaButtonIntent(
                                intent,
                            )
                        },
                    )
            }
        if (mediaBrowserService != null) {
            runAfterConnected?.invoke()
            runAfterConnected = null
        }

        return START_NOT_STICKY
    }

    fun stopForImmediateReusing() {
        if (DEBUG) {
            Log.d(TAG, "stopForImmediateReusing() called")
        }

        if (player != null && !player!!.exoPlayerIsNull()) {
            // Releases wifi & cpu, disables keepScreenOn, etc.
            // We can't just pause the player here because it will make transition
            // from one stream to a new stream not smooth
            player!!.smoothStopForImmediateReusing()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (player != null && !player!!.videoPlayerSelected()) {
            return
        }
        onDestroy()
        // Unload from memory completely
        Runtime.getRuntime().halt(0)
    }

    override fun onDestroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called")
        }

        cleanup()
    }

    private fun cleanup() {
        if (player != null) {
            player!!.destroy()
            player = null
        }
        unbindMediaBrowserService(this)
    }

    fun stopService() {
        cleanup()
        stopSelf()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(AudioServiceLeakFix.preventLeakOf(base))
    }

    interface PlayerInitializedListener {
        fun onPlayerInitialized(player: Player)
    }

    override fun onBind(intent: Intent): IBinder = mBinder

    class LocalBinder internal constructor(
        playerService: PlayerService?,
    ) : Binder() {
        private val playerService: WeakReference<PlayerService?>

        init {
            this.playerService = WeakReference<PlayerService?>(playerService)
        }

        fun getService(): PlayerService? = playerService.get()

        fun getPlayer(): Player? = playerService.get()?.player
    }

    companion object {
        private val TAG: String = PlayerService::class.java.getSimpleName()
        private val DEBUG = Player.DEBUG
    }
}
