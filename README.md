# Java Web Framework — Concurrency, Graceful Shutdown and Cloud Deployment Extension

Extension of the course web framework built in
[LAB06 — Building and Deploying a Maintainable Application Server](https://github.com/JoshQ10/LAB06_TDSE_Building-and-Deploying-a-Maintainable-Application-Server).
The framework is written in plain Java on top of `java.net.ServerSocket`, with **no Spring and no runtime dependencies**. This extension makes it handle requests concurrently, shut down gracefully on `SIGTERM`, run inside a Docker container and deploy on AWS EC2.

Companion repository (Spring Boot workshop):
[LAB07 — Containerizing and Deploying a Java Web Application (Spring Boot)](https://github.com/JoshQ10/LAB07_TDSE_Workshop-Containerizing-and-Deploying-a-Java-Web-Application-Springboot).

## Table of contents

- [Current state of the framework](#current-state-of-the-framework)
- [Changes introduced in this extension](#changes-introduced-in-this-extension)
- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Configuration (environment variables)](#configuration-environment-variables)
- [Build and run locally](#build-and-run-locally)
- [Run in Docker](#run-in-docker)
- [Publish to Docker Hub](#publish-to-docker-hub)
- [AWS EC2 deployment](#aws-ec2-deployment)
- [Evidence of progress](#evidence-of-progress)
- [Completed cloud deployment evidence](#completed-cloud-deployment-evidence)
- [Deliverables checklist](#deliverables-checklist)

## Current state of the framework

The starting point is the LAB06 framework, imported unchanged in commit
[`c0c25d7`](https://github.com/JoshQ10/LAB07_TDSE_Workshop-Containerizing-and-Deploying-a-Java-Web-Application-Framework-Extension/commit/c0c25d7).
It already provided:

- A public API (`WebFramework`) used through a static import: `staticfiles(...)`, `get(path, lambda)`, `start()`, `stop()`.
- Lambda-based GET routes (`GetService`), resolved by a `Router`.
- Static file serving from the classpath (`/webroot`) with path-traversal protection and content types by extension.
- The port read from `PORT` (default `8080`).
- JUnit tests for `Request`, `Router` and `StaticFileService`.

An application only registers routes and never touches the server loop:

```java
staticfiles("/webroot");
get("/greeting", (req, resp) -> "Hello, " + req.getValue("name") + "!");
start();
```

Its limitations were deliberate for LAB06:

- **Sequential**: one connection was accepted, fully handled and closed before the next `accept()`. A slow handler blocked every other client.
- **No signal-driven shutdown**: `stop()` only set a flag, and was only reachable from a `/shutdown` route. `SIGTERM` (for example `docker stop`) killed the process without any cleanup. The flag was also checked only after the next connection arrived.
- **Java 17** and no container packaging.

## Changes introduced in this extension

| Requirement | Before (LAB06) | After (this extension) |
|---|---|---|
| Concurrent request handling | Single-threaded accept → handle → close loop. | The thread calling `start()` only accepts connections. Each socket goes to a fixed-size `ExecutorService` (`worker-N` threads, size from `THREAD_POOL_SIZE`). `Router` now uses a `ConcurrentHashMap` because workers read it in parallel. Each client socket has a 10 s read timeout, so an idle client cannot hold a worker forever. |
| Graceful shutdown | `stop()` set a flag; the loop stayed blocked in `accept()` until another request arrived. | `stop()` **closes the listening socket**, which unblocks `accept()` right away and refuses new connections. `start()` then calls `shutdown()` + `awaitTermination()` on the pool, so in-flight requests still get their response (bounded by `SHUTDOWN_TIMEOUT_SECONDS`, then `shutdownNow()`). |
| Shutdown on `SIGTERM` / Ctrl+C | Not handled. | `WebFramework.start()` registers a JVM **shutdown hook**. The hook calls `stop()` and **waits** for the pool to drain, because the JVM exits as soon as its hooks return. |
| Port from environment variable | `PORT`, default `8080`. | Unchanged, plus `THREAD_POOL_SIZE` and `SHUTDOWN_TIMEOUT_SECONDS`. |
| Docker container execution | None. | `Dockerfile` on `amazoncorretto:21` with an exec-form `ENTRYPOINT`, so the JVM is PID 1 and receives `SIGTERM`. Also a `compose.yaml` with `stop_grace_period`. |
| AWS EC2 deployment | Deployed as a plain jar with systemd. | Deployed as a Docker image pulled from Docker Hub. |
| Tests | Unit tests only. | New `HttpServerTest` starts a real server: (1) three slow requests finish in parallel on three different workers, (2) a slow request does not block a fast one, (3) `stop()` refuses new connections while the in-flight request still returns `200`. |
| Sample app | `/hello`, `/pi`, `/date`, `/shutdown`. | Adds `/greeting?name=` (same contract as the Spring Boot workshop) and `/slow?seconds=` (concurrency and shutdown demo). Build target is Java 21. |

## Architecture

```
Client (browser / curl)
   │ HTTP
   ▼
EC2 virtual machine (Amazon Linux 2023)
   │ port 8080 (security group)
   ▼
Docker Engine ── SIGTERM on `docker stop`
   │
   ▼
Container: amazoncorretto:21  →  java -jar app.jar  (PID 1)
   │
   ▼
WebFramework.start()
   ├─ shutdown hook ──► stop() + wait for drain
   └─ HttpServer
        ├─ acceptor thread: ServerSocket.accept() loop
        └─ ExecutorService (worker-1 … worker-N)
              └─ parse request → Router (GET lambdas) or StaticFileService → response
```

Shutdown sequence:

```
SIGTERM ─► hook: stop() ─► listening socket closed (new connections refused)
                         ─► accept loop exits ─► pool.shutdown() + awaitTermination()
                         ─► in-flight requests finish ─► "Shutdown complete." ─► JVM exits
```

## Project structure

```
.
├── pom.xml
├── Dockerfile
├── compose.yaml
└── src
    ├── main
    │   ├── java/co/edu/escuelaing
    │   │   ├── app/Application.java            # sample application using the framework
    │   │   └── webframework
    │   │       ├── WebFramework.java           # public API + shutdown hook + env config
    │   │       ├── HttpServer.java             # acceptor + worker pool + graceful drain
    │   │       ├── Router.java                 # GET route table (concurrent)
    │   │       ├── Request.java / Response.java
    │   │       ├── GetService.java             # lambda contract for handlers
    │   │       ├── StaticFileService.java      # static resources, traversal-safe
    │   │       └── ContentTypes.java
    │   └── resources/webroot                   # index.html, styles.css, app.js, images
    └── test/java/co/edu/escuelaing/webframework
        ├── HttpServerTest.java                 # concurrency + graceful shutdown
        ├── RequestTest.java
        ├── RouterTest.java
        └── StaticFileServiceTest.java
```

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Listening port. |
| `THREAD_POOL_SIZE` | `10` | Number of worker threads that serve requests in parallel. |
| `SHUTDOWN_TIMEOUT_SECONDS` | `8` | Maximum time to wait for in-flight requests when stopping. |
| `APP_ENV` | `development` | The `/shutdown` route is only registered in `development`. The Docker image sets `production`. |
| `GREETING_PREFIX` | `Hello` | Prefix used by `/hello`. |
| `STATIC_FILES_PATH` | — | Optional external folder for static files instead of the classpath. |

## Build and run locally

Requirements: Java 21 and Maven 3.9+.

```bash
mvn clean package          # compiles and runs all tests
java -jar target/webframework.jar
```

| URL | Response |
|---|---|
| `http://localhost:8080/` | Static page from `webroot` |
| `http://localhost:8080/greeting?name=Pedro` | `Hello, Pedro!` |
| `http://localhost:8080/slow?seconds=3` | `Finished after 3s on worker-N` |
| `http://localhost:8080/pi`, `/date`, `/hello?name=Ana` | LAB06 services |

### Demonstrating concurrency

```bash
time (curl -s localhost:8080/slow & curl -s localhost:8080/slow & curl -s localhost:8080/slow & wait)
```

The total is about **3 s**, not about 9 s, and each response names a different `worker-N`. Measured output:

```
Finished after 3s on worker-3Finished after 3s on worker-4Finished after 3s on worker-5
real    0m3.078s
```

### Demonstrating graceful shutdown

Start a long request and stop the server while it is still running (Ctrl+C locally, `docker stop` in a container):

```bash
curl "localhost:8080/slow?seconds=4" &     # in-flight request
# ... then Ctrl+C / docker stop
```

The in-flight request still receives `Finished after 4s on worker-1`, new connections are refused, and the log shows:

```
Shutting down: closing listening socket and draining worker pool...
Worker pool drained cleanly.
Server stopped gracefully.
Shutdown complete.
```

## Run in Docker

```bash
mvn clean package
docker build -t joshq10/micro-http-framework:1.0 .

docker run -d \
  --name webframework \
  --stop-timeout 15 \
  -e PORT=8080 \
  -p 8080:8080 \
  joshq10/micro-http-framework:1.0
```

Verify with `http://localhost:8080/greeting?name=Container`, which returns `Hello, Container!`.

Graceful shutdown inside the container:

```bash
curl "localhost:8080/slow?seconds=4" &
docker stop webframework
docker logs webframework
```

> **Why `--stop-timeout 15`?** `docker stop` sends `SIGTERM` and sends `SIGKILL` once the stop timeout expires. The drain can take up to `SHUTDOWN_TIMEOUT_SECONDS` (8 s), so the container needs a longer grace period. Docker Engine defaults to 10 s, but the Docker Desktop installation used here gave containers a **1 s** stop timeout, which killed the JVM in the middle of the drain (exit code 137). Setting the value explicitly makes the behavior the same on every host. The container exits with code `143` (128 + SIGTERM), which is the normal code for a JVM that shut down on `SIGTERM`.

### Docker Compose

```bash
docker compose up -d --build
```

The service is available at `http://localhost:8088/greeting?name=Compose`. `compose.yaml` sets `stop_grace_period: 15s` for the same reason.

```bash
docker compose ps
docker compose logs web
docker compose down
```

## Publish to Docker Hub

```bash
docker login
docker tag joshq10/micro-http-framework:1.0 joshq10/micro-http-framework:latest
docker push joshq10/micro-http-framework:1.0
docker push joshq10/micro-http-framework:latest
```

**Docker Hub repository URL:** https://hub.docker.com/r/joshq10/micro-http-framework

Published tags: `1.0` and `latest` (image digest `sha256:726cba4fe2d3…`).

## AWS EC2 deployment

1. Launch an EC2 instance with **Amazon Linux 2023**.
2. Security group: SSH (22) only from your public IP. TCP 8080 only from the network that needs access.
3. Connect and install Docker:

```bash
ssh -i labsuser.pem ec2-user@<ec2-public-dns>

sudo yum update -y
sudo yum install -y docker
sudo service docker start
sudo usermod -a -G docker ec2-user
exit    # reconnect so the docker group membership applies
```

4. Pull and run the image:

```bash
docker pull joshq10/micro-http-framework:1.0

docker run -d \
  --name webframework \
  --restart unless-stopped \
  --stop-timeout 15 \
  -e PORT=8080 \
  -p 8080:8080 \
  joshq10/micro-http-framework:1.0

docker ps
docker logs webframework
```

5. Verify from a browser: `http://<ec2-public-dns>:8080/greeting?name=AWS`, which returns `Hello, AWS!`.
6. Graceful shutdown on EC2: `docker stop webframework && docker logs webframework`.

Terminate the instance when finished to avoid charges.

**Public deployment URL:**



## Evidence of progress

**Commit history:**

| Commit | Description |
|---|---|
| [`c0c25d7`](https://github.com/JoshQ10/LAB07_TDSE_Workshop-Containerizing-and-Deploying-a-Java-Web-Application-Framework-Extension/commit/c0c25d7) | *Import LAB06 webframework as the baseline for the extension*. Sequential server, unchanged. |
| [`742b0f4`](https://github.com/JoshQ10/LAB07_TDSE_Workshop-Containerizing-and-Deploying-a-Java-Web-Application-Framework-Extension/commit/742b0f4) | *Implement concurrent request handling and graceful shutdown*. Worker pool, shutdown hook, Java 21, Docker, tests. |

The diff between the two commits shows exactly what the extension changed in the framework.

### Local execution and tests

| # | Description | Screenshot |
|---|---|---|
| 1 | `mvn clean package`: all tests pass, including `HttpServerTest` | |
| 2 | `/greeting?name=Pedro` responding `Hello, Pedro!` | |
| 3 | Concurrency: three `/slow` requests finish in ~3 s on different `worker-N` threads | |
| 4 | Graceful shutdown with Ctrl+C: in-flight request answered, drain log | |

### Docker

| # | Description | Screenshot |
|---|---|---|
| 5 | `docker build` and `docker images` | |
| 6 | `docker run` + `docker ps` + `/greeting?name=Container` | |
| 7 | `docker stop` during an in-flight `/slow` request + `docker logs` showing the drain | |
| 8 | `docker compose up -d --build` + response on port `8088` | |

### Docker Hub

| # | Description | Screenshot |
|---|---|---|
| 9 | Push of the `1.0` and `latest` tags | |
| 10 | Repository page on Docker Hub showing both tags | |

## Completed cloud deployment evidence

| # | Description | Screenshot |
|---|---|---|
| 11 | SSH connection to the EC2 instance | |
| 12 | `docker pull joshq10/micro-http-framework:1.0` on EC2 | |
| 13 | Container running on EC2 (`docker ps`, `docker logs`) | |
| 14 | Browser response from the public URL: `Hello, AWS!` | |
| 15 | Graceful shutdown on EC2 (`docker stop` + `docker logs`) | |

## Deliverables checklist

- [x] Framework source code, no Spring (extends the LAB06 course framework)
- [x] Concurrent request handling
- [x] Graceful shutdown (including `SIGTERM` from `docker stop`)
- [x] Port read from an environment variable
- [x] Dockerfile and compose.yaml
- [x] Docker Hub repository URL
- [x] Meaningful commits showing the extension (baseline → extension)
- [ ] Screenshots of local and Docker execution
- [ ] Evidence of the EC2 deployment
- [ ] Public deployment URL
- [ ] Demo video (local Docker + EC2)
