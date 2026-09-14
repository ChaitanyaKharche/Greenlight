# GreenLight

Green-light speed advice on an Android phone. Enter a destination, or just drive — it tells
you what speed to hold to arrive at the next light on green, and works as a floating bubble
on top of Google Maps.

This is the same idea as Audi's *Traffic Light Information* and the equivalents from Porsche
and BMW: GLOSA, Green Light Optimal Speed Advisory.

---

## Read this before you get excited

The factory systems work because the manufacturer **buys a live feed**. Audi's runs through
Traffic Technology Services, which aggregates real-time controller state from municipal traffic
management centres and pipes it to the car. That data is licensed to the vehicle, is not free,
and is not something a phone app can call.

So this app cannot magically know what every light is doing. What it does instead:

| Source | Coverage | Accuracy | Needs |
|---|---|---|---|
| **Learned** (default) | Anywhere you drive repeatedly | ±2–4 s once converged | ~5+ passes per light |
| **Manual** | Whatever you measure | Exact for fixed-time signals | A stopwatch, once |
| **Live SPaT** | Wherever a real feed exists | Sub-second | A feed URL you have access to |

The learned mode is the one that works out of the box. It recovers each signal's cycle from
your own stop-and-go history — the published approach for doing this from probe-vehicle
trajectories, just with one phone instead of a fleet, so it needs more days to converge.

**It will not work on everything.** Sensor-actuated signals that extend green based on
detected traffic have no fixed cycle to learn, and the app stays quiet rather than guessing.
Roughly speaking: coordinated arterials and downtown grids, yes; a light on a rural crossroads
that only changes when a car pulls up, no.

---

## The maths

**Feasible speed for one signal.** Travel time to a stop line is strictly decreasing in cruise
speed, so each green window $[s_k, e_k)$ maps to exactly one contiguous speed interval. Arriving
at $e_k$ sets the lower bound, arriving at $s_k$ the upper:

$$V_k = \left[\, v : T(d, v_0, v, a) = e_k - t_0 \,,\; v : T(d, v_0, v, a) = s_k - t_0 \,\right] \cap [v_{\min}, v_{\max}]$$

Travel time $T$ uses a trapezoidal profile rather than $d/v$, because assuming you teleport to
the target speed costs several seconds over a 200 m approach — enough to put you on the wrong
side of an amber:

$$T(d, v_0, v_c, a) = \frac{|v_c - v_0|}{a} + \frac{d - \tfrac{1}{2}(v_0 + v_c)\tfrac{|v_c-v_0|}{a}}{v_c}$$

falling back to the positive root of $d = v_0 t + \tfrac{1}{2}at^2$ when the ramp does not finish
before the stop line. It is inverted by bisection.

**Green wave.** For a corridor, the same constant speed must clear every signal, so intersect
the interval sets: $V = \bigcap_i \bigcup_k V_{i,k}$. The solver deepens the chain greedily and
keeps the deepest one that still leaves a usable band. That is what produces "hold 48, clears
3 lights" instead of a new number at every junction.

**Confidence.** Arrival-time error propagates from three sources:

$$\sigma_t^2 = \sigma_{\text{sched}}^2 + \left(\frac{d\,\sigma_v}{v^2}\right)^2 + \left(\frac{\sigma_d}{v}\right)^2$$

and the displayed number is $\Phi\!\left(\frac{e-t_a}{\sigma_t}\right) - \Phi\!\left(\frac{s-t_a}{\sigma_t}\right)$,
scaled by how much the schedule itself is trusted. Below 55% it stops advising a speed and tells
you you're stopping.

**Learning the cycle.** At a fixed-time signal every red-to-green satisfies $g_i = \varphi + k_i C$,
so all pairwise differences are integer multiples of $C$ — an approximate-GCD problem. The app
sweeps candidate cycles from 30–200 s and scores each by circular concentration
$R = \left|\frac{1}{n}\sum_j e^{2\pi i g_j / C}\right|$, the same trick a pitch detector uses.

Two things make this honest rather than wishful:

1. **Sub-multiples alias perfectly.** If $C$ is the true cycle then $C/2, C/3, \dots$ also produce
   a flawless cluster, because every multiple of $C$ is a multiple of $C/m$. Super-multiples do
   not. So the estimator takes the **largest** well-scoring candidate, never the smallest.
2. **Sweeping ~300 candidates overfits.** Pure noise reliably peaks around $R \approx 0.7$. Under a
   uniform null $P(R > r) \approx e^{-nr^2}$, and adjacent candidates only become distinguishable
   once the furthest observation shifts half a turn, $\mathrm{d}C \approx C^2/(2\,\text{span})$.
   That gives $M = 2\,\text{span}\,(1/C_{\min} - 1/C_{\max})$ effective independent tries and a
   significance of $\exp(-M e^{-nr^2})$, which the confidence is multiplied by. Noise scores
   ~0.09; a real signal scores ~0.89.

Phase is measured from **local midnight**, not the Unix epoch, because coordinated controllers
lock their offsets to a local master clock. Observations are bucketed by time-of-day plan
(AM peak / midday / PM peak / evening / night, weekday vs weekend) and by approach octant, so
opposing directions and different timing plans never pollute each other.

Departures are biased late by queue discharge — being third in line means you move well after the
light changed — so the offset is anchored on the early edge of the cluster with a standard
start-up-loss correction, not on the mean.

---

## Install

### Option A — download the APK from CI (no Android Studio)

