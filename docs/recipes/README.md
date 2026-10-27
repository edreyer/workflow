# Recipes

Short, copyable answers to "how do I use this with …". The [tutorial](../tutorial/README.md)
teaches the library from the ground up; these start from the framework or the problem you
already have.

| Recipe | What it shows | Compiled |
| --- | --- | --- |
| [Ktor](ktor.md) | Calling a use case from a route, the call ID as correlation ID, and errors as HTTP responses or problem details | No |
| [Spring Boot](spring.md) | Use cases as beans, a `suspend` controller, and `ProblemDetail` responses | No |
| [Blocking and Java callers](blocking-callers.md) | `runBlocking` at the edge, and a `CompletableFuture` facade for Java | Yes |
| [Micrometer metrics](micrometer.md) | One `onSummary` listener that times every use case and step, tagged by outcome and error | Yes |
| [Publishing domain events](domain-events.md) | Publishing events after success, and a transactional outbox | Yes |
| [Transactions](transactions.md) | Why a transaction belongs in one step, with Spring JDBC, Spring R2DBC and Exposed | No |
| [Validating input with impossible](impossible.md) | Parsing raw input into domain types in `startWith`, one error or every field at once | No |

Compiled recipes keep their code in
[`src/test/kotlin/io/liquidsoftware/workflow/recipes/`](../../src/test/kotlin/io/liquidsoftware/workflow/recipes/),
and `RecipeSnippetsTest` fails the build if a snippet on their page drifts from it.
Recipes marked "No" are written against the frameworks' public APIs as of late 2026
(Ktor 3, Spring Boot 3 and 4, Exposed 1.x, impossible 0.1.0) but are not built in this
repository; check them against the versions you use.
