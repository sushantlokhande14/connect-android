package app.connect.data

import app.connect.data.local.ChatDao
import app.connect.data.local.MessageEntity
import app.connect.data.network.ApiCaller
import app.connect.data.network.ConnectApi
import app.connect.data.network.MessageDto
import app.connect.data.network.NetworkFactory
import app.connect.data.network.SendMessageRequest
import app.connect.data.repository.ChatRepositoryImpl
import app.connect.data.repository.IdGenerator
import app.connect.data.session.SessionState
import app.connect.data.session.SessionStore
import app.connect.domain.model.AppError
import app.connect.domain.model.MessageStatus
import app.connect.domain.model.Outcome
import app.connect.domain.model.errorOrNull
import app.connect.domain.model.getOrNull
import app.connect.testing.Covers
import app.connect.testing.FakeClock
import app.connect.testing.Fixtures
import app.connect.testing.RecordingBreadcrumbs
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/*
 * Tests with mocked collaborators. The harness-based tests show the layers
 * working together; these pin down a single class's decisions (which call,
 * in which order, mapped to what) without any I/O.
 */

class ApiCallerTest {
    private val breadcrumbs = RecordingBreadcrumbs()
    private val caller = ApiCaller(NetworkFactory.json(), breadcrumbs)

    private fun http(status: Int, body: String = "{}"): HttpException =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `a returned value is a success`() = runTest {
        assertThat(caller.call("x") { 42 }).isEqualTo(Outcome.Success(42))
        assertThat(breadcrumbs.snapshot()).isEmpty()
    }

    @Test
    fun `each transport failure maps to one error`() = runTest {
        val cases: List<Pair<Throwable, AppError>> = listOf(
            http(401) to AppError.Unauthorized,
            http(408) to AppError.Timeout,
            http(500) to AppError.Server(500),
            http(503) to AppError.Server(503),
            http(404, """{"code":"profile_not_found"}""") to AppError.Rejected(404, "profile_not_found"),
            http(422, "not json") to AppError.Rejected(422, null),
            SocketTimeoutException("slow") to AppError.Timeout,
            IOException("reset") to AppError.Offline,
            SerializationException("bad") to AppError.Unexpected("unreadable response"),
        )

        cases.forEach { (thrown, expected) ->
            assertThat(caller.call<Int>("x") { throw thrown }.errorOrNull()).isEqualTo(expected)
        }
    }

    @Test
    fun `a failure leaves a breadcrumb that names the call and not its payload`() = runTest {
        caller.call<Int>("GET /v1/me") { throw http(500, """{"code":"secret-detail"}""") }

        assertThat(breadcrumbs.snapshot().single().message).isEqualTo("GET /v1/me failed: Server")
    }

    @Test
    fun `cancellation is rethrown rather than reported as a failure`() {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { caller.call<Int>("x") { throw CancellationException("gone") } }
        }
    }

    @Test
    fun `an empty error answer is mapped by status`() = runTest {
        val result = caller.callEmpty("x") { Response.error(503, "".toResponseBody(null)) }

        assertThat(result.errorOrNull()).isEqualTo(AppError.Server(503))
    }
}

class ChatRepositoryMockedTest {
    private val api = mockk<ConnectApi>()
    private val dao = mockk<ChatDao>(relaxUnitFun = true)
    private val sessions = mockk<SessionStore> {
        every { state } returns MutableStateFlow(SessionState.Active(Fixtures.session))
        every { current() } returns Fixtures.session
    }
    private val clock = FakeClock(now = 5_000)
    private val repository = ChatRepositoryImpl(
        api = api,
        dao = dao,
        sessions = sessions,
        caller = ApiCaller(NetworkFactory.json(), RecordingBreadcrumbs()),
        clock = clock,
        ids = IdGenerator { "local-1" },
    )
    private val conversation = Fixtures.conversationId

    private fun entity(status: MessageStatus) = MessageEntity(
        clientId = "local-1",
        conversationId = conversation.value,
        senderId = Fixtures.meId.value,
        body = "hello",
        sentAtMillis = 5_000,
        status = status.name,
    )

    @Test
    @Covers("CHAT-01")
    fun `the message is written as pending before the request and as sent after it`() = runTest {
        coEvery { dao.touchConversation(any(), any(), any(), any()) } returns 1
        coEvery { api.sendMessage(conversation.value, SendMessageRequest("local-1", "hello")) } returns
            MessageDto("m_9", "local-1", conversation.value, Fixtures.meId.value, "hello", sentAt = 5_100)

        val result = repository.send(conversation, "hello")

        assertThat(result.getOrNull()?.status).isEqualTo(MessageStatus.SENT)
        coVerifyOrder {
            dao.upsertMessage(entity(MessageStatus.PENDING))
            api.sendMessage(conversation.value, SendMessageRequest("local-1", "hello"))
            dao.upsertMessage(entity(MessageStatus.SENT).copy(sentAtMillis = 5_100))
        }
    }

    @Test
    @Covers("CHAT-02")
    fun `a failed request marks the stored message failed`() = runTest {
        coEvery { dao.touchConversation(any(), any(), any(), any()) } returns 1
        coEvery { api.sendMessage(any(), any()) } throws IOException("no route")

        val result = repository.send(conversation, "hello")

        assertThat(result.errorOrNull()).isEqualTo(AppError.Offline)
        coVerifyOrder {
            dao.upsertMessage(entity(MessageStatus.PENDING))
            dao.upsertMessage(entity(MessageStatus.FAILED))
        }
    }

    @Test
    @Covers("CHAT-02")
    fun `a retry reuses the stored client id as the idempotency key`() = runTest {
        coEvery { dao.message("local-1") } returns entity(MessageStatus.FAILED)
        coEvery { api.sendMessage(any(), any()) } returns
            MessageDto("m_9", "local-1", conversation.value, Fixtures.meId.value, "hello", sentAt = 5_100)

        repository.retry("local-1")

        coVerify(exactly = 1) { api.sendMessage(conversation.value, SendMessageRequest("local-1", "hello")) }
    }

    @Test
    fun `retrying an unknown message fails without a request`() = runTest {
        coEvery { dao.message("nope") } returns null

        val result = repository.retry("nope")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 0) { api.sendMessage(any(), any()) }
    }

    @Test
    fun `sending without a session fails before touching the database`() = runTest {
        every { sessions.current() } returns null

        val result = repository.send(conversation, "hello")

        assertThat(result.errorOrNull()).isEqualTo(AppError.Unauthorized)
        coVerify(exactly = 0) { dao.upsertMessage(any()) }
    }
}
