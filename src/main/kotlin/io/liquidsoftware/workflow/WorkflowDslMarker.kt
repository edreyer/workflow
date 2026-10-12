package io.liquidsoftware.workflow

/**
 * Marks the receivers of the workflow DSL builders.
 *
 * Without this marker, a nested block such as `parallel { ... }` can silently resolve
 * `then(...)` against the enclosing use case builder, adding a sequential step instead of a
 * parallel one. With it, the compiler only offers the innermost builder's members.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class WorkflowDsl
