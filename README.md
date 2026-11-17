# Connect

A native Android social discovery app in Kotlin and Jetpack Compose: create an account, set up a profile, swipe through people nearby, match, chat, get notified.

It is also a test bed. The app is complete enough that architecture decisions have consequences, and each decision here is backed by something you can run: a benchmark, a fault-injection test, or an end-to-end test that drives the real UI.

| | |
|---|---|
| Screen-load time, naive sequential load vs. this app's loader | **1,172 ms to 430 ms** median on a simulated LTE profile (-63%) |
| Automated tests | **213**, all passing, including 11 end-to-end flows through the real activity |
| Regression scenarios with a passing automated test | **26 of 30** (86.7%); the other four are listed with the reason they are manual |
| Setup needed to run it | None. The demo build carries its own backend. |

## Run it

```bash
./gradlew :app:installDemoDebug
```

Sign in with `demo@connect.app` / `connect123`, or create an account. The demo build needs no server and no Firebase project: the backend runs inside the app, plugged in at the bottom of the HTTP client, so everything above it is the production code path. A wrench icon on the "You" tab opens a diagnostics screen where you can slow the network down, fail the next load, expire the access token or end the session, and watch the app respond.

No Android SDK installed? The whole project builds and tests inside a container:

```bash
docker run -d --name connect-build -v connect-gradle:/root/.gradle -v connect-work:/work -v "$PWD:/src" ghcr.io/cirruslabs/android-sdk:35 sleep infinity
docker exec connect-build bash /src/tools/container-gradle.sh :app:assembleDemoDebug
```

## How it is put together

```text
            app            navigation, auth gate, push, demo/prod wiring
             |
   feature:auth  feature:discovery  feature:profile  feature:chat
             |        one ViewModel + one stateless screen each
          core:ui          theme, shared components, error wording
             |
           domain          models, repository interfaces, use cases     (pure Kotlin)
             ^
            data           Retrofit, Room, DataStore, repositories, diagnostics
```

`domain` depends on nothing but coroutines. Features depend on `domain` and `core:ui` and never on `data`; only `app` knows the data layer exists, and Hilt wires it in. A feature module cannot import Retrofit or Room even by accident, because they are not on its classpath.

There are two build flavors. `demo` binds an in-memory backend and a push simulator. `prod` binds a real base URL and Firebase Cloud Messaging. They differ in one small Hilt module each.

## Decisions, and what backs each one

**The discovery screen loads five things at once, and treats them differently.**
[`LoadDiscoveryScreen`](domain/src/main/kotlin/app/connect/domain/usecase/LoadDiscoveryScreen.kt) starts the viewer's profile, their filters, the first deck page, the like quota and the unread count together. None of them needs another's response to make its request, so the load takes as long as the slowest one, not the sum. Profile, filters and deck are required: the first to fail cancels the rest and fails the load, without waiting for slower requests. Quota and unread count are optional: if one fails, the screen is drawn without it. The unit tests assert this on virtual time: 400 ms concurrent, 840 ms sequential, and a failure at 120 ms returns at 120 ms.

**Identical reads in flight are collapsed into one.**
[`SingleFlight`](domain/src/main/kotlin/app/connect/domain/util/Concurrency.kt) makes concurrent callers for the same key share one execution. If the caller doing the work is cancelled, a waiting caller takes over rather than being cancelled with it.

**Token refresh is single-flight, inside an interceptor.**
When an access token expires, the discovery screen's five requests fail together. Refresh tokens are single-use, so two parallel refreshes would sign the user out. [`AuthInterceptor`](data/src/main/kotlin/app/connect/data/network/AuthInterceptor.kt) lets the first failing request refresh while the others wait, see the token has changed, and replay. A test fires five requests at an expired token and asserts exactly one refresh. It is an interceptor rather than an OkHttp `Authenticator` on purpose: an `Authenticator` never runs when a response is produced above the network layer, which is where the demo backend answers.

**Auth is a state, not a navigation call.**
There is no "go to login" anywhere. The session lives in one store; when it ends, by sign-out or because the server rejected the refresh token, the auth state changes and the signed-in UI leaves the composition. Both paths also wipe the user's cached data.

**Chat reads only from Room.**
The UI observes the database. Network results and pushes are written into it. That is why conversations are readable offline and why a message stays visible while sending, after failing, and after a restart.

**A message is never stored twice.**
The client generates the message id before the first attempt and sends it as an idempotency key. A send whose response was lost can be retried safely, and a later refresh repairs it without any retry.

**Analytics events are typed and checked before they leave the app.**
A screen cannot send a free-form string. Every event class is validated against the rules an analytics backend enforces (snake case, bounded sizes, primitive values), and a contract test fails the build when a new event class is not covered.

**A crash writes its own report.**
The uncaught-exception handler writes the exception and the last 50 breadcrumbs to disk synchronously, then hands over to the platform handler. Nothing asynchronous survives a dying process.

