package io.github.loopholetv.ui.cast

import io.github.loopholetv.cast.CastInvite
import io.github.loopholetv.cast.CastPeer
import io.github.loopholetv.cast.CastState
import io.github.loopholetv.cast.CastStatus
import io.github.loopholetv.cast.CastView
import io.github.loopholetv.cast.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeCast : CastView {
        override val state = MutableStateFlow<CastState>(CastState.Off)
        override val peers = MutableStateFlow<List<CastPeer>>(emptyList())
        override val invite = MutableStateFlow<CastInvite?>(null)
        override val messages = MutableSharedFlow<String>()
        val joined = mutableListOf<String>()
        val stopped = mutableListOf<StopReason>()
        override fun startCasting() {}
        override fun stopCasting(reason: StopReason) { stopped += reason }
        override fun join(peerId: String) { joined += peerId }
        override fun leave() {}
        override fun ignoreInvite() {}
    }

    private fun peer(id: String, name: String, status: CastStatus) = CastPeer(
        id = id, name = name, host = "192.168.1.20", port = 8000, status = status, accepts = true,
        castId = if (status == CastStatus.CASTING) "c-$id" else null,
        token = if (status == CastStatus.CASTING) "t-$id" else null,
        castStartedAtMs = if (status == CastStatus.CASTING) 1L else null,
        lastSeenMs = 0,
    )

    @Test
    fun tvsRowsThisTvFirstThenByName() {
        val cast = FakeCast()
        // Arrives unsorted: the rows still list this TV first, then the others by name.
        cast.peers.value = listOf(
            peer("b", "kitchen", CastStatus.IDLE),
            peer("a", "Bedroom", CastStatus.WATCHING),
            peer("c", "Living room", CastStatus.CASTING),
        )
        val vm = TvsViewModel(cast)

        val rows = vm.ui.value.rows
        assertEquals(TvRow.ThisTv(CastStatus.IDLE, canStop = false), rows.first())
        assertEquals(listOf("Bedroom", "kitchen", "Living room"), rows.drop(1).map { (it as TvRow.Peer).name })
        assertEquals(
            listOf("Watching", "Idle", "Casting"),
            rows.drop(1).map { statusLabel((it as TvRow.Peer).status) },
        )
        assertNull(vm.ui.value.emptyText)
    }

    @Test
    fun thisTvShowsWhatItDoes() {
        val cast = FakeCast()
        val playing = MutableStateFlow(false)
        val vm = TvsViewModel(cast, playing)
        assertEquals(TvRow.ThisTv(CastStatus.IDLE, canStop = false), vm.ui.value.rows.first())

        playing.value = true
        assertEquals(TvRow.ThisTv(CastStatus.WATCHING, canStop = false), vm.ui.value.rows.first())

        cast.state.value = CastState.Casting(followers = 2)
        assertEquals(TvRow.ThisTv(CastStatus.CASTING, canStop = true), vm.ui.value.rows.first())

        cast.state.value = CastState.Following(peer("c", "Living room", CastStatus.CASTING))
        assertEquals(TvRow.ThisTv(CastStatus.WATCHING, canStop = false), vm.ui.value.rows.first())
    }

    @Test
    fun okOnCastingPeerJoins() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("c", "Living room", CastStatus.CASTING))
        val vm = TvsViewModel(cast)

        val close = vm.onOk(vm.ui.value.rows[1])

        assertTrue("joining closes the panel", close)
        assertEquals(listOf("c"), cast.joined)
    }

    @Test
    fun okOnIdlePeerDoesNothing() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("a", "Bedroom", CastStatus.IDLE), peer("b", "Kitchen", CastStatus.WATCHING))
        val vm = TvsViewModel(cast)

        assertFalse(vm.onOk(vm.ui.value.rows[1]))
        assertFalse(vm.onOk(vm.ui.value.rows[2]))
        assertEquals(emptyList<String>(), cast.joined)
    }

    @Test
    fun okOnPeerThatStoppedMeanwhileDoesNothing() {
        val cast = FakeCast()
        cast.peers.value = listOf(peer("c", "Living room", CastStatus.CASTING))
        val vm = TvsViewModel(cast)
        val row = vm.ui.value.rows[1]

        // The row on screen is older than the list: what the TV does now decides.
        cast.peers.value = listOf(peer("c", "Living room", CastStatus.IDLE))

        assertFalse(vm.onOk(row))
        assertEquals(emptyList<String>(), cast.joined)
    }

    @Test
    fun emptyText() {
        val cast = FakeCast()
        val vm = TvsViewModel(cast)

        assertEquals("No other TVs found. Open Loophole on another TV on the same network.", vm.ui.value.emptyText)
        // This TV is listed even when no other TV is.
        assertEquals(listOf<TvRow>(TvRow.ThisTv(CastStatus.IDLE, canStop = false)), vm.ui.value.rows)

        cast.peers.value = listOf(peer("a", "Bedroom", CastStatus.IDLE))
        assertNull(vm.ui.value.emptyText)
    }

    @Test
    fun thisTvRowStopsCasting() {
        val cast = FakeCast()
        val vm = TvsViewModel(cast)

        // Not casting: OK on this TV does nothing.
        assertFalse(vm.onOk(vm.ui.value.rows.first()))
        assertEquals(emptyList<StopReason>(), cast.stopped)

        cast.state.value = CastState.Casting(followers = 1)
        val close = vm.onOk(vm.ui.value.rows.first())

        assertFalse("the panel stays open on this TV's row", close)
        assertEquals(listOf(StopReason.STOPPED), cast.stopped)
        assertEquals("Stop casting", STOP_CASTING_LABEL)
    }
}
