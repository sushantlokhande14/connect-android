package app.connect.fakebackend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * The Connect REST API, in memory.
 *
 * It behaves like a server rather than a canned-response table: sign-up
 * creates an account, tokens expire and rotate, swipes consume candidates,
 * likes can match, and message sends are idempotent on the client id. That is
 * what lets tests exercise real flows (expired token -> refresh -> replay)
 * instead of asserting against fixed JSON.
 */
class FakeBackend(
    seed: Long = 42,
    private val clock: () -> Long = System::currentTimeMillis,
    @Volatile var network: NetworkProfile = NetworkProfile.INSTANT,
    val faults: FaultPlan = FaultPlan(),
    private val accessTokenTtlMillis: Long = 15 * 60_000L,
    private val replyDelayMillis: Long = 2_500L,
) {
    private class Account(
        val userId: String,
        val email: String,
        val password: String,
        val profile: ProfileRecord,
        var minAge: Int = 18,
        var maxAge: Int = 60,
        var maxDistanceKm: Int = 50,
        var quotaRemaining: Int = 25,
        val swiped: MutableSet<String> = HashSet(),
        val likedBy: Set<String>,
        val pushes: ArrayDeque<JsonObject> = ArrayDeque(),
        val deviceTokens: MutableSet<String> = HashSet(),
    )

    private class TokenRecord(val userId: String, val expiresAt: Long)

    private class MessageRecord(val id: String, val clientId: String, val senderId: String, val body: String, val sentAt: Long)

    private class PendingReply(val dueAt: Long, val body: String)

    private class ConversationRecord(
        val id: String,
        val ownerId: String,
        val peer: ProfileRecord,
        val messages: MutableList<MessageRecord> = ArrayList(),
        var unread: Int = 0,
        val pendingReplies: MutableList<PendingReply> = ArrayList(),
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val random = Random(seed)
    private val directory = SeedData.profiles(seed)
    private val accountsByEmail = HashMap<String, Account>()
    private val accountsById = HashMap<String, Account>()
    private val accessTokens = HashMap<String, TokenRecord>()
    private val refreshTokens = HashMap<String, String>()
    private val conversations = LinkedHashMap<String, ConversationRecord>()
    private val log = ArrayList<String>()
    private var nextId = 1
    @Volatile var simulatePeerReplies: Boolean = true

    init {
        synchronized(lock) { createAccount(DEMO_EMAIL, DEMO_PASSWORD, "Sam", 27) }
    }

    // ---------------------------------------------------------------- test hooks

    /** Routes requested so far, in arrival order, e.g. "GET /v1/me". */
    fun requests(): List<String> = synchronized(lock) { log.toList() }

    fun requestCount(route: String): Int = synchronized(lock) { log.count { it == route } }

    fun clearRequestLog() = synchronized(lock) { log.clear() }

    /** Makes every access token issued so far answer 401 token_expired. Refresh tokens stay valid. */
    fun expireAccessTokens() = synchronized(lock) {
        val expired = accessTokens.mapValues { TokenRecord(it.value.userId, expiresAt = 0) }
        accessTokens.putAll(expired)
    }

    /** Makes the next refresh attempt fail, which is how a session ends for good. */
    fun revokeRefreshTokens() = synchronized(lock) { refreshTokens.clear() }

    /** Queues a message from the other person, as if they had just written it. */
    fun peerSends(conversationId: String, body: String) = synchronized(lock) {
        val conversation = conversations[conversationId] ?: error("No conversation $conversationId")
        deliverPeerMessage(conversation, body)
    }

    fun conversationIdsOf(email: String): List<String> = synchronized(lock) {
        val account = accountsByEmail[email] ?: return@synchronized emptyList()
        conversations.values.filter { it.ownerId == account.userId }.map { it.id }
    }

    /** Server-side copy of a conversation's message bodies, to assert what was actually stored. */
    fun storedMessageBodies(conversationId: String): List<String> = synchronized(lock) {
        conversations[conversationId]?.messages?.map { it.body }.orEmpty()
    }

    // ------------------------------------------------------------------ handling

    fun handle(request: FakeRequest): FakeResponse {
        val route = routeOf(request)
        val delay: Long
        val fault: Fault?
        synchronized(lock) {
            log += route
            fault = faults.take(route)
            delay = network.sample(route, random)
        }
        return when (fault) {
            is Fault.Timeout -> FakeResponse(status = 0, delayMillis = delay, transport = TransportFault.TIMEOUT)
            is Fault.Disconnect -> FakeResponse(status = 0, delayMillis = delay, transport = TransportFault.DISCONNECT)
            is Fault.LostResponse -> {
                synchronized(lock) { dispatch(route, request) }
                FakeResponse(status = 0, delayMillis = delay, transport = TransportFault.DISCONNECT)
            }
            is Fault.Http -> error(fault.status, fault.code).copy(delayMillis = delay)
            is Fault.Slow -> synchronized(lock) { dispatch(route, request) }.copy(delayMillis = delay + fault.extraMillis)
            null -> synchronized(lock) { dispatch(route, request) }.copy(delayMillis = delay)
        }
    }

    private fun dispatch(route: String, request: FakeRequest): FakeResponse {
        val body = request.body?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
                ?: return error(400, "malformed_json")
        } ?: JsonObject(emptyMap())

        when (route) {
            "POST /v1/auth/signup" -> return signUp(body)
            "POST /v1/auth/login" -> return login(body)
            "POST /v1/auth/refresh" -> return refresh(body)
        }

        val account = authenticate(request) ?: return error(401, tokenProblem(request))
        materialiseDueReplies(account)
        val segments = request.path.trim('/').split('/')

        return when (route) {
            "POST /v1/auth/logout" -> {
                accessTokens.entries.removeAll { it.value.userId == account.userId }
                refreshTokens.entries.removeAll { it.value == account.userId }
                FakeResponse(204)
            }
            "GET /v1/session" -> ok { put("userId", account.userId) }
            "GET /v1/me" -> ok(profileJson(account.profile))
            "PATCH /v1/me" -> {
                body["displayName"]?.jsonPrimitive?.contentOrNull?.let { account.profile.displayName = it }
                body["bio"]?.jsonPrimitive?.contentOrNull?.let { account.profile.bio = it }
                body["interests"]?.let { list -> account.profile.interests = list.jsonArray.map { it.jsonPrimitive.content } }
                ok(profileJson(account.profile))
            }
            "GET /v1/me/preferences" -> ok(preferencesJson(account))
            "PUT /v1/me/preferences" -> {
                val min = body["minAge"]?.jsonPrimitive?.intOrNull ?: account.minAge
                val max = body["maxAge"]?.jsonPrimitive?.intOrNull ?: account.maxAge
                val distance = body["maxDistanceKm"]?.jsonPrimitive?.intOrNull ?: account.maxDistanceKm
                if (min < 18 || min > max || distance <= 0) return error(422, "invalid_preferences")
                account.minAge = min
                account.maxAge = max
                account.maxDistanceKm = distance
                ok(preferencesJson(account))
            }
            "GET /v1/me/quota" -> ok {
                put("remaining", account.quotaRemaining)
                put("resetsAt", startOfNextDay())
            }
            "GET /v1/me/pushes" -> ok {
                put("pushes", buildJsonArray { while (account.pushes.isNotEmpty()) add(account.pushes.removeFirst()) })
            }
            "POST /v1/devices" -> {
                body["token"]?.jsonPrimitive?.contentOrNull?.let { account.deviceTokens += it }
                FakeResponse(204)
            }
            "GET /v1/profiles/{id}" -> {
                val profile = directory.firstOrNull { it.id == segments[2] }
                    ?: account.profile.takeIf { it.id == segments[2] }
                    ?: return error(404, "profile_not_found")
                ok(profileJson(profile))
            }
            "GET /v1/discovery" -> discovery(account, request)
            "POST /v1/swipes" -> swipe(account, body)
            "GET /v1/conversations" -> ok {
                put("conversations", buildJsonArray { conversationsOf(account).forEach { add(conversationJson(it)) } })
            }
            "GET /v1/conversations/unread-count" -> ok { put("count", conversationsOf(account).sumOf { it.unread }) }
            "GET /v1/conversations/{id}/messages" -> {
                val conversation = conversationOf(account, segments[2]) ?: return error(404, "conversation_not_found")
                ok { put("messages", buildJsonArray { conversation.messages.forEach { add(messageJson(conversation.id, it)) } }) }
            }
            "POST /v1/conversations/{id}/messages" -> {
                val conversation = conversationOf(account, segments[2]) ?: return error(404, "conversation_not_found")
                postMessage(account, conversation, body)
            }
            "POST /v1/conversations/{id}/read" -> {
                val conversation = conversationOf(account, segments[2]) ?: return error(404, "conversation_not_found")
                conversation.unread = 0
                FakeResponse(204)
            }
            else -> error(404, "no_such_route")
        }
    }

    // ---------------------------------------------------------------------- auth

    private fun signUp(body: JsonObject): FakeResponse {
        val email = body.string("email")?.lowercase() ?: return error(422, "email_required")
        val password = body.string("password") ?: return error(422, "password_required")
        val name = body.string("displayName") ?: return error(422, "display_name_required")
        val age = body["age"]?.jsonPrimitive?.intOrNull ?: return error(422, "age_required")
        if (age < 18) return error(422, "under_age")
        if (password.length < 8) return error(422, "weak_password")
        if (accountsByEmail.containsKey(email)) return error(409, "email_taken")
        val account = createAccount(email, password, name, age)
        return FakeResponse(201, sessionJson(account).toString())
    }

    private fun login(body: JsonObject): FakeResponse {
        val email = body.string("email")?.lowercase()
        val account = accountsByEmail[email]
        if (account == null || account.password != body.string("password")) return error(401, "invalid_credentials")
        return FakeResponse(200, sessionJson(account).toString())
    }

    private fun refresh(body: JsonObject): FakeResponse {
        val token = body.string("refreshToken")
        // Rotation: a refresh token works once.
        val userId = refreshTokens.remove(token) ?: return error(401, "invalid_refresh_token")
        val account = accountsById.getValue(userId)
        return FakeResponse(200, sessionJson(account).toString())
    }

    private fun createAccount(email: String, password: String, name: String, age: Int): Account {
        val userId = "u_acct_${nextId++}"
        val profile = ProfileRecord(
            id = userId,
            displayName = name,
            age = age,
            bio = "",
            interests = listOf("climbing", "cooking", "film"),
            photoUrl = null,
            city = "San Jose",
            distanceKm = 0,
        )
        val account = Account(
            userId = userId,
            email = email,
            password = password,
            profile = profile,
            // Every third person in the directory already likes a new account, so liking them back matches.
            likedBy = directory.filterIndexed { index, _ -> index % 3 == 0 }.map { it.id }.toSet(),
        )
        accountsByEmail[email] = account
        accountsById[userId] = account
        return account
    }

    private fun sessionJson(account: Account): JsonObject {
        val access = "at_${nextId++}"
        val refresh = "rt_${nextId++}"
        val expiresAt = clock() + accessTokenTtlMillis
        accessTokens[access] = TokenRecord(account.userId, expiresAt)
        refreshTokens[refresh] = account.userId
        return buildJsonObject {
            put("userId", account.userId)
            put("accessToken", access)
            put("refreshToken", refresh)
            put("expiresAt", expiresAt)
        }
    }

    private fun bearer(request: FakeRequest): String? =
        request.header("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    private fun authenticate(request: FakeRequest): Account? {
        val record = accessTokens[bearer(request)] ?: return null
        if (record.expiresAt <= clock()) return null
        return accountsById[record.userId]
    }

    private fun tokenProblem(request: FakeRequest): String {
        val token = bearer(request) ?: return "missing_token"
        return if (accessTokens.containsKey(token)) "token_expired" else "invalid_token"
    }

    // ----------------------------------------------------------------- discovery

    private fun discovery(account: Account, request: FakeRequest): FakeResponse {
        val limit = request.query["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        val offset = request.query["cursor"]?.toIntOrNull() ?: 0
        val eligible = directory.filter {
            it.id !in account.swiped &&
                it.age in account.minAge..account.maxAge &&
                it.distanceKm <= account.maxDistanceKm
        }
        val page = eligible.drop(offset).take(limit)
        val next = (offset + page.size).takeIf { it < eligible.size }
        return ok {
            put(
                "candidates",
                buildJsonArray {
                    page.forEach { profile ->
                        add(
                            buildJsonObject {
                                put("profile", profileJson(profile))
                                put("distanceKm", profile.distanceKm)
                            },
                        )
                    }
                },
            )
            if (next != null) put("nextCursor", next.toString())
        }
    }

    private fun swipe(account: Account, body: JsonObject): FakeResponse {
        val targetId = body.string("targetId") ?: return error(422, "target_required")
        val action = body.string("action") ?: return error(422, "action_required")
        val target = directory.firstOrNull { it.id == targetId } ?: return error(404, "profile_not_found")
        val isLike = when (action) {
            "like" -> true
            "pass" -> false
            else -> return error(422, "unknown_action")
        }
        // Swiping twice on the same person is answered the same way and costs nothing extra.
        val firstTime = account.swiped.add(targetId)
        if (isLike && firstTime) {
            if (account.quotaRemaining <= 0) {
                account.swiped.remove(targetId)
                return error(429, "like_quota_exhausted")
            }
            account.quotaRemaining--
        }
        val matched = isLike && targetId in account.likedBy
        var conversationId: String? = null
        if (matched) {
            val conversation = conversations.values.firstOrNull { it.ownerId == account.userId && it.peer.id == targetId }
                ?: ConversationRecord(id = "c_${nextId++}", ownerId = account.userId, peer = target).also {
                    conversations[it.id] = it
                    account.pushes += buildJsonObject {
                        put("type", "new_match")
                        put("conversationId", it.id)
                        put("peerId", target.id)
                        put("peerName", target.displayName)
                    }
                }
            conversationId = conversation.id
        }
        return ok {
            put("matched", matched)
            if (conversationId != null) put("conversationId", conversationId)
        }
    }

    // ---------------------------------------------------------------------- chat

    private fun conversationsOf(account: Account) =
        conversations.values.filter { it.ownerId == account.userId }
            .sortedByDescending { it.messages.lastOrNull()?.sentAt ?: 0L }

    private fun conversationOf(account: Account, id: String) =
        conversations[id]?.takeIf { it.ownerId == account.userId }

    private fun postMessage(account: Account, conversation: ConversationRecord, body: JsonObject): FakeResponse {
        val clientId = body.string("clientId") ?: return error(422, "client_id_required")
        val text = body.string("body")?.takeIf { it.isNotBlank() } ?: return error(422, "body_required")
        // Idempotent on clientId: a retried send returns the stored message instead of storing a second one.
        conversation.messages.firstOrNull { it.clientId == clientId }?.let {
            return FakeResponse(200, messageJson(conversation.id, it).toString())
        }
        val message = MessageRecord("m_${nextId++}", clientId, account.userId, text, clock())
        conversation.messages += message
        if (simulatePeerReplies) {
            conversation.pendingReplies += PendingReply(
                dueAt = clock() + replyDelayMillis,
                body = SeedData.replies[random.nextInt(SeedData.replies.size)],
            )
        }
        return FakeResponse(201, messageJson(conversation.id, message).toString())
    }

    private fun materialiseDueReplies(account: Account) {
        val now = clock()
        conversations.values.filter { it.ownerId == account.userId }.forEach { conversation ->
            val due = conversation.pendingReplies.filter { it.dueAt <= now }
            conversation.pendingReplies.removeAll(due)
            due.forEach { deliverPeerMessage(conversation, it.body) }
        }
    }

    private fun deliverPeerMessage(conversation: ConversationRecord, body: String) {
        val message = MessageRecord("m_${nextId++}", "srv_${nextId++}", conversation.peer.id, body, clock())
        conversation.messages += message
        conversation.unread++
        accountsById[conversation.ownerId]?.pushes?.addLast(
            buildJsonObject {
                put("type", "new_message")
                put("conversationId", conversation.id)
                put("clientId", message.clientId)
                put("senderId", conversation.peer.id)
                put("senderName", conversation.peer.displayName)
                put("body", body)
                put("sentAt", message.sentAt)
            },
        )
    }

    // ---------------------------------------------------------------------- json

    private fun profileJson(profile: ProfileRecord) = buildJsonObject {
        put("id", profile.id)
        put("displayName", profile.displayName)
        put("age", profile.age)
        put("bio", profile.bio)
        put("interests", JsonArray(profile.interests.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        profile.photoUrl?.let { put("photoUrl", it) }
        put("city", profile.city)
    }

    private fun preferencesJson(account: Account) = buildJsonObject {
        put("minAge", account.minAge)
        put("maxAge", account.maxAge)
        put("maxDistanceKm", account.maxDistanceKm)
    }

    private fun conversationJson(conversation: ConversationRecord) = buildJsonObject {
        val last = conversation.messages.lastOrNull()
        put("id", conversation.id)
        put("peerId", conversation.peer.id)
        put("peerName", conversation.peer.displayName)
        conversation.peer.photoUrl?.let { put("peerPhotoUrl", it) }
        last?.let { put("lastMessagePreview", it.body) }
        put("lastMessageAt", last?.sentAt ?: 0L)
        put("unreadCount", conversation.unread)
    }

    private fun messageJson(conversationId: String, message: MessageRecord) = buildJsonObject {
        put("id", message.id)
        put("clientId", message.clientId)
        put("conversationId", conversationId)
        put("senderId", message.senderId)
        put("body", message.body)
        put("sentAt", message.sentAt)
    }

    private fun ok(body: JsonObject) = FakeResponse(200, body.toString())

    private fun ok(build: JsonObjectBuilder.() -> Unit) = FakeResponse(200, buildJsonObject(build).toString())

    private fun error(status: Int, code: String) =
        FakeResponse(status, buildJsonObject { put("code", code) }.toString())

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun startOfNextDay(): Long {
        val day = 86_400_000L
        return (clock() / day + 1) * day
    }

    companion object {
        const val DEMO_EMAIL = "demo@connect.app"
        const val DEMO_PASSWORD = "connect123"

        private val ID_SEGMENT = Regex("^(u|c|m)_[A-Za-z0-9_]+$")

        /** "GET /v1/profiles/u_7" -> "GET /v1/profiles/{id}", so faults and latencies can be set per endpoint. */
        fun routeOf(request: FakeRequest): String {
            val path = request.path.trim('/').split('/').joinToString("/") { if (ID_SEGMENT.matches(it)) "{id}" else it }
            return "${request.method.uppercase()} /$path"
        }
    }
}
