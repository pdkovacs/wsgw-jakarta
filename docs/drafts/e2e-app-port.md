# Rebuilding e2e-app on embedded Tomcat

Session of 2026-10-17, as of commit `6ced75e`; revised 2026-10-18, when the in-place port
became a rebuild in a sibling module (§4). This is a plan, not a spec. The decisions in §3
are settled.

---

## 1. Goal and why

e2e-app (`e2e/app`) should run on the gateway's model: embedded Tomcat with `useVirtualThreads`, plain
servlets, manual wiring, and SLF4J/logstash logging. When a number from an e2e run looks
odd, there should then be only one backpressure model to suspect, not two.

e2e-app is a **test fixture**, not a reference app. Its job is to be predictable,
instrumented and the same as the gateway, so that its own behaviour never explains a
number. Showing that the contract holds against a stack the gateway didn't choose is a
different job. That belongs to a separate app that is different on purpose. The Quarkus
app, now `e2e/quarkus-app`, stays beside e2e-app and may turn out to be that app; whether
it stays for good is open. `wsgw-node-ref` may be another, if it still matches this
gateway's contract (not checked).

The Quarkus app is not an event-loop app either:

- Netty handles network I/O.
- `quarkus-rest` hands resource methods with ordinary (non-`Uni`) signatures to the
  Quarkus worker pool. The pool has a fixed number of threads and, as far as I know, an
  unbounded queue by default.
- Pushes go out through the JDK `HttpClient`.

That's three models, and the worker pool is the implicit concurrency cap the gateway gave
up when it moved to virtual threads.

**e2e-app behaves differently from the Quarkus app in one place: its answers to the
gateway.** The Quarkus app doesn't answer the routes the gateway calls
(`relay-app-answers.md` §1.1); e2e-app's servlets do (§4). Anything else that changes
behaviour (§6) comes afterwards, in separate commits.

**Out of scope:** the e2e-driver, runnable packaging and container images, the
DynamoDB/Valkey trackers, h2c.

## 2. What replaces what

e2e-app uses none of the Quarkus pieces below; the last column is what it uses instead.

| Quarkus piece | Where it's used | Replacement |
|---|---|---|
| `quarkus-rest` (JAX-RS routing) | 6 `*Resource` classes | `HttpServlet`s on a Tomcat `Context`, set up in code the way `Wsgw.start()` and `FakeApp.start()` do it |
| `quarkus-rest-jackson` | request and response bodies | a single `ObjectMapper`, already used in `AppConfig` and `WsgwClient`; add `jackson-databind` explicitly, since the Quarkus BOM supplies it today |
| `quarkus-arc` (CDI) | `@Singleton`/`@ApplicationScoped`, the `@Produces` in `WsConnectionsFactory`, `@PostConstruct`/`@PreDestroy` | constructor wiring in one `E2eApp` class with `start()`/`stop()` |
| `@RequestScoped RequestUser` | auth filter → resources | a request attribute set by the auth filter |
| MicroProfile `@ConfigProperty` | `AppConfig` | `Config.fromEnv(Env)` in the style of the gateway's `Configuration`, with `Env` from `wsgw-common` |
| `ContainerRequestFilter`s | `BasicAuthFilter`, `RequestContextFilter` | servlet `HttpFilter`s; clear the MDC in `finally` instead of in a response filter |
| JBoss logging/MDC + `quarkus-logging-json` | `CtxLogger`, `RequestContextFilter` | the gateway's SLF4J `CtxLogger` + logback/logstash |
| `@QuarkusTest`, `@QuarkusIntegrationTest`, RestAssured | 3 test classes | start `E2eApp` on port 0 and call it with the JDK `HttpClient`, like the gateway's ITs |
| `quarkus-maven-plugin`, the BOM, `quarkus.test.env.*`, `java.util.logging.manager` | `e2e/quarkus-app/pom.xml` | nothing; tests construct `AppConfig` directly, so there's no env-var plumbing |
| `quarkus:dev` | `e2e/quarkus-app/taskfile.yaml` `run` | `exec:java` or similar in e2e-app's own taskfile; no live reload |

The `git-commit-id` / `build-info.properties` filtering is plain Maven and is copied as it is.

## 3. Decisions

All three were decided on 2026-10-17.

### 3.1 Route handlers: servlets, not filters that end the chain (decided)

Both are Servlet API. The question is which kind of component handles a route and writes
the response.

The gateway's route filters work in two different ways:

- `ConnectionRequest` is a filter in the usual sense. It does its work, then calls
  `chain.doFilter(...)` so Tomcat's `WsFilter` can do the upgrade. It has to be a filter.
