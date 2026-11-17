package app.connect.data.network

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Endpoints that run without a session. Built on a client that has no auth interceptor. */
interface AuthApi {
    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): SessionDto

    @POST("v1/auth/signup")
    suspend fun signUp(@Body body: SignUpRequest): SessionDto

    /** Blocking on purpose: it is called from inside an OkHttp interceptor, which is not a coroutine. */
    @POST("v1/auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<SessionDto>
}

interface ConnectApi {
    @POST("v1/auth/logout")
    suspend fun logout(): Response<Unit>

    /** Session probe. Production code never calls it; the benchmark's baseline loader does. */
    @GET("v1/session")
    suspend fun session(): Response<Unit>

    @GET("v1/me")
    suspend fun me(): ProfileDto

    @PATCH("v1/me")
    suspend fun updateMe(@Body patch: ProfilePatch): ProfileDto

    @GET("v1/me/preferences")
    suspend fun preferences(): PreferencesDto

    @PUT("v1/me/preferences")
    suspend fun updatePreferences(@Body body: PreferencesDto): PreferencesDto

    @GET("v1/me/quota")
    suspend fun quota(): QuotaDto

    @GET("v1/me/pushes")
    suspend fun pushes(): PushesDto

    @POST("v1/devices")
    suspend fun registerDevice(@Body body: DeviceRequest): Response<Unit>

    @GET("v1/profiles/{id}")
    suspend fun profile(@Path("id") id: String): ProfileDto

    @GET("v1/discovery")
    suspend fun discovery(@Query("cursor") cursor: String?, @Query("limit") limit: Int = 10): DeckDto

    @POST("v1/swipes")
    suspend fun swipe(@Body body: SwipeRequest): SwipeDto

    @GET("v1/conversations")
    suspend fun conversations(): ConversationsDto

    @GET("v1/conversations/unread-count")
    suspend fun unreadCount(): UnreadDto

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(@Path("id") conversationId: String): MessagesDto

    @POST("v1/conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") conversationId: String, @Body body: SendMessageRequest): MessageDto

    @POST("v1/conversations/{id}/read")
    suspend fun markRead(@Path("id") conversationId: String): Response<Unit>
}