**Feature flags are validated before they are applied.**
A remote payload with an unknown key or a non-boolean value is rejected whole, with the reasons. One flag is a kill switch for the concurrent loader.

## Measured

The benchmark runs the real client stack (Retrofit, OkHttp's interceptor chain, kotlinx.serialization, Room) against the in-process backend with a latency model, 30 paired runs per arm. Full method and all three network profiles are in [docs/benchmark.md](docs/benchmark.md).

Simulated LTE, milliseconds:

| Loader | Requests | Median | p90 | vs. baseline |
|---|---:|---:|---:|---:|
| Baseline: one request at a time, a session probe first | 6 | 1,172 | 1,358 | |
| Without the redundant session probe | 5 | 1,034 | 1,240 | -11.7% |
| Concurrent, first visit | 5 | 430 | 550 | -63.3% |
| Revisit after a minute: profile and filters from cache | 3 | 429 | 550 | -63.4% |
| Quick revisit: deck cached too | 2 | 170 | 239 | -85.5% |

Two things the numbers say that I did not assume going in:

- Caching the profile and the filters removed two of five requests and **no time**. Once the requests are concurrent, the slowest one sets the pace, and that one was not cached. The cache earns its keep in request count and on quick returns, not on the first paint.
- The reduction is the same on Wi-Fi, LTE and slow 3G, because it is a ratio of the latency model: roughly one minus (slowest request / sum of requests).

This is a simulation. The result is exactly as trustworthy as the latency assumptions, which are printed next to it. It is not a measurement of a real network or of rendering.

## Tests

```bash
./gradlew :domain:test :fakebackend:test testDebugUnitTest :app:testDemoDebugUnitTest
python3 tools/scenario_coverage.py --results .
```

| Level | What it runs against | Count |
|---|---|---:|
| Use cases, concurrency helpers | Fake repositories, virtual time | 34 |
| Fake backend | Itself: it is infrastructure other tests trust | 15 |
| Data layer | Real Retrofit and OkHttp over a socket, real Room, real DataStore, with faults injected | 69 |
| ViewModels and screens | Fake repositories; screens rendered from a given state | 71 |
| App | The real activity, navigation, Hilt graph and data layer, driven through the UI | 24 |

The end-to-end tests live in one file, [`EndToEndTest.kt`](app/src/sharedTest/kotlin/app/connect/android/EndToEndTest.kt), compiled into two source sets. On every run it executes on the JVM under Robolectric in about half a minute. The CI workflow can also run the same file on an emulator, on demand.

"Coverage" here means behaviours, not lines. [docs/scenarios.toml](docs/scenarios.toml) lists 30 scenarios the app must keep getting right. Tests claim them with `@Covers("CHAT-02")`. A script joins the catalogue, the claims and the JUnit results into [docs/regression-matrix.md](docs/regression-matrix.md), counting a scenario only if a test that claims it actually passed. Four scenarios are checked by hand, and the matrix says why for each: a real process kill, real Firebase delivery, a real radio toggling, and a screen-reader walkthrough.

## What the tests found

Writing tests against the real stack turned up problems that reading the code had not:

- **The unread badge was invisible to screen readers.** A Material navigation item with a label hides its icon from accessibility services, and the badge sits on the icon. The count is now the tab's state description, and the end-to-end test asserts it that way.
- **A fast swipe could be decided from a stale position.** The drag offset was updated in a coroutine and read back at the end of the gesture. The finger position is now tracked synchronously.
- **OkHttp replays requests on its own.** When a pooled connection drops, OkHttp retries on a new one without telling the caller, and that includes POSTs. Four "offline" tests passed for the wrong reason until faults were made to persist. It is also the practical argument for the idempotency key: a test pins that a replayed send is stored once.
- **The first benchmark was wrong, twice.** Every run reused one random seed, so 30 samples were one draw repeated. And over a loopback socket, each response cost about 44 ms of delayed-ACK time, charged once per request to the sequential loaders only, which flattered the result. Both are fixed; the commit that fixes them says what the bad numbers were.

## What is not here

- A real backend. The API is specified by the fake backend and the Retrofit interfaces; nothing is deployed.
- Firebase configuration. The prod flavor compiles without `google-services.json`, and delivers no pushes until one is added.
- Encryption of tokens at rest. They are in app-private DataStore; a Keystore-wrapped key is the next step.
- A line-coverage figure. Only scenario coverage is measured.
- Paging beyond a cursor on the deck, image upload, typing indicators.

## Layout

```text
app/                 activity, navigation, push, diagnostics screen, flavor wiring
core/ui/             theme, components, user-facing error text
feature/*/           auth, discovery, profile, chat
domain/              models, repository interfaces, use cases
data/                network, database, session, repositories, diagnostics, Hilt modules
fakebackend/         in-memory API with a latency model and fault injection
testing/             fakes and rules shared by every module's tests
docs/                scenario catalogue, regression matrix, benchmark
tools/               containerised build, test summary, scenario coverage
```
