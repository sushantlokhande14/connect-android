package app.connect.data.network

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the HTTP stack. Hilt calls these in production and tests call them
 * directly, so both run the same client configuration.
 */
object NetworkFactory {
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    data class Timeouts(
        val connectMillis: Long = 10_000,
        val readMillis: Long = 15_000,
        val callMillis: Long = 30_000,
    )

    /**
     * @param transport interceptors that stand in for the network (the demo backend). They are added
     *   last so the auth interceptor and logging still run in front of them.
     */
    fun plainClient(
        transport: Set<Interceptor> = emptySet(),
        timeouts: Timeouts = Timeouts(),
        logging: Boolean = false,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
        .apply {
            if (logging) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    },
                )
            }
            transport.forEach(::addInterceptor)
        }
        .build()

    /** Shares the plain client's connection pool and dispatcher; only the interceptor chain differs. */
    fun authedClient(plain: OkHttpClient, auth: AuthInterceptor): OkHttpClient {
        val builder = plain.newBuilder()
        val existing = builder.interceptors().toList()
        builder.interceptors().clear()
        builder.addInterceptor(auth)
        existing.forEach(builder::addInterceptor)
        return builder.build()
    }

    fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
