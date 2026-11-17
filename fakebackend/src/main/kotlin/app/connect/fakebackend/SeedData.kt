package app.connect.fakebackend

import kotlin.random.Random

internal data class ProfileRecord(
    val id: String,
    var displayName: String,
    val age: Int,
    var bio: String,
    var interests: List<String>,
    val photoUrl: String?,
    val city: String,
    val distanceKm: Int,
)

/** Deterministic directory of people to discover. The same seed always produces the same people. */
internal object SeedData {
    private val names = listOf(
        "Maya", "Jonah", "Priya", "Leo", "Amara", "Tomas", "Ines", "Kai", "Noor", "Felix",
        "Zoe", "Ravi", "Elena", "Marcus", "Yuki", "Dalia", "Owen", "Sana", "Mateo", "Lila",
        "Arjun", "Hana", "Theo", "Nadia", "Luca", "Imani", "Soren", "Mei", "Andre", "Freya",
        "Diego", "Asha", "Emil", "Camila", "Idris", "Wren", "Pablo", "Tara", "Nico", "Esme",
    )
    private val interests = listOf(
        "climbing", "cooking", "film", "hiking", "chess", "running", "pottery", "jazz",
        "photography", "cycling", "board games", "surfing", "gardening", "coffee", "poetry", "yoga",
    )
    private val cities = listOf("San Jose", "Oakland", "San Francisco", "Palo Alto", "Berkeley", "Santa Cruz")
    private val openers = listOf(
        "Weekends are for", "Currently obsessed with", "Will talk for hours about",
        "Learning to love", "Probably late because of",
    )

    fun profiles(seed: Long, count: Int = names.size): List<ProfileRecord> {
        val random = Random(seed)
        return (0 until count).map { index ->
            val picked = interests.shuffled(random).take(3 + random.nextInt(3))
            ProfileRecord(
                id = "u_${index + 1}",
                displayName = names[index % names.size],
                age = 21 + random.nextInt(18),
                bio = "${openers[random.nextInt(openers.size)]} ${picked.first()}. Also ${picked[1]} and ${picked[2]}.",
                interests = picked,
                photoUrl = "https://picsum.photos/seed/connect-${index + 1}/600/800",
                city = cities[random.nextInt(cities.size)],
                distanceKm = 1 + random.nextInt(60),
            )
        }
    }

    val replies = listOf(
        "Ha, same here.",
        "That sounds great. When were you thinking?",
        "Tell me more about that.",
        "I was hoping you'd say that.",
        "Okay, now I'm curious.",
    )
}
