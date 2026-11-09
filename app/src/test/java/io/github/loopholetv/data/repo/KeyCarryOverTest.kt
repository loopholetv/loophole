package io.github.loopholetv.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCarryOverTest {
    private fun k(key: String, name: String, group: String = "G") = KeyNameGroup(key, name, group)

    @Test fun uniqueNameAndGroupMapped() {
        val old = listOf(k("u1/1", "One"), k("u1/2", "Two"), k("u1/3", "Three", "News"))
        val new = listOf(k("u2/1", "One"), k("u2/2", "Two"), k("u2/3", "Three", "News"))
        assertEquals(mapOf("u1/1" to "u2/1", "u1/2" to "u2/2", "u1/3" to "u2/3"), carryOverPlan(old, new))
    }

    @Test fun ambiguousNamesSkipped() {
        // "Dup" twice in the old list, "Twin" twice in the new one: neither can be paired safely.
        val old = listOf(k("a", "Dup"), k("b", "Dup"), k("c", "Twin"), k("d", "Solo"))
        val new = listOf(k("a2", "Dup"), k("c2", "Twin"), k("c3", "Twin"), k("d2", "Solo"))
        assertEquals(mapOf("d" to "d2"), carryOverPlan(old, new))
    }

    @Test fun sameNameInAnotherGroupIsNotTheSameChannel() {
        val old = listOf(k("a", "One", "News"))
        val new = listOf(k("b", "One", "Sport"))
        assertEquals(emptyMap<String, String>(), carryOverPlan(old, new))
    }

    @Test fun unchangedKeysIgnored() {
        // "k1" stays; only the vanished key moves.
        val old = listOf(k("k1", "One"), k("k2", "Two"))
        val new = listOf(k("k1", "One"), k("k2b", "Two"))
        assertEquals(mapOf("k2" to "k2b"), carryOverPlan(old, new))
        assertEquals(emptyMap<String, String>(), carryOverPlan(old, old))
        // A new key that already existed before is never a target, and a kept key never a source.
        val before = listOf(k("x", "A"), k("y", "B"))
        assertEquals(emptyMap<String, String>(), carryOverPlan(before, listOf(k("x", "B"), k("y", "A"))))
    }
}
