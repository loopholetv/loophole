package io.github.loopholetv.ui.nav

import android.os.Bundle
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.channelScopeFromArg
import java.net.URLDecoder
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartDestinationTest {
    /** Navigates a graph holding the real player destination to [route]; returns its arguments. */
    private fun playerArgs(route: String): Bundle {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = "start") {
            composable("start") {}
            composable(Routes.PLAYER, arguments = Routes.playerArguments) {}
        }
        nav.navigate(route)
        val entry = nav.currentBackStackEntry!!
        assertEquals(Routes.PLAYER, entry.destination.route)
        return entry.arguments!!
    }

    @Test
    fun playerRouteWithArchiveFromParses() {
        val from = Instant.ofEpochSecond(1_700_000_000)
        val group = ChannelScope.Group(3, "News & Sport")

        assertEquals("player/42?archiveFrom=1700000000", Routes.player(42, ChannelScope.All, from))
        assertEquals(
            "player/42?scope=pg%3A3%3ANews%20%26%20Sport&archiveFrom=1700000000",
            Routes.player(42, group, from),
        )

        val all = playerArgs(Routes.player(42, ChannelScope.All, from))
        assertEquals(42L, all.getLong(Routes.ARG_CHANNEL_ID))
        assertEquals(ChannelScope.All, channelScopeFromArg(all.getString(Routes.ARG_SCOPE)))
        assertEquals(from, archiveFromArg(all.getLong(Routes.ARG_ARCHIVE_FROM)))

        val scoped = playerArgs(Routes.player(42, group, from))
        assertEquals(group, channelScopeFromArg(scoped.getString(Routes.ARG_SCOPE)))
        assertEquals(from, archiveFromArg(scoped.getLong(Routes.ARG_ARCHIVE_FROM)))
    }

    @Test
    fun oldPlayerRoutesStillParse() {
        // Without archiveFrom the player plays live, as before.
        assertEquals("player/42", Routes.player(42, ChannelScope.All))
        val plain = playerArgs("player/42")
        assertEquals(42L, plain.getLong(Routes.ARG_CHANNEL_ID))
        assertEquals(ChannelScope.All, channelScopeFromArg(plain.getString(Routes.ARG_SCOPE)))
        assertNull(archiveFromArg(plain.getLong(Routes.ARG_ARCHIVE_FROM, NO_ARCHIVE)))

        val fav = playerArgs(Routes.player(42, ChannelScope.Favourites))
        assertEquals(ChannelScope.Favourites, channelScopeFromArg(fav.getString(Routes.ARG_SCOPE)))
        assertNull(archiveFromArg(fav.getLong(Routes.ARG_ARCHIVE_FROM, NO_ARCHIVE)))
    }

    @Test
    fun archiveFromArgTreatsNegativeAsNone() {
        assertNull(archiveFromArg(NO_ARCHIVE))
        assertNull(archiveFromArg(null))
        assertEquals(Instant.EPOCH, archiveFromArg(0L))
    }

    @Test
    fun searchFromThePlayerReplacesIt() {
        // Back from Search then returns to the guide, not to the player.
        val options = searchFromPlayerOptions()
        assertEquals(Routes.PLAYER, options.popUpToRoute)
        assertTrue(options.isPopUpToInclusive())
        assertTrue(options.shouldLaunchSingleTop())
    }

    @Test
    fun noPlaylistStartsAtOnboarding() {
        assertEquals(Routes.ONBOARDING, startDestination(hasPlaylist = false))
    }

    @Test
    fun withPlaylistStartsAtGuide() {
        assertEquals(Routes.GUIDE, startDestination(hasPlaylist = true))
    }

    @Test
    fun playlistFormRoute() {
        assertEquals("playlist-form", Routes.playlistForm())
        assertEquals("playlist-form?edit=7", Routes.playlistForm(7))
        assertEquals("playlist-form?edit={edit}", Routes.PLAYLIST_FORM)
        // Navigation can't make a Long argument nullable; -1 means "add".
        assertEquals(null, playlistFormEditId(-1L))
        assertEquals(null, playlistFormEditId(null))
        assertEquals(7L, playlistFormEditId(7L))
    }

    @Test
    fun allOffStillStartsOnGuide() {
        // Playlists that are all turned off still count; the guide shows its message.
        assertEquals(Routes.GUIDE, startDestination(hasPlaylist = true))
        assertFalse(shouldGoToOnboarding(2, Routes.GUIDE))
        assertFalse(shouldGoToOnboarding(1, Routes.PLAYLIST_FORM))
        assertTrue(shouldGoToOnboarding(0, Routes.PLAYLIST_FORM))
    }

    @Test
    fun searchRouteConstant() {
        assertEquals("search", Routes.SEARCH)
    }

    @Test
    fun playerRouteEncodesScope() {
        assertEquals("player/42", Routes.player(42, ChannelScope.All))
        assertEquals("player/42?scope=pg%3A3%3ANews%20%26%20Sport", Routes.player(42, ChannelScope.Group(3, "News & Sport")))
        assertEquals("player/42?scope=fav", Routes.player(42, ChannelScope.Favourites))
    }

    @Test
    fun playerRouteRoundTripsAwkwardGroupNames() {
        listOf("A+B 100%", "Ελληνικά", "News & Sport", "g:odd?=/#").forEach { name ->
            val scope = ChannelScope.Group(3, name)
            val route = Routes.player(7, scope)
            assertTrue(route, route.startsWith("player/7?scope="))
            val query = route.substringAfter("?scope=")
            assertFalse("query must be fully encoded: $route", query.any { it in " +&#?/" })
            assertEquals(scope, channelScopeFromArg(URLDecoder.decode(query, "UTF-8")))
        }
    }

    @Test
    fun emptyPlaylistsLeaveEveryScreenExceptOnboarding() {
        assertTrue(shouldGoToOnboarding(0, Routes.GUIDE))
        assertTrue(shouldGoToOnboarding(0, Routes.SETTINGS))
        assertTrue(shouldGoToOnboarding(0, Routes.PLAYER))
        assertFalse(shouldGoToOnboarding(0, null))
        assertFalse(shouldGoToOnboarding(0, Routes.ONBOARDING))
    }

    @Test
    fun nonEmptyPlaylistsNeverGoToOnboarding() {
        assertFalse(shouldGoToOnboarding(1, Routes.GUIDE))
        assertFalse(shouldGoToOnboarding(3, Routes.SETTINGS))
        assertFalse(shouldGoToOnboarding(1, Routes.ONBOARDING))
    }
}