- `MessageRequest` and `DisconnectRequest` are endpoints. They write the response and
  never call the chain. The `DefaultServlet` at `/` exists only so the chain has an end.

**Decision:** one `HttpServlet` per route group (`doGet` / `doPost`). Filters are used only
where passing the request on is the job (auth, MDC). This is the rule of least power: use
the least powerful construct that does the job. A filter can do everything a servlet can
and also pass the request on, which an endpoint never needs. Servlets give you three
things here:

1. **No silent 404 if a handler forgets to return.** In the filter style, a handler that
   falls through lands in `DefaultServlet` and gets a 404. `FromWsgwFilters.Authentication`
   has a comment warning about exactly that.
2. **Wrong methods are rejected automatically.** An `HttpServlet` that overrides only
   `doPost` answers other methods with 405.
3. **§3.2 depends on it.** With filters as endpoints, every request maps to the `default`
   servlet.

**Path parsing changes.** With a servlet mapped at `/message/*`, `getServletPath()` is
`/message` and the id is in `getPathInfo()`. The gateway's
`ConnectionIdExtractor.extract(req.getServletPath(), 1)` only works because its requests
end at `DefaultServlet` on `/`, which makes `servletPath` the whole path.

**The gateway is out of scope.** By the same rule, `MessageRequest` and `DisconnectRequest`
would be servlets too, and `ConnectionRequest` stays a filter. That's a separate change,
not part of this port.

### 3.2 Exempting public routes from authentication (decided)

`BasicAuthFilter` lets requests through without credentials when the resource carries
e2e-app's own `@Public` annotation. Today only `/app-info` does. The filter finds the
annotation through JAX-RS `ResourceInfo`, which servlets don't have.

**Decision:** mark public routes when you register them. A helper such as
`addServlet(ctx, name, servlet, pattern, Access.PUBLIC)` adds the servlet's name to a set.
The auth filter is mapped to `/*` and lets a request through without credentials only if
`req.getHttpServletMapping().getServletName()` is in that set. `@Public` and its
reflection go away.

The alternatives:

- **Map the auth filter only to the protected paths.** It's simplest, since Tomcat does
  the matching, but it fails open: a new route that isn't added to the list needs no
  credentials. (This would not bring back the old bug, which came from comparing path
  strings by hand.)
- **Keep `@Public` on the servlet class**, read when the servlet is registered. It fails
  closed like the decision, but it adds reflection for the sake of where the marker sits,
  and with one public route that isn't worth it.

**Verified on Tomcat 11.0.22** (trial, 2026-10-17): a filter mapped to `/*` sees the
target servlet's name from `getHttpServletMapping().getServletName()`. A path that no
servlet matches never reaches the filter, because Tomcat answers 404 first, so the
design fails closed there too.

### 3.3 Where `CtxLogger` and `Env` live: `wsgw-common` (decided, revised 2026-10-18)

Both are in a new `wsgw-common` module. The gateway, e2e-app and, later, the e2e-driver
all run embedded Tomcat with SLF4J and read their configuration the same way, so they share
one copy. `wsgw-contract` stays the wire contract only. The first decision (2026-10-17) was
to copy both into e2e-app.

- **`CtxLogger`** kept its package, `io.github.pdkovacs.wsgw.logging`, so no imports
  changed. The module depends only on `slf4j-api`; each app brings its own backend
  (logback + logstash, as the gateway does).
- **`Env`** moved to `io.github.pdkovacs.wsgw.config` and became public. It is now an
  instance over an injected lookup: `Env.system()` reads the process environment, and
  `Env.of(map::get)` lets a test drive `fromEnv(Env)` without one. Each `fromEnv()` is
  kept as a shorthand for `fromEnv(Env.system())`.

## 4. Steps

e2e-app is built anew in a sibling module, not ported in place. Quarkus can't run
servlets, and its logging setup can't share a module with logback, so a module that is half
one and half the other never builds. Step 0 sets up the sibling, and e2e-app then grows one
gateway↔app interaction at a time, copying from the Quarkus app where that helps.

Each slice is one commit: the servlet(s) for one interaction plus an IT that drives it
through a real gateway. Early slices are allowed to be oversimplified (hardcoded config, no
auth, connections not tied to users); later slices replace those parts instead of
preserving them. The §2 table is what e2e-app has by the end, not the contents of one
commit.

### Open decision: how an IT gets a running gateway

Needed before slice 1. e2e-app itself always starts in-process (`E2eApp` on port 0), since
the ITs live in its module.

