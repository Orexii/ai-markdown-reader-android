package com.orexii.aimarkdownreader.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.orexii.aimarkdownreader.App
import com.orexii.aimarkdownreader.MainActivity
import com.orexii.aimarkdownreader.reader.ReaderController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The reader as a Media3 player, so the system shows it in the notification, on the lock screen, on watches and
 * in the car, and Bluetooth buttons control it. Positions are seconds of text at 1×; the speed is the reading speed.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(looper: Looper, private val reader: ReaderController, scope: CoroutineScope) : SimpleBasePlayer(looper) {
    private val job: Job = scope.launch { reader.state.collect { invalidateState() } }

    override fun getState(): State {
        val s = reader.state.value
        val settings = reader.settings.settings.value
        val document = reader.document.value
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_RELEASE,
        ).build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(s.rate.toFloat()))
            .setSeekBackIncrementMs(settings.skipBack * 1000L)
            .setSeekForwardIncrementMs(settings.skipForward * 1000L)
        if (!s.loaded || document == null) return builder.setPlaybackState(Player.STATE_IDLE).setPlaylist(emptyList()).build()
        val metadata = MediaMetadata.Builder()
            .setTitle(document.title)
            .setSubtitle(s.section.ifEmpty { null })
            .setArtist(s.section.ifEmpty { "AI Markdown Reader" })
            .setAlbumTitle("AI Markdown Reader")
            .setIsPlayable(true)
            .build()
        val item = MediaItemData.Builder(document.uri)
            .setMediaItem(MediaItem.Builder().setMediaId(document.uri).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs((s.total * 1_000_000).toLong())
            .setIsSeekable(true)
            .build()
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(
                when {
                    s.finished -> Player.STATE_ENDED
                    s.playing && (s.waiting || !s.engineReady) -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setContentPositionMs { (reader.state.value.elapsed * 1000).toLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) reader.play() else reader.player.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> reader.command("skipBack")
            Player.COMMAND_SEEK_FORWARD -> reader.command("skipForward")
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> reader.command("nextParagraph")
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> reader.command("previousParagraph")
            else -> reader.player.seekTo(positionMs / 1000.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        reader.setRate(playbackParameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        reader.player.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        job.cancel()
        return Futures.immediateVoidFuture()
    }
}

/** Keeps reading in the background and owns the media session (notification, lock screen, headset buttons). */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: SessionPlayer

    private val previousParagraph = SessionCommand(COMMAND_PREVIOUS_PARAGRAPH, Bundle.EMPTY)
    private val nextParagraph = SessionCommand(COMMAND_NEXT_PARAGRAPH, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        player = SessionPlayer(mainLooper, app.reader, app.scope)
        val buttons = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName("Back ${app.settings.settings.value.skipBack} seconds").setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("Forward ${app.settings.settings.value.skipForward} seconds").setSlots(CommandButton.SLOT_FORWARD).build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setSessionCommand(previousParagraph)
                .setDisplayName("Previous paragraph").setSlots(CommandButton.SLOT_OVERFLOW).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setSessionCommand(nextParagraph)
                .setDisplayName("Next paragraph").setSlots(CommandButton.SLOT_OVERFLOW).build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(previousParagraph).add(nextParagraph).build(),
                        )
                        .setMediaButtonPreferences(buttons)
                        .build()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        COMMAND_PREVIOUS_PARAGRAPH -> app.reader.command("previousParagraph")
                        COMMAND_NEXT_PARAGRAPH -> app.reader.command("nextParagraph")
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Headset and keyboard previous/next track keys move by paragraph, as in the extension.
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (event.action != KeyEvent.ACTION_DOWN) return false
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> app.reader.command("nextParagraph")
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> app.reader.command("previousParagraph")
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> app.reader.command("skipForward")
                        KeyEvent.KEYCODE_MEDIA_REWIND -> app.reader.command("skipBack")
                        else -> return false
                    }
                    return true
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends the session; while reading, reading continues.
        if (!player.playWhenReady) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val COMMAND_PREVIOUS_PARAGRAPH = "com.orexii.aimarkdownreader.PREVIOUS_PARAGRAPH"
        const val COMMAND_NEXT_PARAGRAPH = "com.orexii.aimarkdownreader.NEXT_PARAGRAPH"
    }
}
