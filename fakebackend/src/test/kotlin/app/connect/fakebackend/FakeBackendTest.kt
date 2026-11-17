package app.connect.fakebackend

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.random.Random

/** The backend is test infrastructure that other tests trust, so its own behaviour is pinned down here. */
class FakeBackendTest {
    private var now = 1_000_000L
    private val backend = FakeBackend(clock = { now })

    private fun json(response: FakeResponse): JsonObject = Json.parseToJsonElement(response.body).jsonObject

    private fun login(password: String = FakeBackend.DEMO_PASSWORD) = backend.handle(
        FakeRequest("POST", "/v1/auth/login", body = """{"email":"${FakeBackend.DEMO_EMAIL}","password":"$password"}"""),
    )

    private fun get(path: String, token: String) =
        backend.handle(FakeRequest("GET", path, headers = mapOf("Authorization" to "Bearer $token")))

    private fun post(path: String, token: String, body: String) =
        backend.handle(FakeRequest("POST", path, headers = mapOf("Authorization" to "Bearer $token"), body = body))

    private fun tokens(): Pair<String, String> {
        val session = json(login())
        return session.getValue("accessToken").jsonPrimitive.content to session.getValue("refreshToken").jsonPrimitive.content
    }

    @Test
    fun `ids in a path do not create new routes`() {
        assertThat(FakeBackend.routeOf(FakeRequest("get", "/v1/profiles/u_17"))).isEqualTo("GET /v1/profiles/{id}")
        assertThat(FakeBackend.routeOf(FakeRequest("POST", "/v1/conversations/c_3/messages")))
            .isEqualTo("POST /v1/conversations/{id}/messages")
        assertThat(FakeBackend.routeOf(FakeRequest("GET", "/v1/conversations/unread-count")))
            .isEqualTo("GET /v1/conversations/unread-count")
    }

    @Test
    fun `wrong credentials and missing tokens are refused with distinct codes`() {
        assertThat(login(password = "nope").status).isEqualTo(401)
        assertThat(json(login(password = "nope")).getValue("code").jsonPrimitive.content).isEqualTo("invalid_credentials")

        val anonymous = backend.handle(FakeRequest("GET", "/v1/me"))
        assertThat(anonymous.status).isEqualTo(401)
        assertThat(json(anonymous).getValue("code").jsonPrimitive.content).isEqualTo("missing_token")
    }

    @Test
    fun `an access token works until it expires and then answers token_expired`() {
        val (access, _) = tokens()
        assertThat(get("/v1/me", access).status).isEqualTo(200)

        now += 16 * 60_000L

        val expired = get("/v1/me", access)
        assertThat(expired.status).isEqualTo(401)
        assertThat(json(expired).getValue("code").jsonPrimitive.content).isEqualTo("token_expired")
    }

    @Test
    fun `a refresh token works exactly once`() {
        val (_, refresh) = tokens()
        val body = """{"refreshToken":"$refresh"}"""

        val first = backend.handle(FakeRequest("POST", "/v1/auth/refresh", body = body))
        val second = backend.handle(FakeRequest("POST", "/v1/auth/refresh", body = body))

        assertThat(first.status).isEqualTo(200)
        assertThat(second.status).isEqualTo(401)
        val renewed = json(first).getValue("accessToken").jsonPrimitive.content
        assertThat(get("/v1/me", renewed).status).isEqualTo(200)
    }

    @Test
    fun `a message posted twice with one client id is stored once`() {
        val (access, _) = tokens()
        val conversation = backend.startConversation(FakeBackend.DEMO_EMAIL)
        val body = """{"clientId":"local-1","body":"hello"}"""

        val first = post("/v1/conversations/$conversation/messages", access, body)
        val second = post("/v1/conversations/$conversation/messages", access, body)

        assertThat(first.status).isEqualTo(201)
        assertThat(second.status).isEqualTo(200)
        assertThat(json(second).getValue("id")).isEqualTo(json(first).getValue("id"))
        assertThat(backend.storedMessageBodies(conversation)).containsExactly("hello")
    }

