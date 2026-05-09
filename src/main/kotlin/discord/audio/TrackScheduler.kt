package jp.simplespace.discord.voice

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class TrackScheduler(private val player: AudioPlayer) : AudioEventAdapter() {
    private val queue: BlockingQueue<AudioTrack> = LinkedBlockingQueue()

    /**
     * キューに次の曲を追加するか、キューに曲がない場合はすぐに再生します。
     *
     * @param track 再生またはキューに追加するトラック
     */
    fun queue(track: AudioTrack) {
        // noInterrupt を true に設定して startTrack を呼び出すと、現在何も再生されていない場合にのみトラックが再生されます。
        // もし何かが再生中の場合は、falseを返して何もしません。その場合、プレーヤーはすでに再生中であるため、
        // このトラックは代わりにキューに追加されます。
        if (!player.startTrack(track, true)) {
            queue.offer(track)
        }
    }

    /**
     * 次のトラックを再生し、現在再生中のトラックがある場合は停止します。
     */
    fun nextTrack() {
        // Start the next track, regardless of if something is already playing or not. In case queue was empty, we are
        // giving null to startTrack, which is a valid argument and will simply stop the player.
        player.startTrack(queue.poll(), false)
    }

    /**
     * キューをすべてクリアし、再生を停止します。
     */
    fun stopTracks() {
        queue.clear()
        player.stopTrack()
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        // Only start the next track if the end reason is suitable for it (FINISHED or LOAD_FAILED)
        if (endReason.mayStartNext) {
            nextTrack()
        }
    }
}
