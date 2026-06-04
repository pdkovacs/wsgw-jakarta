# wsgw-e2eapp — Java/Quarkus reference backend for wsgw

A Quarkus/Java 21 backend that implements the contract expected by [`pdkovacs/wsgw`](https://github.com/pdkovacs/wsgw). Sibling to [`wsgw-node-ref`](https://github.com/pdkovacs/wsgw-node-ref); same routes, same env-var names, same pluggable connection-tracker abstraction.

## Iteration status

**Iteration 1 (current) — MVP.**

- App module only; smoke-test and DynamoDB/Valkey trackers are stubbed for iteration 2.
- `/ws/connect`, `/ws/message`, `/ws/disconnected` under `/ws`.
- `/api/message`, `/api/messages-in-bulk` under `/api`.
- `/app-info`, `/config`, `/user`, `/users`.
- Basic Auth from `E2EAPP_PASSWORD_CREDENTIALS`.
- In-memory connection tracker.
- Plain JDK `HttpClient` (HTTP/1.1) toward wsgw.

**Iteration 2 (planned).**

- DynamoDB + Valkey trackers (drop-in arms in `WsConnectionsFactory`).
- OpenTelemetry via `quarkus-opentelemetry` (the same `OTEL_*` env vars as the Node ref).
- Server-side h2c (`quarkus.http.http2`) and client-side `HTTP_2` in `WsgwClient`.
- `smoke-test/` module — full round-trip through wsgw via `java.net.http.WebSocket`.
- `deploy/k8s/` manifests + container image build.

## Prerequisites

- JDK 25 (current LTS). Java 25 is required; the build pins `<maven.compiler.release>25</maven.compiler.release>`.
- Maven 3.9+ — install with `dnf install maven` / `brew install maven`. To bootstrap the Maven wrapper after install, run `mvn -N wrapper:wrapper -Dmaven=3.9.9` from the repo root; the `taskfile.yaml` can then be switched from `mvn` to `./mvnw`.

## Quick start

```bash
task run            # builds & runs in Quarkus dev mode on :45678
```

`task run` generates 30 demo users (`user1..user30` / `crixcrax1..crixcrax30`) and expects wsgw at `localhost:45679`.

To point at a different gateway:

```bash
export E2EAPP_SERVER_PORT=45678
export E2EAPP_WSGW_HOST=localhost
export E2EAPP_WSGW_PORT=45679
export E2EAPP_PASSWORD_CREDENTIALS='[{"username":"alice","password":"hunter2"}]'
mvn -pl app -am quarkus:dev
```

## Endpoint reference

Mirrors `wsgw-node-ref` — see that repo's README for the wire-level diagram and rationale.

| Method | Path | Notes |
|---|---|---|
| `GET`  | `/ws/connect` | 200 / 400 (no conn-id header) / 401 / 403 (no session user). |
| `POST` | `/ws/message` | Logs the inbound frame; 200. |
| `POST` | `/ws/disconnected` | Drops the connection; 200. |
| `POST` | `/api/message` | Fan-out to one recipient; evicts on 404 from wsgw. 204. |
| `POST` | `/api/messages-in-bulk` | Same, list payload. 204. |
| `GET`  | `/app-info` | Public. Reads `META-INF/build-info.properties`. |
| `GET`  | `/config` | Returns the configured `WsgwLocator`. |
| `GET`  | `/user`, `/users` | Basic Auth required. |

## Configuration

All env vars prefixed `E2EAPP_`, same as the Node ref.

| Variable | Default | Notes |
|---|---|---|
| `E2EAPP_SERVER_PORT` | `8080` | Listening port. |
| `E2EAPP_HTTP2` | `false` | Iteration 2 will honor this for h2c. |
| `E2EAPP_PASSWORD_CREDENTIALS` | — | **Required.** JSON array of `{username, password}`. |
| `E2EAPP_WSGW_HOST` | — | **Required.** wsgw host. |
| `E2EAPP_WSGW_PORT` | — | **Required.** wsgw port. |
| `E2EAPP_CONNECTION_TRACKING` | `in-memory` | Iteration 1 supports only `in-memory`. |
| `E2EAPP_CONNECTION_TRACKING_URL` | — | Forbidden for `in-memory`. |

## Build

```bash
mvn -pl app -am package           # produces app/target/quarkus-app/
mvn -pl app -am quarkus:dev       # live-reload dev mode
```

## Layout

```
wsgw-e2eapp/
├── pom.xml                                          # parent
├── taskfile.yaml
├── app/
│   ├── pom.xml
│   └── src/main/
│       ├── java/io/github/pdkovacs/wsgw/refapp/
│       │   ├── config/        AppConfig, PasswordCredentials, ConnectionTrackingConfig
│       │   ├── common/        DTOs + wsgw path/header constants
│       │   ├── http/          JAX-RS resources + filters
│       │   ├── security/      UserService, UserInfo
│       │   ├── conntrack/     WsConnections interface, factory, in-memory impl
│       │   └── push/          WsgwClient (JDK HttpClient wrapper)
│       └── resources/
│           ├── application.properties
│           └── resources-filtered/META-INF/build-info.properties
└── (iter 2) smoke-test/, deploy/k8s/
```
