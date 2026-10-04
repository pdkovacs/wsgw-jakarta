# Contributing

## Code conventions

### Grouping members: `// --- label ---` dividers

When a class holds members of more than one kind (or more than one flow), group them under
divider comments. This is the only grouping pattern in the repository; do not use `// region`,
`// ====` banners or other styles.

```java
    // --- flow=push, site=gw_to_client (docs/backpressure.md §2.3.2) ---

    public Duration getSendLockTimeout() { ... }
```

Rules:

- **Form**: `// --- label ---`: three dashes, one space, label, one space, three dashes. Lowercase
  label unless it contains an identifier or a proper name.
- **Placement**: on its own line, at the indentation of the members it groups, with a blank line
  before and after. A group runs until the next divider or the end of the class.
- **Granularity**: members only (fields, methods, nested types). Don't use dividers inside method
  bodies.
- **Only when it earns its place**: a class with a single group needs no divider.
- **Order of groups**: keep the same groups in the same order wherever a class repeats them (e.g.
  a field block followed by the accessor block, as in `Configuration`).

### Choosing labels

- **Test classes**: `// --- helpers ---` separates test methods from the private helpers below them.
- **Anything tied to congestion handling** is grouped by the frame in
  [docs/backpressure.md](docs/backpressure.md) §2.1: `flow=<push|connect|relay>, site=<site>`,
  using the metric-tag vocabulary verbatim. Add the doc section in parentheses where it helps,
  or who consumes the group, e.g. `(asserted on by PushTest)`. Things the frame doesn't cover get
  a plain descriptive label (`// --- the connection registry, by lifecycle state ---`).
- **Configuration**: `// --- base ---` for settings that belong to no flow; the shared
  registration gate and each flow/site get their own group. A knob lives in the group of the hop
  whose doc table (§2.3.2, §2.4.1, §2.4.2, §2.5.1) lists it.
- Follow the project's vocabulary (see `CLAUDE.md`): session vs connection, flow vs hop vs site.

### Test phases

Inside a test method, `// ARRANGE` / `// ACT` / `// ASSERT` may mark the phases. That is a
separate, optional convention for method bodies, not for grouping members.
