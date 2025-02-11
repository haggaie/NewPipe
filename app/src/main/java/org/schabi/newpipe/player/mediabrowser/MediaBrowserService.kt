package org.schabi.newpipe.player.mediabrowser

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService

class MediaBrowserService : MediaBrowserServiceCompat() {
    private val mBinder: IBinder = LocalBinder()
    private var mediaBrowserConnector: MediaBrowserConnector? = null
    private val disposables = CompositeDisposable()
    val sessionConnector: MediaSessionConnector?
        get() = mediaBrowserConnector?.getSessionConnector()

    override fun onCreate() {
        super.onCreate()
        mediaBrowserConnector = MediaBrowserConnector(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DEBUG) {
            Log.d(TAG, "destroy() called")
        }

        if (mediaBrowserConnector != null) {
            mediaBrowserConnector!!.release()
            mediaBrowserConnector = null
        }

        disposables.clear()
    }

    // MediaBrowserServiceCompat methods (they defer function calls to mediaBrowserConnector)
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? = mediaBrowserConnector!!.onGetRoot(clientPackageName, clientUid, rootHints)

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        val disposable =
            mediaBrowserConnector!!
                .onLoadChildren(parentId)
                .subscribe(
                    io.reactivex.rxjava3.functions.Consumer {
                        result.sendResult(
                            it,
                        )
                    },
                )
        disposables.add(disposable)
    }

    override fun onSearch(
        query: String,
        extras: Bundle,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        mediaBrowserConnector!!.onSearch(query, result)
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaBrowserService = this@MediaBrowserService
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Send MediaBrowserServiceCompat messages to the base class, while keeping the existing
        // custom binder PlayerService.LocalBinder interface for the existing messages.
        if (SERVICE_INTERFACE == intent?.action) {
            return super.onBind(intent)
        }
        return mBinder
    }

    companion object {
        private val TAG: String = PlayerService::class.java.simpleName
        private val DEBUG = Player.DEBUG
    }
}
