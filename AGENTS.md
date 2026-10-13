<!-- context7 -->
Use Context7 MCP to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service — even well-known ones like React, Next.js, Prisma, Express, Tailwind, Django, or Spring Boot. This includes API syntax, configuration, version migration, library-specific debugging, setup instructions, and CLI tool usage. Use even when you think you know the answer — your training data may not reflect recent changes. Prefer this over web search for library docs.

Do not use for: refactoring, writing scripts from scratch, debugging business logic, code review, or general programming concepts.

## Steps

1. Always start with `resolve-library-id` using the library name and the user's question, unless the user provides an exact library ID in `/org/project` format
2. Pick the best match (ID format: `/org/project`) by: exact name match, description relevance, code snippet count, source reputation (High/Medium preferred), and benchmark score (higher is better). If results don't look right, try alternate names or queries (e.g., "next.js" not "nextjs", or rephrase the question). Use version-specific IDs when the user mentions a version
3. `query-docs` with the selected library ID and the user's full question (not single words), scoped to a single concept. If the question spans multiple distinct concepts (e.g. routing and auth and caching), make a separate `query-docs` call per concept with the same library ID, unless the question is about how the concepts interact — combined queries dilute ranking and return shallow results for each topic
4. Answer using the fetched docs
<!-- context7 -->

## Working Relationship

- Work as a principal-level engineering partner: co-design and co-build with
  the user, challenge proposals constructively, and explain better or simpler
  alternatives and their tradeoffs.
- Do not silently guess or conceal confusion. State material assumptions,
  uncertainties, and tradeoffs. Ask when ambiguity would materially change the
  result; otherwise make and disclose a reasonable assumption.

## Delivery Discipline

- Prefer the smallest, simplest, most intuitive design that fully satisfies the
  requirements and preserves the system's architecture.
- Write the minimum code needed to solve the problem. Do not add speculative
  features, abstractions, configuration, extension points, cleanup, or related
  work that was not requested.
- Touch only the files and lines required for the task. Preserve unrelated and
  user-authored changes. Clean up only artifacts or test data created by the
  current work.
- Define concrete success criteria before making changes. Iterate until those
  criteria are verified, or report the exact blocker and what remains
  unverified.

## Serena Usage

When Serena tools are available for a coding project:

- Activate the current project before beginning code-oriented work.
- Read Serena's `initial_instructions` once per session before using its
  semantic tools.
- Prefer Serena for symbol structure, targeted code navigation, reference and
  implementation tracing, control-flow analysis, and safe symbol-level edits.
- Prefer shell tools for documentation, configuration, logs, tests, broad text
  searches, and small file reads.
- Use targeted symbolic reads instead of loading entire source files when the
  required context can be obtained more precisely.
- Do not create, edit, rename, or delete Serena memories unless explicitly
  requested.
- Keep durable project guidance in repository-owned documentation rather than
  Serena memories unless explicitly directed otherwise.
- Follow stronger or more specific Serena guidance defined by the repository.

## Software Design Defaults

Apply these principles proportionately to the complexity of the software and
within the conventions of the repository being changed.

- For domain-rich business software, prefer Domain-Driven Design: explicit
  bounded contexts, domain language, value objects, aggregates or policies
  where useful, and clear boundaries between domain and infrastructure.
- Do not introduce DDD ceremony into simple scripts, infrastructure glue,
  straightforward CRUD, or other code where it would add complexity without
  improving correctness or understanding.
- Prefer algebraic data types, such as sealed hierarchies, discriminated unions,
  enums with associated data, and product types, when they express domain
  alternatives more accurately than flags, nullable fields, or loosely related
  primitives.
- Make illegal domain states unrepresentable when practical. Validate untrusted
  input at boundaries, then convert it into types whose invariants are enforced
  by construction.
- Prefer immutable data and explicit state transitions for domain-significant
  behavior.
- Use functional programming techniques when they improve correctness,
  composability, testability, or clarity. Do not introduce functional
  abstraction merely for stylistic purity.
- Represent expected, recoverable failures with explicit typed results when the
  language and surrounding architecture support them.
- Do not use exceptions for ordinary domain decisions, validation outcomes,
  authorization denials, or other expected control flow.
