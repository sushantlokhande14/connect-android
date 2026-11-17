# Screen-load benchmark

What it measures: the time from "the discovery screen asks for its data" to "the data is ready to render", for five ways of loading it, on the same simulated network.

What it does not measure: a real network, a real device, or rendering. The server is simulated. The numbers are a controlled comparison of loading strategies under stated latency assumptions, and should be read as that.

## Run it

```bash
./gradlew :data:testDebugUnitTest -Pbenchmark            # 30 runs per arm
./gradlew :data:testDebugUnitTest -Pbenchmark -PbenchmarkRuns=100
```

Output goes to `build/benchmark/screen_load.md` and `screen_load.json` (every sample). The copy in [benchmark/](benchmark/) is the run the README quotes. The benchmark is excluded from the normal test task: it takes about nine minutes and produces a report, not a verdict.

## What is real and what is simulated

Real: Retrofit, OkHttp's interceptor chain including the auth interceptor, kotlinx.serialization, the repositories, Room (in memory, under Robolectric), and the production `LoadDiscoveryScreen` use case.

Simulated: the server. Requests are answered in process by the fake backend, which sleeps for a latency drawn from a log-normal distribution (sigma 0.25) around a per-endpoint median:

| Network | session | me | me/preferences | discovery | me/quota | conversations/unread-count |
|---|---:|---:|---:|---:|---:|---:|
| wifi | 59 | 81 | 63 | 189 | 54 | 72 |
| lte | 130 | 180 | 140 | 420 | 120 | 160 |
| slow-3g | 390 | 540 | 420 | 1260 | 360 | 480 |

These medians are assumptions: a round trip plus server time, with the discovery query the most expensive call. Change them and the percentages change. In particular the headline reduction is close to `1 - slowest / sum`, so a screen whose slowest request dominates more would gain less.

## The five arms

| Arm | What it does |
|---|---|
| baseline | One request at a time, nothing cached, and a session probe before the load. The naive version of the screen. |
| no probe | The baseline without the probe. The probe is redundant: an expired token is already handled when a request gets a 401. |
| concurrent | The production loader on a first visit, with nothing cached. |
| revisit | The production loader returning after more than a minute. Profile and filters come from the cache; the deck's one-minute lifetime has passed, so it is refetched. |
| quick revisit | The production loader returning within a minute. The deck is cached too. |

The naive loader exists only in the benchmark's source. It is a comparison point, not an earlier version of the app.

## Fairness

- **Paired runs.** Every arm of a run faces the same network. Each endpoint draws its latency from its own random stream, and the streams are reseeded with the run's seed before each arm, so "the deck request" costs the same in all five arms of that run.
- **Independent runs.** Each run has a different seed and a fresh harness with empty caches, so the percentiles describe a distribution rather than one draw.
- **No socket.** The requests do not cross a loopback socket. An earlier version did, through MockWebServer, which writes response headers and body as two TCP segments; on loopback that costs a delayed ACK of about 44 ms per response. A sequential loader pays it once per request and a concurrent one pays it once in total, so it inflated the baseline by roughly a quarter of a second and flattered the result.
- **Warm-up.** Three runs are discarded before measuring.

## Result

30 runs per arm. Milliseconds.

| Network | Loader | Requests | Median | Mean | p90 | vs baseline | vs no probe |
|---|---|---:|---:|---:|---:|---:|---:|
| wifi | baseline | 6 | 530 | 545 | 618 | | |
| wifi | no probe | 5 | 466 | 487 | 561 | -12.0% | |
| wifi | concurrent | 5 | 195 | 201 | 251 | -63.3% | -58.3% |
| wifi | revisit | 3 | 195 | 201 | 250 | -63.3% | -58.3% |
| wifi | quick revisit | 2 | 77 | 80 | 108 | -85.4% | -83.4% |
| lte | baseline | 6 | 1172 | 1205 | 1358 | | |
| lte | no probe | 5 | 1034 | 1078 | 1240 | -11.7% | |
| lte | concurrent | 5 | 430 | 443 | 550 | -63.3% | -58.5% |
| lte | revisit | 3 | 429 | 443 | 550 | -63.4% | -58.5% |
| lte | quick revisit | 2 | 170 | 176 | 239 | -85.5% | -83.6% |
| slow-3g | baseline | 6 | 3500 | 3592 | 4066 | | |
| slow-3g | no probe | 5 | 3081 | 3218 | 3709 | -12.0% | |
| slow-3g | concurrent | 5 | 1279 | 1320 | 1646 | -63.4% | -58.5% |
| slow-3g | revisit | 3 | 1280 | 1319 | 1646 | -63.4% | -58.5% |
| slow-3g | quick revisit | 2 | 503 | 522 | 711 | -85.6% | -83.7% |

## Reading it

- Removing the session probe is worth about 12%. It removes one request out of six.
- Running the five remaining requests together is worth another 58%. The load now takes as long as the deck request.
- Caching the profile and the filters is worth nothing in time on a revisit (429 vs 430 ms on LTE). It removes two requests, but neither was the slowest. Its value is in traffic, and in the offline fallback the repository tests cover.
- Caching the deck for a minute is what makes a quick return fast: only the two cheap requests remain.

## What the first version got wrong

The first run of this benchmark reported 1,694 ms for the LTE baseline and a 76% reduction. Two mistakes produced that:

1. The backend's random generator was created with the same seed in every run, so all 30 runs repeated one sequence of latency draws, and each arm consumed a different part of that sequence. The median and the 90th percentile were nearly identical, which is what gave it away.
2. The socket artefact described under Fairness.

Both were found by checking the result against what the latency model predicts (the baseline should be near the sum of the medians, 1,150 ms) instead of accepting a number because it was large.
