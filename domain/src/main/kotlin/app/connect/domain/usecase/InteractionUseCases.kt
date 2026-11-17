package app.connect.domain.usecase

import app.connect.domain.diagnostics.Analytics
import app.connect.domain.diagnostics.AnalyticsEvent
import app.connect.domain.model.AppError
import app.connect.domain.model.ConversationId
import app.connect.domain.model.Message
import app.connect.domain.model.Outcome
import app.connect.domain.model.Profile
import app.connect.domain.model.ProfileUpdate
import app.connect.domain.model.SwipeAction
import app.connect.domain.model.SwipeResult
import app.connect.domain.model.UserId
import app.connect.domain.repository.ChatRepository
import app.connect.domain.repository.DiscoveryRepository
import app.connect.domain.repository.ProfileRepository
import javax.inject.Inject

class SwipeOnCard @Inject constructor(
    private val discovery: DiscoveryRepository,
    private val analytics: Analytics,
) {
    suspend operator fun invoke(target: UserId, action: SwipeAction): Outcome<SwipeResult> {
        val result = discovery.swipe(target, action)
        if (result is Outcome.Success) {
            analytics.track(AnalyticsEvent.SwipePerformed(action.name.lowercase(), result.value.matched))
        }
        return result
    }
}

class SendMessage @Inject constructor(
    private val chat: ChatRepository,
    private val analytics: Analytics,
) {
    suspend operator fun invoke(conversationId: ConversationId, rawBody: String): Outcome<Message> {
        val body = rawBody.trim()
        if (body.isEmpty()) return Outcome.Failure(AppError.Validation("body", "Write a message first"))
        if (body.length > MAX_LENGTH) {
            return Outcome.Failure(AppError.Validation("body", "Messages can be up to $MAX_LENGTH characters"))
        }
        val result = chat.send(conversationId, body)
        analytics.track(AnalyticsEvent.MessageSendCompleted(success = result is Outcome.Success, wasRetry = false))
        return result
    }

    suspend fun retry(clientId: String): Outcome<Message> {
        val result = chat.retry(clientId)
        analytics.track(AnalyticsEvent.MessageSendCompleted(success = result is Outcome.Success, wasRetry = true))
        return result
    }

    companion object {
        const val MAX_LENGTH = 1_000
    }
}

class UpdateMyProfile @Inject constructor(
    private val profiles: ProfileRepository,
) {
    suspend operator fun invoke(update: ProfileUpdate): Outcome<Profile> {
        update.displayName?.let { name -> CredentialRules.displayName(name)?.let { return Outcome.Failure(it) } }
        update.bio?.let { bio ->
            if (bio.length > MAX_BIO_LENGTH) {
                return Outcome.Failure(AppError.Validation("bio", "Keep your bio under $MAX_BIO_LENGTH characters"))
            }
        }
        val interests = update.interests?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct()
        if (interests != null && interests.size > MAX_INTERESTS) {
            return Outcome.Failure(AppError.Validation("interests", "Pick up to $MAX_INTERESTS interests"))
        }
        return profiles.updateMe(
            update.copy(displayName = update.displayName?.trim(), bio = update.bio?.trim(), interests = interests),
        )
    }

    companion object {
        const val MAX_BIO_LENGTH = 300
        const val MAX_INTERESTS = 8
    }
}
