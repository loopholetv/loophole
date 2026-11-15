package io.github.loopholetv.phone

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import io.github.loopholetv.cast.net.HttpRequest
import io.github.loopholetv.cast.net.HttpResponse
import io.github.loopholetv.data.db.AppDatabase
import io.github.loopholetv.data.db.ChannelEntity
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.data.db.EditorGroupRow
import io.github.loopholetv.data.repo.ChannelEdits
import io.github.loopholetv.data.repo.FavouritesRepository
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * The phone page's channel and group routes: one playlist's live channels, paged, and the same
 * rename, hide, favourite and reorder edits as the TV's channel editor, through its repositories.
 * The list reads what the editor reads: hidden channels and groups too, in editor order.
 */
class ChannelsApi(
    private val db: AppDatabase,
    private val edits: ChannelEdits,
    private val favourites: FavouritesRepository,
) {
    fun register(server: PhoneServer) {
        server.api("GET", CHANNELS, handler = ::get)
        server.api("GET", GROUPS, handler = ::get)
        server.api("POST", CHANNELS, handler = ::post)
        server.api("POST", GROUPS, handler = ::post)
    }

    private suspend fun get(req: HttpRequest, res: HttpResponse) {
        when (req.path) {
            CHANNELS -> list(req, res)
            GROUPS -> groups(req, res)
            else -> res.sendError(404, NOT_FOUND)
        }
    }

    private suspend fun post(req: HttpRequest, res: HttpResponse) {
        val action = ACTIONS[req.path] ?: return res.sendError(404, NOT_FOUND)
        val body = readEdit(req) ?: return res.sendError(400, BAD_REQUEST)
        if (db.playlistDao().byId(body.playlist) == null) return res.sendError(404, NOT_FOUND)
        // A missing or mistyped field is a 400 before anything is looked up.
        val done = when (action) {
            Action.CHANNEL_RENAME -> {
                val name = body.name ?: return res.sendError(400, BAD_REQUEST)
                onChannel(body) { edits.rename(it.ref(), name); true }
            }
            Action.CHANNEL_HIDDEN -> {
                val hidden = body.flag ?: return res.sendError(400, BAD_REQUEST)
                onChannel(body) { edits.setHidden(it.ref(), hidden); true }
            }
            Action.CHANNEL_FAVOURITE -> {
                val favourite = body.flag ?: return res.sendError(400, BAD_REQUEST)
                onChannel(body) { setFavourite(it, favourite); true }
            }
            Action.CHANNEL_MOVE -> {
                val to = body.to ?: return res.sendError(400, BAD_REQUEST)
                onChannel(body) { edits.moveTo(it.ref(), to) }
            }
            Action.GROUP_RENAME -> {
                val name = body.name ?: return res.sendError(400, BAD_REQUEST)
                onGroup(body) { edits.renameGroup(body.playlist, it, name); true }
            }
            Action.GROUP_HIDDEN -> {
                val hidden = body.flag ?: return res.sendError(400, BAD_REQUEST)
                onGroup(body) { edits.setGroupHidden(body.playlist, it, hidden); true }
            }
            Action.GROUP_MOVE -> {
                val to = body.to ?: return res.sendError(400, BAD_REQUEST)
                onGroup(body) { edits.moveGroupTo(body.playlist, it, to) }
            }
        }
        when (done) {
            Done.OK -> res.sendJson(200) {
                beginObject()
                name("ok").value(true)
                endObject()
            }
            Done.GONE -> res.sendError(404, NOT_FOUND)
            Done.INVALID -> res.sendError(400, BAD_REQUEST)
        }
    }

    /** Runs [edit] on the channel [body] names; it returns false when the channel went away meanwhile. */
    private suspend fun onChannel(body: Edit, edit: suspend (ChannelEntity) -> Boolean): Done {
        val key = body.key ?: return Done.INVALID
        val channel = db.overlayDao().channelByKey(body.playlist, key) ?: return Done.GONE
        return if (edit(channel)) Done.OK else Done.GONE
    }

    /** Runs [edit] on the group [body] names; it returns false when the group went away meanwhile. */
    private suspend fun onGroup(body: Edit, edit: suspend (String) -> Boolean): Done {
        val title = body.title ?: return Done.INVALID
        if (groupRows(body.playlist).none { it.title == title }) return Done.GONE
        return if (edit(title)) Done.OK else Done.GONE
    }

    /** Adds or removes the favourite only when it isn't in that state yet. */
    private suspend fun setFavourite(channel: ChannelEntity, favourite: Boolean) {
        if (favourites.isFavourite(channel) != favourite) favourites.toggle(channel)
    }

    private suspend fun groups(req: HttpRequest, res: HttpResponse) {
        val id = playlistId(req, res) ?: return
        val rows = groupRows(id)
        res.sendJson(200) {
            beginArray()
            for (g in rows) {
                beginObject()
                name("title").value(g.title)
                name("label").value(g.label)
                name("hidden").value(g.hidden)
                name("renamed").value(g.renamed)
                name("count").value(g.count)
                endObject()
            }
            endArray()
        }
    }

    private suspend fun list(req: HttpRequest, res: HttpResponse) {
        val id = playlistId(req, res) ?: return
        val limit = (number(req, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (number(req, "offset") ?: 0).coerceAtLeast(0)
        val group = req.query["group"]?.takeIf { it.isNotEmpty() }
        val query = req.query["q"]?.trim()?.takeIf { it.isNotEmpty() }
        // One more than asked tells whether another page follows.
        val rows = db.channelDao().phoneChannels(id, group, query, limit + 1, offset)
        res.sendJson(200) {
            serializeNulls = true
            beginObject()
            name("rows").beginArray()
            for (r in rows.take(limit)) {
                val c = r.channel
                beginObject()
                name("key").value(c.key)
                name("name").value(r.customName ?: c.name)
                name("sourceName").value(c.name)
                name("group").value(c.groupTitle)
                name("hidden").value(r.hidden)
                name("favourite").value(r.favourite)
                endObject()
            }
            endArray()
            name("more").value(rows.size > limit)
            endObject()
        }
    }

    /** The `playlist` query value of an existing playlist; otherwise answers 400 or 404 and returns null. */
    private suspend fun playlistId(req: HttpRequest, res: HttpResponse): Long? {
        val id = req.query["playlist"]?.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toLongOrNull()
        if (id == null) {
            res.sendError(400, BAD_REQUEST)
            return null
        }
        if (db.playlistDao().byId(id) == null) {
            res.sendError(404, NOT_FOUND)
            return null
        }
        return id
    }

    /** A whole number in [name] (beyond the Int range it saturates); null when absent or not a number. */
    private fun number(req: HttpRequest, name: String): Int? =
        req.query[name]?.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    private suspend fun groupRows(playlistId: Long): List<EditorGroupRow> = db.channelDao().editorGroups(playlistId).first()

    private fun ChannelEntity.ref() = ChannelRef(playlistId, key)

    private enum class Action { CHANNEL_RENAME, CHANNEL_HIDDEN, CHANNEL_FAVOURITE, CHANNEL_MOVE, GROUP_RENAME, GROUP_HIDDEN, GROUP_MOVE }

    private enum class Done { OK, GONE, INVALID }

    /** The fields a POST may carry; absent or mistyped ones are null. */
    private class Edit(val playlist: Long, val key: String?, val title: String?, val name: String?, val flag: Boolean?, val to: Int?)

    private companion object {
        const val CHANNELS = "/api/channels"
        const val GROUPS = "/api/groups"
        const val NOT_FOUND = "not_found"
        const val BAD_REQUEST = "bad_request"
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        val ACTIONS = mapOf(
            "$CHANNELS/rename" to Action.CHANNEL_RENAME,
            "$CHANNELS/hidden" to Action.CHANNEL_HIDDEN,
            "$CHANNELS/favourite" to Action.CHANNEL_FAVOURITE,
            "$CHANNELS/move" to Action.CHANNEL_MOVE,
            "$GROUPS/rename" to Action.GROUP_RENAME,
            "$GROUPS/hidden" to Action.GROUP_HIDDEN,
            "$GROUPS/move" to Action.GROUP_MOVE,
        )
    }

    /** `{playlist, key|title, name|hidden|favourite|to}`; null unless it is an object with a numeric `playlist`. */
    private fun readEdit(req: HttpRequest): Edit? = try {
        req.jsonBody().use { r ->
            var playlist: Long? = null
            var key: String? = null
            var title: String? = null
            var name: String? = null
            var flag: Boolean? = null
            var to: Int? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "playlist" -> playlist = integer(r)
                    "key" -> key = text(r)
                    "title" -> title = text(r)
                    "name" -> name = text(r)
                    "hidden", "favourite" -> flag = if (r.peek() == JsonReader.Token.BOOLEAN) r.nextBoolean() else r.skipValue().let { null }
                    "to" -> to = integer(r)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            playlist?.let { Edit(it, key, title, name, flag, to) }
        }
    } catch (_: IOException) {
        null
    } catch (_: JsonDataException) {
        null
    }

    private fun text(r: JsonReader): String? =
        if (r.peek() == JsonReader.Token.STRING) r.nextString() else r.skipValue().let { null }

    private fun integer(r: JsonReader): Long? =
        if (r.peek() == JsonReader.Token.NUMBER) r.nextDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong() else r.skipValue().let { null }
}
