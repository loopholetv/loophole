package io.github.loopholetv.data.repo

/** A channel's key with the name and group (as the source gives them) that identify it across a re-key. */
data class KeyNameGroup(val key: String, val name: String, val group: String)

/**
 * After a playlist's address or login changed, which old channel keys move to which new ones.
 *
 * For each old key that is absent from [new], the new key with the same `(name, group)` that is
 * absent from [old] — only when that pair occurs exactly once in [old] and exactly once in [new],
 * so two channels sharing a name are never guessed at. Unchanged keys are left alone.
 */
fun carryOverPlan(old: List<KeyNameGroup>, new: List<KeyNameGroup>): Map<String, String> {
    val oldKeys = old.mapTo(HashSet()) { it.key }
    val newKeys = new.mapTo(HashSet()) { it.key }
    val oldByPair = old.groupBy { it.name to it.group }
    val newByPair = new.groupBy { it.name to it.group }
    val plan = LinkedHashMap<String, String>()
    for ((pair, olds) in oldByPair) {
        val from = olds.singleOrNull() ?: continue
        val to = newByPair[pair]?.singleOrNull() ?: continue
        if (from.key in newKeys || to.key in oldKeys) continue
        plan[from.key] = to.key
    }
    return plan
}
