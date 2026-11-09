package io.github.loopholetv.ui.nav

import io.github.loopholetv.data.db.ChannelScope
import io.github.loopholetv.data.db.channelScopeFromArg
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartDestinationTest {
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
