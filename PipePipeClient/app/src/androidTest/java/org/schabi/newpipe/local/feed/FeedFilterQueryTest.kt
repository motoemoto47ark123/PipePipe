package org.schabi.newpipe.local.feed

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Maybe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.TestDatabases.stream
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import java.time.OffsetDateTime

/** Checks that the feed's filters for watched, paid and live streams pick the right streams. */
@RunWith(AndroidJUnit4::class)
class FeedFilterQueryTest {
    private lateinit var database: AppDatabase
    private var groupId = 0L

    private val all = setOf("normal", "paid", "live", "audio live", "watched")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        val subscription = SubscriptionEntity()
        subscription.serviceId = 0
        subscription.url = "https://www.youtube.com/channel/pipepipe-tests"
        subscription.name = "PipePipe tests"
        val subscriptionId = database.subscriptionDAO().insert(subscription)

        val streams = database.streamDAO()
        val ids = listOf(
            streams.insert(stream("normal")),
            streams.insert(stream("paid", paid = true)),
            streams.insert(stream("live", StreamType.LIVE_STREAM)),
            streams.insert(stream("audio live", StreamType.AUDIO_LIVE_STREAM)),
            streams.insert(stream("watched"))
        )
        database.feedDAO().insertAll(ids.map { FeedEntity(it, subscriptionId) })

        // Watched to the end
        val watched = ids.last()
        database.streamHistoryDAO().insert(StreamHistoryEntity(watched, OffsetDateTime.now(), 1))
        database.streamStateDAO().insert(StreamStateEntity(watched, 600_000))

        groupId = database.feedGroupDAO()
            .insert(FeedGroupEntity(0, "Group", FeedGroupIcon.entries.first()))
        database.feedGroupDAO().insertSubscriptionsToGroup(
            listOf(FeedGroupSubscriptionEntity(groupId, subscriptionId))
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun titles(streams: Maybe<List<StreamWithState>>) =
        streams.blockingGet()!!.map { it.stream.title }.toSet()

    @Test
    fun filtersTheWholeFeed() {
        val feed = database.feedDAO()
        assertEquals(all, titles(feed.getAllStreams(true, true)))
        assertEquals(all - "paid", titles(feed.getAllStreams(false, true)))
        assertEquals(all - "live" - "audio live", titles(feed.getAllStreams(true, false)))
        assertEquals(setOf("normal", "watched"), titles(feed.getAllStreams(false, false)))

        assertEquals(all - "watched", titles(feed.getLiveOrNotPlayedStreams(true, true)))
        assertEquals(setOf("normal", "paid"), titles(feed.getLiveOrNotPlayedStreams(true, false)))
        assertEquals(
            setOf("normal", "live", "audio live"),
            titles(feed.getLiveOrNotPlayedStreams(false, true))
        )
        assertEquals(setOf("normal"), titles(feed.getLiveOrNotPlayedStreams(false, false)))
    }

    @Test
    fun filtersAGroup() {
        val feed = database.feedDAO()
        assertEquals(all, titles(feed.getAllStreamsForGroup(groupId, true, true)))
        assertEquals(all - "paid", titles(feed.getAllStreamsForGroup(groupId, false, true)))
        assertEquals(
            setOf("normal", "paid"),
            titles(feed.getLiveOrNotPlayedStreamsForGroup(groupId, true, false))
        )
        assertEquals(
            emptySet<String>(),
            titles(feed.getAllStreamsForGroup(groupId + 1, true, true))
        )
    }
}
