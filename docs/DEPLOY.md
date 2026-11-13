# Deploy to Render (free), step by step

This puts the exchange and the live page on a public `https://<your-name>.onrender.com` address.
You do the clicking; the repository already contains everything Render needs (`Dockerfile`, the health
endpoint, the `PORT` setting).

> **Read "What the free plan means" before you share the link.** The exchange forgets everything every
> time the free service sleeps or restarts. For a demo that is fine, but you should know it.

## Before you start

1. The code is on GitHub (`git push` done). Render builds from the repository, not from your PC.
2. You have a Render account (<https://render.com>, "Get started" with your GitHub login is easiest).
3. Optional but wise: build and run the image on your PC first (see "Try the image locally" below).

## Create the service

In the Render dashboard (menu names may be slightly different: Render changes its screens now and then):

1. Click **New +** and choose **Web Service**.
2. Choose **Git provider** and connect your GitHub account when asked. Allow access to the
   `matching-engine` repository (you can allow only that one).
3. Select the repository `sanjeevveerapandian/matching-engine` and click **Connect**.
4. Fill in the form:

   | Field | Value | Why |
   |---|---|---|
   | **Name** | `matching-engine` (any name) | It becomes the address: `https://<name>.onrender.com` |
   | **Language** | **Docker** | Render then builds your `Dockerfile` instead of guessing a build command |
   | **Branch** | `main` | The branch Render deploys, and re-deploys on every push |
   | **Region** | the one nearest to you | Lower delay for you and for visitors |
   | **Root Directory** | leave empty | The `Dockerfile` is in the repository root |
   | **Instance Type** | **Free** | No cost |

5. Open **Advanced** (or the section with "Health Check Path") and set:

   | Setting | Value |
   |---|---|
   | **Health Check Path** | `/actuator/health` |

   Render asks this address every few seconds. Any `2xx` or `3xx` answer means healthy; any `4xx` or
   `5xx` means failed (our endpoint answers `503` if the engine thread has died). During a deploy, Render
   only switches traffic to the new version once this check passes.

6. **Environment variables: you do not need to add any.** For reference, this is what is already handled:

   | Variable | Who sets it | What it does |
   |---|---|---|
   | `PORT` | **Render** (default `10000`) | The server listens on it (`server.port=${PORT:8080}`), so Render can reach the app |
   | `MATCHING_DATADIR` | the `Dockerfile` (`/app/data`) | Where the command log and snapshots go |

   Optional overrides, only if you want them (add under **Environment** > **Add Environment Variable**):

   | Variable | Example | What it does |
   |---|---|---|
   | `MATCHING_SNAPSHOTEVERY` | `10000` | Commands between snapshots |
   | `MATCHING_QUEUECAPACITY` | `1024` | How many commands may wait for the engine thread |
   | `MATCHING_BOOKPUBLISHINTERVAL` | `100ms` | Minimum gap between book updates on the live feed |

   (Spring Boot reads `MATCHING_SNAPSHOTEVERY` as `matching.snapshot-every`: dashes are dropped from the
   variable name.)

7. Click **Create Web Service** (or **Deploy**).

## Watch the first deploy

- Open the service's **Logs** tab. You will see Docker download the images, Maven download the libraries
  (the first build is the slowest), then the Spring Boot banner and `Started MatchingEngineApplication`.
- Render marks the deploy **Live** when `/actuator/health` answers. If that does not happen within
  15 minutes, Render cancels the deploy and keeps the previous version (there is none the first time).
- The free instance has a **small CPU (0.1)**, so the app starts more slowly than on your laptop.
  **Measured on a real first deploy of this repository:** the whole deploy took **2 min 38 s**, and the app's
  own log said `Started MatchingEngineApplication in 59.105 seconds` (about 2 seconds on a laptop). Render's
  limit for a deploy is 15 minutes, so a few minutes of "In progress" is normal.
- For comparison, I had *predicted* nearly 4 minutes by running the image locally with Docker limited to
  512 MB and 0.1 CPU (about 224 s to answer its health check). That emulation was **pessimistic**: Render's
  real start-up was about four times faster. Keep that in mind for the simulation numbers below, which come
  from the same emulation and have **not** been measured on Render.

## Check that it works

Replace the address with yours:

1. Open `https://<name>.onrender.com/`: the page loads and the badge at the top right says **Live**.
2. Open `https://<name>.onrender.com/actuator/health`: you should see exactly `{"status":"UP"}`. (On Render's
   first deploy it also listed `"groups":["liveness","readiness"]`: Spring Boot adds those by itself on a cloud
   platform. I switched them off afterwards, so redeploys show only the status.)
3. Press **Simulate traffic** on the page: the ladder fills and trades appear on the tape.
4. Place two crossing orders by hand (Sell then Buy at the same price): the trade appears at once.

If the page loads but the badge never says **Live**, the WebSocket is not connecting: see "If something
goes wrong".

## What the free plan means (from Render's own documentation)

| Fact | What it means for this project |
|---|---|
| **Spins down after 15 minutes without incoming traffic** | The first visitor after a quiet spell waits while it starts again (Render says "about one minute", and its dashboard says requests can be delayed "by 50 seconds or more"). I have not yet timed a real wake-up of this app |
| **Local files are lost on every restart, redeploy and spin-down; no persistent disk on the free plan** | The command log and snapshots are erased: **the exchange starts empty every time**, and recovery from the log cannot help on this plan |
| **750 free instance hours per month per workspace** | One always-on service uses about 720 to 744 hours a month, so it fits, but **only one** (see "Keeping the demo awake") |
| **512 MB RAM, 0.1 CPU** (Render's compute-plans page) | In my local test with those limits, memory stayed around 145 MiB (heap cap about 308 MB), with no out-of-memory kill. The CPU is the tight part: see "Simulations on a small CPU" below |
| Render may restart the service at any time | Same as above: do not expect state to survive |

Sources: Render's pages on [free services](https://render.com/docs/free),
[web services](https://render.com/docs/web-services), [health checks](https://render.com/docs/health-checks)
and [compute plans](https://render.com/docs/compute-plans). These limits change, so check them before you rely on them.

## Keeping the demo awake (optional)

A free service sleeps after 15 minutes without traffic, so a visitor who arrives after a quiet spell waits for it to wake.
You can avoid that by sending it one small request every few minutes. Know the trade-offs first:

- **It uses your free hours.** An always-awake service runs 24 hours a day: **744 of your 750 free hours in a 31-day month**
  (720 in a 30-day month). That fits **only if it is the only free web service in your Render workspace**. If two free
  services stay awake, Render suspends the free services until the next month. Check the dashboard before you start.
- **It does not keep the data.** A restart or a redeploy still erases the order book. Keeping it awake only avoids the
  *sleep* delay.
- **It hides how a sleeping free service behaves**, which is fine for a demo, but keep it in mind when you quote start-up times.

**What gets called.** Only `GET /actuator/health`. It is cheap: it reads one flag and never touches the order book, the
command log or the snapshots (a test proves 50 calls leave the log at 0 bytes). It answered in a few milliseconds on a laptop
and in 0.2 to 0.5 seconds on the deployed demo, and both `GET` and `HEAD` return `200`. **Never ping `/robots.txt`**: Render
answers that file itself, without waking your app.

### Main option: an UptimeRobot monitor

| Setting | Value |
|---|---|
| Monitor type | **HTTP(s)** |
| Friendly name | `Matching Engine demo` (anything) |
| URL | `https://matching-engine-hwru.onrender.com/actuator/health` |
| Monitoring interval | **5 minutes** (the shortest on UptimeRobot's free plan, per its pricing page) |
| HTTP method | **GET** if it lets you choose (the check also works with `HEAD`) |
| Alert contact | your e-mail |

Leave the timeout at its default. A ping that arrives while the service is asleep may be reported as "down" once while it
wakes up; that ping still wakes it.

### Backup option: the GitHub Actions workflow

`.github/workflows/keep-alive.yml` sends the same request every 10 minutes (at :04, :14, :24, ...), and you can also run it by
hand from the repository's **Actions** tab ("keep-alive", then **Run workflow**). It is only a **backup**, because GitHub's
scheduler is not a clock:

- scheduled runs can be **delayed by many minutes** when GitHub is busy, and some are **skipped**, so a gap longer than Render's
  15 minutes can still happen;
- in a public repository GitHub **turns scheduled workflows off after 60 days without repository activity** (a push counts).
  If that happens, re-enable the workflow on the Actions tab.

That is why UptimeRobot is the main option: it is built for exactly this, on a fixed 5-minute clock.

If you would rather not keep it awake, put a line in your README or CV instead: "free hosting: the first load can take a few minutes".

## Simulations on a small CPU (measured locally, not on Render)

These numbers come from my PC with Docker limited to Render's documented 512 MB and 0.1 CPU. I have **not**
repeated them on Render, and start-up there turned out much faster than the emulation predicted, so treat
them as a cautious estimate.

Render stops sending traffic to an instance after 15 seconds of failed health checks (each must answer
within 5 seconds) and restarts it after 60 seconds. So I checked that **Simulate traffic** does not make the
health check fail. With the image limited to 512 MB and 0.1 CPU:

| Run | How long it took | Slowest health answer meanwhile |
|---|---|---|
| 1,000 operations (the page's button), JVM still cold | 8.8 s | 3.4 s (the closest call: the limit is 5 s) |
| 10,000 operations, the first time | 96 s | not measured |
| 10,000 operations, after the JVM had warmed up | 22.6 s | 0.3 s |

No health answer in any of my runs went over 5 seconds, but the cold run came within about 1.5 seconds of it.
The cause is that the Java just-in-time compiler competes with the app for the small CPU while the app is
new. Practical advice: after a deploy or a wake-up, give it a minute or two before pressing **Simulate
traffic** a lot. If you ever see the service restart during a demo, this is the first place to look.

## Auto-deploy

Render re-deploys every time you `git push` to `main`. To stop that, open the service's **Settings** and
change **Auto-Deploy** to off, then deploy by hand with **Manual Deploy**.

## Warning: it is a public exchange with no login

Anyone who finds the link can place orders and press **Simulate traffic** (up to 10,000 operations per
call). There is no authentication and no rate limit. That is acceptable for a portfolio demo, but do not
put anything private on it, and do not post the link somewhere very public without expecting strangers to
use it. Adding rate limits is listed under "what I would do next" in the README.

## If something goes wrong

| What you see | Likely cause and what to do |
|---|---|
| Build fails with "killed" or out of memory | The free build machine ran out of memory during Maven. Re-run the deploy (**Manual Deploy > Clear build cache & deploy**). If it keeps happening, tell me the log lines and we will slim the build |
| Deploy never becomes Live | Open **Logs**. If the app logged `Started MatchingEngineApplication` but Render still waits, check the Health Check Path is exactly `/actuator/health` (with the leading `/`) |
| Page loads but the badge says **Disconnected** | The WebSocket was refused. The app trusts Render's forwarded-host headers (`server.forward-headers-strategy=framework`), so check the logs for `403`, and that you opened the real `https://<name>.onrender.com` address (not an old preview URL) |
| `502` or a long wait on the first visit | The service was asleep. Render says waking takes about a minute (its dashboard: "50 seconds or more"). Wait and reload |
| The book is empty after a few minutes of silence | The service slept and restarted, and the free plan erased the state. Press **Simulate traffic** |

## Try the image locally first (recommended)

With Docker Desktop running (the engine must say "Engine running"):

```bash
docker build -t matching-engine:local .
docker run --rm -p 8080:8080 --name matching-engine matching-engine:local
```

Then open <http://localhost:8080/> and <http://localhost:8080/actuator/health>. Stop it with `Ctrl+C`.
(If port 8080 is already used, for example by `mvn spring-boot:run`, use `-p 8081:8080` and open port 8081.)

To test the `PORT` variable, as Render does:

```bash
docker run --rm -e PORT=10000 -p 10000:10000 --name matching-engine matching-engine:local
```

To imitate Render's free instance (512 MB, 0.1 CPU; expect a start-up of several minutes):

```bash
docker run --rm --memory=512m --memory-swap=512m --cpus=0.1 -e PORT=10000 -p 10000:10000 --name matching-engine matching-engine:local
```

## Railway instead?

Render fits this project (Docker, WebSockets, a free plan, a health check path), so I did not research
Railway. Its free offering has changed over time: check its current terms before choosing it.