- Exceptions remain appropriate for unexpected failures, programmer errors,
  unrecoverable conditions, and framework contracts that require them.
- Translate infrastructure and framework failures into domain- or
  application-appropriate errors at system boundaries.
- Prefer exhaustive handling of closed state and error hierarchies.
- Follow stronger or more specific architectural rules defined by the
  repository.

## This Repository

`workflow` is a single-module Kotlin library, published as
`io.liquidsoftware:workflow`. It provides a DSL for composing application use
cases from small, typed, testable steps. Sources live in
`src/main/kotlin/io/liquidsoftware/workflow/`, and tests live in
`src/test/kotlin/io/liquidsoftware/workflow/`.

### Commands

```bash
./gradlew build                 # compile, test, and verify
./gradlew test                  # run the test suite
./gradlew test --tests "io.liquidsoftware.workflow.UseCaseTest"   # one class
./gradlew checkKotlinAbi        # fail if the public API differs from api/workflow.api
./gradlew updateKotlinAbi       # regenerate api/workflow.api after an intended API change
./gradlew dependencyUpdates     # list dependency updates
```

The build uses the JDK 25 toolchain and publishes Java 17 bytecode and Gradle
metadata. Explicit API mode is on, so every public declaration needs an
explicit visibility modifier and a public type. Run `updateKotlinAbi` and
`checkKotlinAbi` as separate Gradle invocations.

Tests use `kotlin("test")` on JUnit 5 with `kotlinx-coroutines-test`
(`runTest`). `logback-classic` is the test-scope SLF4J backend.

### Architecture

- `Workflow.kt`: `Workflow<I, O>`, a final class the library builds from an id
  and a step lambda. Users never implement it: steps are functions, wired in with
  `workflow(id) { }`, `guard(id) { }`, or inline `Pipeline.then(id) { }`. The
  lambda's scope, `WorkflowStepScope`, is an Arrow `Raise<WorkflowError>` with
  `emit`, `put`, and `context`. Also defines `@WorkflowDsl`, step execution
  (`runStep`), and the `ExecutionRecorder` that collects executions and
  attributes failures to steps.
- `UseCase.kt`: `useCase { startWith { } ... .result { } }`. `Pipeline<I, E, S>`
  is an immutable typed chain (`then`, `thenIf`, `parallel`, `parallelJoin`,
  `thenLaunch`, `awaitLaunched`), so a step that does not accept the current
  state does not compile. Also holds the runner that executes a pipeline,
  drains launched work, maps errors, and emits the summary.
- `ParallelJoin.kt`: top-level `parallelJoin` for 2 to 8 branches,
  `ParallelErrorPolicy` (`WaitAll`, `FailFast`), and `runBranches`.
- `WorkflowDomain.kt`: `Event`, identity-based `Key<T>`, the immutable
  `WorkflowContext`, `WorkflowResult`, `WorkflowExecution`, and the open
  `WorkflowError` interface with the library's built-in errors.
- `UseCaseLogging.kt` and `UseCaseTelemetry.kt`: debug start and finish logs
  that carry only safe metadata unless fields are added explicitly,
  `useCaseLoggingGlobalConfig { }`, and `UseCaseSummary` for `onSummary`
  listeners. Error messages are redacted unless `withErrorMessages()` is
  called.

Errors are never wrapped. Steps raise `WorkflowError`s, including
application-defined sealed types, and the `errors` mapper receives them as they
were raised. Parallel branches and launched work merge back only the context
keys they changed.

`OrderProcessingExampleTest.kt` and `ReadmeExampleTest.kt` keep the documented
examples compiling and passing. Update them when the public DSL changes.

### Conventions

- A public API change needs `updateKotlinAbi` and the regenerated
  `api/workflow.api` in the same change.
- Keep `Readme.md` and `docs/reference.md` in sync with the public DSL.
- Record user-visible changes in `CHANGELOG.md`. A breaking change also needs
  before and after examples in a migration guide under `docs/migrations/`.
- Codex reviews each pull request when it is opened or marked ready. Address
  its findings, or reply with why not, before merging.
- A release sets `version` in `build.gradle.kts` and dates the `CHANGELOG.md`
  section. The maintainer publishes from `main` with
  `./gradlew publishAllPublicationsToCentralPortal`.