1. Open the **Actions** tab of this repo → the latest **Build APK** run
2. Download the `greenlight-debug-apk` artifact and unzip it
3. Copy `app-debug.apk` to your phone and tap it
4. Allow "install unknown apps" for your browser or file manager when prompted

### Option B — build it yourself

```bash
git clone https://github.com/ChaitanyaKharche/Greenlight
cd Greenlight
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 35.

### Permissions to grant

| Permission | Why | Where |
|---|---|---|
| Precise location | Everything | In-app prompt |
| **Display over other apps** | The bubble on top of Google Maps | In-app button → system settings |
| Notifications | The foreground service notification | In-app prompt |

On a Galaxy S24, also go to **Settings → Battery → Background usage limits** and mark GreenLight
as **Unrestricted**. One UI is aggressive about killing location services, and without this it
will be suspended mid-drive.

---

## Using it

**With Google Maps.** Open GreenLight, tap **Start**, enable the overlay, then switch to Google
Maps and navigate normally. The bubble floats on top. You do not need to enter a destination —
free-drive mode watches the road ahead and picks up signals in a cone in front of you. Drag the
bubble to move it, tap to collapse it to a small readout.

**With a destination in-app.** Search an address and pick a result. GreenLight routes with OSRM,
pulls every traffic signal along that route from OpenStreetMap, and then knows the corridor in
advance — which is what makes multi-light green-wave advice possible rather than just next-light
advice.

**What the colours mean.** Green: hold what you are doing, you clear it. Amber: change speed, or
confidence is marginal. Red: you are stopping — the number becomes seconds until green, so you
can come off the throttle early instead of braking hard.

**It will never advise you above the speed limit.** There is no setting to turn that off. Where
OpenStreetMap has no `maxspeed` for the road, it falls back to a conservative default.

---

## Plugging in a real signal feed

If your city, university, or a connected-vehicle pilot gives you access to an actual SPaT feed,
that is the path to factory-grade accuracy. `RestSpatProvider` takes a URL template and expects:

```json
{
  "intersections": [
    {
      "id": "1234",
      "lat": 42.3601, "lon": -71.0589,
      "approaches": [
        { "bearing": 90, "state": "GREEN", "secondsToChange": 12.5,
          "cycleSec": 90, "greenSec": 32 }
      ]
    }
  ]
}
```

Wire it up in `GlosaEngine`:

```kotlin
engine.liveProvider = RestSpatProvider(
    baseUrl = "https://your-feed/spat?lat={lat}&lon={lon}&radius={radius}",
    apiKeyHeader = "X-Api-Key",
    apiKey = BuildConfig.SPAT_KEY,
)
```

If your feed speaks raw SAE J2735 over MQTT instead, subclass and translate into
`LiveSpatSchedule` — everything downstream is agnostic to how the bytes arrived. The fusion
layer automatically prefers a live feed over learned timing.

---

## Architecture

```
core/     Geo, numerics — no Android dependencies, fully unit tested
model/    Domain types, SignalSchedule abstraction over learned and live timing
glosa/    Kinematics and the corridor solver
learn/    Cycle estimator and the GPS observation state machine
data/     SQLite cache, Overpass / Nominatim / OSRM clients
spat/     Provider interface, learned / manual / REST implementations, fusion
nav/      Route snapping, free-drive cone search, the orchestrating engine
service/  Foreground service, notification, TTS, floating overlay
ui/       Compose screen
```

The core is deliberately Android-free so the maths can be tested on the JVM. 45 unit tests,
including a property test asserting that every speed the solver advises actually lands inside a
green window.

```bash
./gradlew testDebugUnitTest
```

---

## Honest limitations

- **Actuated signals.** No fixed cycle, nothing to learn. The app stays quiet.
- **Cold start.** A junction needs roughly five passes in the same plan bucket before it says
  anything. Your usual commute converges within a week; a road you have never driven gives you
  nothing.
- **Queue position.** The offset estimate is biased by how far back you were stopped. Corrected
  with a constant, but it is still the largest error term.
- **GPS in urban canyons.** Multipath between tall buildings wrecks the distance-to-stop-line
  estimate, and the confidence number does not fully capture that.
- **OSM coverage.** Signals come from OpenStreetMap. Well mapped in cities, patchy in some
  places. Missing signal, no advice.
- **Overpass reliability.** The public API returns 504 under load. The app tries three mirrors
  twice, and caches everything locally, so a repeated route makes no network calls at all.
- **Free public endpoints.** Nominatim, OSRM and Overpass are community infrastructure under
  fair-use policies. Fine for personal use. Self-host before doing anything larger.

## Safety

Advisory only. It does not know about the car in front of you, a cyclist in the bike lane, or a
pedestrian stepping off the kerb. Watch the road, not the bubble.

## References

- [Audi Traffic Light Information — how the factory system works](https://media.audiusa.com/releases/412)
- [Traffic Technology Services — the commercial feed behind it](https://www.traffictechservices.com/)
- [GLOSA overview, Interreg Europe](https://www.interregeurope.eu/good-practices/glosa-green-light-optimal-speed-advisory)
- [Traffic Signal Phase and Timing Estimation with Large-Scale Floating Car Data (arXiv 2507.14190)](https://arxiv.org/html/2507.14190v1)
- [Learning traffic signal phase and timing from low-sampling-rate taxi GPS trajectories](https://www.sciencedirect.com/science/article/abs/pii/S0950705116302519)
- [GLOSA in SUMO — simulation reference](https://sumo.dlr.de/docs/Simulation/GLOSA.html)
- [Overpass QL recurse filters](https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL)

## Licence

MIT.