    @Test
    fun `another account cannot read a conversation it does not own`() {
        val conversation = backend.startConversation(FakeBackend.DEMO_EMAIL)
        val signUp = backend.handle(
            FakeRequest(
                "POST",
                "/v1/auth/signup",
                body = """{"email":"other@example.com","password":"hunter2hunter2","displayName":"Other","age":30}""",
            ),
        )
        val otherToken = json(signUp).getValue("accessToken").jsonPrimitive.content

        assertThat(get("/v1/conversations/$conversation/messages", otherToken).status).isEqualTo(404)
    }

    @Test
    fun `the deck respects filters and never repeats a swiped person`() {
        val (access, _) = tokens()
        val firstPage = json(get("/v1/discovery", access)).getValue("candidates").jsonArray
        val target = firstPage.first().jsonObject.getValue("profile").jsonObject.getValue("id").jsonPrimitive.content

        post("/v1/swipes", access, """{"targetId":"$target","action":"pass"}""")

        val after = json(get("/v1/discovery", access)).getValue("candidates").jsonArray
        assertThat(after.map { it.jsonObject.getValue("profile").jsonObject.getValue("id").jsonPrimitive.content })
            .doesNotContain(target)
    }

    @Test
    fun `the same seed produces the same people`() {
        assertThat(SeedData.profiles(7)).isEqualTo(SeedData.profiles(7))
        assertThat(SeedData.profiles(7)).isNotEqualTo(SeedData.profiles(8))
    }

    @Test
    fun `every request is logged by route, including failed ones`() {
        val (access, _) = tokens()
        backend.clearRequestLog()
        backend.faults.fail("GET /v1/me", Fault.Http(500))

        get("/v1/me", access)
        get("/v1/me", access)
        get("/v1/profiles/u_3", access)

        assertThat(backend.requests()).containsExactly("GET /v1/me", "GET /v1/me", "GET /v1/profiles/{id}").inOrder()
        assertThat(backend.requestCount("GET /v1/me")).isEqualTo(2)
    }
}

class FaultPlanTest {
    private val plan = FaultPlan()

    @Test
    fun `a fault fires for the requested number of requests and then stops`() {
        plan.fail("GET /a", Fault.Http(503), times = 2)

        assertThat(plan.take("GET /a")).isEqualTo(Fault.Http(503))
        assertThat(plan.take("GET /b")).isNull()
        assertThat(plan.take("GET /a")).isEqualTo(Fault.Http(503))
        assertThat(plan.take("GET /a")).isNull()
    }

    @Test
    fun `faults queued for one route fire in order`() {
        plan.fail("GET /a", Fault.Timeout)
        plan.fail("GET /a", Fault.Disconnect)

        assertThat(listOf(plan.take("GET /a"), plan.take("GET /a"), plan.take("GET /a")))
            .containsExactly(Fault.Timeout, Fault.Disconnect, null).inOrder()
    }

    @Test
    fun `a fault set until cleared keeps firing until it is cleared`() {
        plan.failUntilCleared("GET /a", Fault.Disconnect)

        repeat(50) { assertThat(plan.take("GET /a")).isEqualTo(Fault.Disconnect) }
        plan.clear("GET /a")

        assertThat(plan.take("GET /a")).isNull()
    }
}

class LatencyTest {
    @Test
    fun `samples are positive and repeatable for a seed`() {
        val latency = Latency(medianMillis = 200.0, sigma = 0.25)

        val first = Random(3).let { random -> List(200) { latency.sample(random) } }
        val second = Random(3).let { random -> List(200) { latency.sample(random) } }

        assertThat(first).isEqualTo(second)
        assertThat(first.min()).isAtLeast(1)
    }

    @Test
    fun `the sample median is close to the configured median`() {
        val random = Random(11)
        val samples = List(5_000) { Latency(medianMillis = 200.0, sigma = 0.25).sample(random) }.sorted()

        assertThat(samples[samples.size / 2].toDouble()).isWithin(15.0).of(200.0)
    }

    @Test
    fun `the instant profile adds no delay and scaling multiplies the median`() {
        assertThat(NetworkProfile.INSTANT.sample("GET /v1/me", Random(1))).isEqualTo(0)
        assertThat(NetworkProfile.SLOW_3G.latencyFor("GET /v1/discovery").medianMillis)
            .isWithin(0.001).of(NetworkProfile.LTE.latencyFor("GET /v1/discovery").medianMillis * 3)
    }
}
