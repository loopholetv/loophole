package io.github.loopholetv.data.repo

import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.GroupPrefsEntity
import io.github.loopholetv.data.db.addPlaylist
import io.github.loopholetv.data.db.channel
import io.github.loopholetv.data.db.channelScopeFromArg
import io.github.loopholetv.data.db.inMemoryDb
import io.github.loopholetv.data.db.insertChannels
import io.github.loopholetv.data.db.label
import io.github.loopholetv.data.db.loadAll
import io.github.loopholetv.data.db.ref
import io.github.loopholetv.data.db.toArg
import androidx.paging.PagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ScopedChannelsTest {
    private lateinit var db: AppDatabase
    private lateinit var scoped: ScopedChannels
    private lateinit var ch: Map<String, ChannelEntity>
    private var t = 0L
    private var p = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        scoped = ScopedChannels(db.channelDao(), db.favouriteDao())
        p = db.addPlaylist()
        ch = db.insertChannels(
            channel(p, "a", 1, groupTitle = "News"),
            channel(p, "b", 2, groupTitle = "Sport"),
            channel(p, "c", 3, groupTitle = "News"),
            channel(p, "d", 4, groupTitle = "Sport"),
            channel(p, "e", 5, groupTitle = "News"),
        )
    }

    @After fun tearDown() = db.close()

    private fun c(key: String) = ch.getValue(key)
    private suspend fun fav(vararg keys: String) = keys.forEach { db.favouriteDao().add(c(it).ref, Instant.ofEpochMilli(++t)) }

    private suspend fun setVisible(key: String, visible: Boolean) =
        db.channelDao().updateAll(listOf(db.channelDao().byId(c(key).id)!!.copy(visible = visible)))

    @Test fun scopeArgRoundTripsWithPlaylistId() {
        listOf(ChannelScope.Group(3, "News: UK & Sport"), ChannelScope.Favourites, ChannelScope.All).forEach {
            assertEquals(it, channelScopeFromArg(it.toArg()))
        }
        assertEquals("pg:3:News: UK & Sport", ChannelScope.Group(3, "News: UK & Sport").toArg())
        assertEquals(ChannelScope.Group(3, "News: UK & Sport"), channelScopeFromArg("pg:3:News: UK & Sport"))
        assertEquals("all", ChannelScope.All.toArg())
        assertEquals("fav", ChannelScope.Favourites.toArg())
        assertEquals(ChannelScope.All, channelScopeFromArg("pg:x:N"))
        assertEquals(ChannelScope.All, channelScopeFromArg("pg:3:"))
        assertEquals(ChannelScope.All, channelScopeFromArg("pg:3"))
        assertEquals(ChannelScope.All, channelScopeFromArg("pg:-1:N"))
        assertEquals(ChannelScope.Group(0, "News"), channelScopeFromArg("pg:0:News")) // legacy, resolved by title
        assertEquals(ChannelScope.All, channelScopeFromArg(null))
        assertEquals(ChannelScope.All, channelScopeFromArg("g:"))
        assertEquals(ChannelScope.All, channelScopeFromArg("zzz"))
        assertEquals("All channels", ChannelScope.All.label())
        assertEquals("Favourites", ChannelScope.Favourites.label())
        listOf("A+B 100%", "Ελληνικά", "g:", "pg:1:x", "fav", "all", " ").forEach { name ->
            val scope = ChannelScope.Group(7, name)
            assertEquals(name, scope, channelScopeFromArg(scope.toArg()))
        }
    }

    @Test fun legacyGroupArgResolvesByTitle() = runTest {
        val legacy = channelScopeFromArg("g:News")
        assertEquals(ChannelScope.Group(0, "News"), legacy)
        assertEquals(ChannelScope.Group(p, "News"), scoped.resolve(legacy))
        assertEquals(ChannelScope.All, scoped.resolve(channelScopeFromArg("g:Missing")))
        assertEquals(ChannelScope.Favourites, scoped.resolve(ChannelScope.Favourites))
        assertEquals(ChannelScope.All, scoped.resolve(ChannelScope.All))
        assertEquals(ChannelScope.Group(p, "Sport"), scoped.resolve(ChannelScope.Group(p, "Sport")))
        assertEquals(ChannelScope.All, scoped.resolve(ChannelScope.Group(p + 9, "Sport")))
        // A later playlist also has "News": the first playlist's visible group wins...
        val p2 = db.addPlaylist("Second")
        db.insertChannels(channel(p2, "n2", 1, groupTitle = "News", guideNumber = 6))
        assertEquals(ChannelScope.Group(p, "News"), scoped.resolve(legacy))
        // ...until every channel of it is hidden: then the next visible one, and its pg: scope is All.
        listOf("a", "c", "e").forEach { setVisible(it, false) }
        assertEquals(ChannelScope.Group(p2, "News"), scoped.resolve(legacy))
        assertEquals(ChannelScope.All, scoped.resolve(ChannelScope.Group(p, "News")))
        setVisible("c", true)
        assertEquals(ChannelScope.Group(p, "News"), scoped.resolve(ChannelScope.Group(p, "News")))
        assertEquals(ChannelScope.Group(p, "News"), scoped.resolve(legacy))
    }

    @Test fun labelUsesCustomGroupName() = runTest {
        db.groupPrefsDao().upsert(GroupPrefsEntity(p, "News", customName = "Headlines"))
        assertEquals("Headlines", scoped.label(ChannelScope.Group(p, "News")))
        assertEquals("Sport", scoped.label(ChannelScope.Group(p, "Sport")))
        assertEquals("Headlines", scoped.label(ChannelScope.Group(0, "News"))) // legacy: resolved first
        assertEquals("All channels", scoped.label(ChannelScope.All))
        assertEquals("Favourites", scoped.label(ChannelScope.Favourites))
    }

    @Test fun favouritesPagesAreInvalidatedWhenFavouritesChange() = runTest {
        val first = scoped.pages(ChannelScope.Favourites)
        assertEquals(emptyList<String>(), first.loadAll().map { it.key })
        fav("c")
        awaitInvalid(first)
        val second = scoped.pages(ChannelScope.Favourites)
        assertEquals(listOf("c"), second.loadAll().map { it.key })
        db.favouriteDao().remove(c("c").playlistId, "c")
        awaitInvalid(second)
        assertEquals(emptyList<String>(), scoped.pages(ChannelScope.Favourites).loadAll().map { it.key })
    }

    /** Room invalidates on its own executor: wait in real time, not the test's virtual time. */
    private suspend fun awaitInvalid(source: PagingSource<*, *>) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!source.invalid) delay(10) }
    }

    @Test fun favouritesScopeZapsInFavouriteOrder() = runTest {
        fav("d", "a", "e")
        val scope = ChannelScope.Favourites
        assertEquals(listOf("d", "a", "e"), scoped.pages(scope).loadAll().map { it.key })
        assertEquals(c("a"), scoped.neighbour(c("d"), scope, +1))
        assertEquals(c("d"), scoped.neighbour(c("e"), scope, +1)) // wraps
        assertEquals(c("e"), scoped.neighbour(c("d"), scope, -1))
        assertEquals(listOf("d", "a", "e"), scoped.around(c("a"), scope, 5).map { it.key })
    }

    @Test fun groupScopeDelegatesToChannelDao() = runTest {
        val news = ChannelScope.Group(p, "News")
        assertEquals(listOf("a", "c", "e"), scoped.pages(news).loadAll().map { it.key })
        assertEquals(listOf("a", "b", "c", "d", "e"), scoped.pages(ChannelScope.All).loadAll().map { it.key })
        assertEquals(c("c"), scoped.neighbour(c("a"), news, +1))
        assertEquals(c("a"), scoped.neighbour(c("e"), news, +1))
        assertEquals(c("b"), scoped.neighbour(c("a"), ChannelScope.All, +1))
        assertEquals(listOf("a", "c", "e"), scoped.around(c("c"), news, 1).map { it.key })
        assertEquals(listOf("b", "c", "d"), scoped.around(c("c"), ChannelScope.All, 1).map { it.key })
        // A legacy scope zaps like its resolved group.
        assertEquals(c("c"), scoped.neighbour(c("a"), ChannelScope.Group(0, "News"), +1))
    }

    @Test fun containsChecksFavouriteMembershipAndGroup() = runTest {
        fav("b")
        assertTrue(scoped.contains(c("a"), ChannelScope.All))
        assertTrue(scoped.contains(c("a"), ChannelScope.Group(p, "News")))
        assertFalse(scoped.contains(c("a"), ChannelScope.Group(p + 1, "News")))
        assertFalse(scoped.contains(c("b"), ChannelScope.Group(p, "News")))
        assertTrue(scoped.contains(c("b"), ChannelScope.Favourites))
        assertFalse(scoped.contains(c("a"), ChannelScope.Favourites))
    }
}
