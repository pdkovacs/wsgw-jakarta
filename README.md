# wsgw-jakarta — WebSocket gateway (Jakarta EE)

Stateful WebSocket connections don't compose well with stateless backends — they
pin clients to specific instances and turn horizontal scaling into a routing
problem. **wsgw** lifts that one concern out of the application: it owns the
WebSocket connections so the backend can stay stateless, and it speaks plain HTTP
to the backend in both directions.

This is the Jakarta EE / JVM re-port of the Go project
[`pdkovacs/wsgw`](https://github.com/pdkovacs/wsgw); it targets Tomcat 11 on
JDK 25 using a blocking-on-virtual-threads model. It's a small, focused service
for low- to medium-workload deployments that want to scale the application
horizontally without the operational weight of a heavyweight gateway.

## How it works

```
Client                  wsgw                              Backend
  |                      |                                   |
  |--- GET /connect ---->|--- GET /ws/connect/{id} -------->|
  |                      |<------------ 204 -----------------|
  |<-- 101 handshake ----|                                   |
  |                      |                                   |
  |---- WS frame ------->|--- POST /ws/message/{id} ------->|
  |                      |                                   |
  |                      |<-- POST /message/{id} ------------|
  |<--- WS frame --------|                                   |
  |                      |                                   |
  |--- WS close -------->|--- POST /ws/disconnected/{id} -->|
```

1. Client opens a WebSocket: `GET /connect`.
2. wsgw generates a connection ID and forwards the request as a plain HTTP `GET`
   to the backend's `/ws/connect/{id}` for authentication.
3. Backend returns `204` → wsgw upgrades the connection to a WebSocket.
4. **Client → backend:** wsgw relays each WS frame to `POST /ws/message/{id}`.
5. **Backend → client:** backend `POST`s to wsgw's `/message/{id}`; wsgw delivers
   the body over the WebSocket.
6. Either side closes → wsgw notifies the backend via
   `POST /ws/disconnected/{id}` (best-effort).

## Endpoint reference

### Provided by the gateway

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/connect` | Client opens a WebSocket. |
| `POST` | `/message/{connectionId}` | Backend delivers a message to a client. Returns **429** when the connection is under backpressure — see [docs/backpressure.md](docs/backpressure.md). |
| `POST` | `/disconnect/{connectionId}` | Backend forces a connection closed. |

### Expected from the backend

The backend serves three endpoints under the base URL given by `APP_BASE_URL`.
The original client headers (including `Authorization`) are passed through; wsgw
does no auth itself.

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/ws/connect/{connectionId}` | Authenticate a new connection. Return `204` to accept, `401` to reject. |
| `POST` | `/ws/message/{connectionId}` | Receive a frame the client sent. |
| `POST` | `/ws/disconnected/{connectionId}` | Disconnect notification (best-effort). |

## Build & run

Built with Maven; produces a WAR to deploy to a Jakarta EE 11 runtime (Tomcat 11,
JDK 25). From the repository root:

```bash
mvn clean package        # build all modules; the gateway WAR is under wsgw/target/
mvn verify               # run unit tests (Surefire) + integration tests (Failsafe)
```

Configuration is via environment variables, read at startup:

| Variable | Default | Description |
|---|---|---|
| `APP_BASE_URL` | — | Base URL of the backend (e.g. `http://app:8080`). **Required.** |
| `APPWARD_DISPATCHER_QUEUE_SIZE` | `1024` | Per-connection buffer bound for frames relayed to the backend. |

## Backpressure

wsgw makes backpressure **explicit**: the signals it emits (429 / 503 / 504), the knobs that
tune them, and the metrics that expose congestion are specified — with current
implementation status — in **[docs/backpressure.md](docs/backpressure.md)**.

## Repository layout

| Module | Purpose |
|---|---|
| `wsgw/` | The gateway itself. |
| `wsgw-contract/` | Shared path/constant definitions for the wsgw↔backend contract. |
| `e2e/app/` | A reference backend used by the end-to-end suite. |

## Non-goals

- **Authentication** — delegated entirely to the backend's `/ws/connect`.
- **TLS termination** — expected from a load balancer or sidecar.
- **Message persistence / delivery guarantees** — undelivered frames surface as
  errors to the backend; retry and durability are the backend's concern.
- **Horizontal scaling of wsgw itself** — intended to run as a single instance
  per application. Scale the application; treat wsgw as a small piece of stateful
  glue.

## Status

Early-stage re-port. The wire contract is stable enough for the integration and
e2e suites; expect breaking changes elsewhere. See the Go original
[`pdkovacs/wsgw`](https://github.com/pdkovacs/wsgw) for the more mature reference.