- **In-process:** the IT starts `Wsgw` too, the way the gateway's own ITs start `FakeApp`.
  But `wsgw` is packaged as a `war`, so other modules can't put its classes on their
  classpath as an ordinary dependency. It needs `attachClasses` on the gateway's war plugin
  (a `-classes` jar), which touches the gateway's POM.
- **External:** the ITs connect to a gateway started separately, at `e2e.wsgw.host` /
  `e2e.wsgw.port` (`e2e/pom.xml`). The gateway stays untouched, but `scripts/ci/run.sh`
  would have to start and stop one.

### Step 0: the sibling module (done 2026-10-18)

- `e2e/app` was renamed to `e2e/quarkus-app` (artifact `wsgw-e2e-quarkus-app`); its code is
  unchanged. Its Java package is still `io.github.pdkovacs.wsgw.e2e.app`, the same as
  e2e-app's; the two never share a classpath.
- The Quarkus BOM, the Quarkus plugin and the Quarkus-specific surefire/failsafe config
  moved from `e2e/pom.xml` into quarkus-app's POM, so e2e-app inherits nothing from
  Quarkus. quarkus-app's resolved dependencies and managed versions are unchanged
  (checked). `e2e/pom.xml` keeps the Jackson BOM and the shared `e2e.*` properties.
- The new `e2e/app` (artifact `wsgw-e2e-app`) depends only on `wsgw-common` so far.
- CI: `E2E_MODULES` lists both modules. quarkus-app's tests still run; e2e-app has none
  until slice 1.

### Slice 1: connect

- `E2eApp` (`start()` / `stop()`) and `Main`; one servlet for the connect route from
  `AppPaths`, answering 204, the only success the gateway accepts. It records the
  `connectionId` and nothing else: no user, since there's no auth yet. Config is passed to
  `E2eApp`'s constructor; nothing is read from the environment yet.
- POM: Tomcat, `wsgw-contract`, logback + logstash, `jackson-databind`; `logback.xml`.
- A `taskfile.yaml` with a `run` task (`exec:java` or similar); quarkus-app keeps its own.
- IT: a client opens a session through the gateway, and the app has seen the connect
  (`clientSessionOpenConnectsAtApp`).

### Slice 2: message, client → app

- A servlet at `/message/*`; the id comes from `getPathInfo()` (§3.1). It answers 200 or
  204, chosen here; `relay-app-answers.md` §1.1 has what depends on the choice.
- IT: a message the client sends arrives at the app.

### Slice 3: disconnect

- A servlet for the disconnect route; the app forgets the `connectionId`.
- IT: `clientSessionCloseDisconnectsAtApp`.

### Slice 4: push, app → client

- `WsgwClient` copied from quarkus-app, without CDI. The push is addressed by `connectionId`:
  it's the oversimplification here, since the app doesn't know users yet.
- IT: the app pushes, and the client receives the message.

### Slice 5 onwards: the real app

One commit each where they don't depend on each other:

- `Config.fromEnv()` (written ahead of the slices, 2026-10-18) wired into `Main`,
  replacing the constructor-passed config.
- `BasicAuthFilter` with the public-route set (§3.2), and the MDC filter as an
  `HttpFilter` that clears the MDC in `finally`. Connections are now tied to the
  authenticated user, which replaces slice 1's bare `connectionId` set.
- `/api/message` and `/api/messages-in-bulk`, pushing to recipients by user. This replaces
  slice 4's push by `connectionId`. The fan-out pool is copied as it is (§6).
- `/app-info`, `/user`, `/users` and `/config`, with the old `/user` and `/app-info` tests
  rewritten as ITs on the JDK `HttpClient`. The Quarkus `CtxLoggerTest` is not copied; it
  tests the MDC route.

### Last: docs

- **e2e-app's README:** describe it as the e2e harness's app (§1), not a reference backend.
- **quarkus-app's README** moved with it. It still says Java 21, and its layout section is
  stale.

## 5. What you give up

- Live reload in dev mode (quarkus-app keeps it).
- JAX-RS conveniences: content negotiation, automatic 400/405/415 responses, `@PathParam`.
- A second stack in the data path. §1 explains why that's intended.

## 6. Afterwards, as separate changes

- **The fan-out pool in `ApiResource`:** 4 platform threads. It caps concurrency on the
  push path and decides how e2e-app adds load to the gateway. Copy it unchanged into
  e2e-app, then decide on purpose whether to keep it as a knob, change it, or remove it.
- **h2c on e2e-app's server side:** with Tomcat this becomes the same `addUpgradeProtocol`
  recipe the gateway uses, which has already been verified. `E2EAPP_HTTP2` is currently
  read but has no effect.
