Simulated latency per endpoint: log-normal, sigma 0.25, with these medians in ms.

| Network | session | me | me/preferences | discovery | me/quota | conversations/unread-count |
|---|---:|---:|---:|---:|---:|---:|
| wifi | 59 | 81 | 63 | 189 | 54 | 72 |
| lte | 130 | 180 | 140 | 420 | 120 | 160 |
| slow-3g | 390 | 540 | 420 | 1260 | 360 | 480 |

30 runs per arm after 3 warm-up runs. Every arm of a run sees the same latency draws.

| Network | Loader | Requests | Median ms | Mean ms | p90 ms | vs baseline | vs no probe |
|---|---|---:|---:|---:|---:|---:|---:|
| wifi | baseline | 6 | 530 | 545 | 618 |  |  |
| wifi | no probe | 5 | 466 | 487 | 561 | -12.0% |  |
| wifi | concurrent | 5 | 195 | 201 | 251 | -63.3% | -58.3% |
| wifi | revisit | 3 | 195 | 201 | 250 | -63.3% | -58.3% |
| wifi | quick revisit | 2 | 77 | 80 | 108 | -85.4% | -83.4% |
| lte | baseline | 6 | 1172 | 1205 | 1358 |  |  |
| lte | no probe | 5 | 1034 | 1078 | 1240 | -11.7% |  |
| lte | concurrent | 5 | 430 | 443 | 550 | -63.3% | -58.5% |
| lte | revisit | 3 | 429 | 443 | 550 | -63.4% | -58.5% |
| lte | quick revisit | 2 | 170 | 176 | 239 | -85.5% | -83.6% |
| slow-3g | baseline | 6 | 3500 | 3592 | 4066 |  |  |
| slow-3g | no probe | 5 | 3081 | 3218 | 3709 | -12.0% |  |
| slow-3g | concurrent | 5 | 1279 | 1320 | 1646 | -63.4% | -58.5% |
| slow-3g | revisit | 3 | 1280 | 1319 | 1646 | -63.4% | -58.5% |
| slow-3g | quick revisit | 2 | 503 | 522 | 711 | -85.6% | -83.7% |
