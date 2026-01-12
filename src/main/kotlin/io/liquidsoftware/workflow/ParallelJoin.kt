package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.reflect.KClass

/**
 * Controls how parallelJoin handles branch failures.
 */
enum class ParallelErrorPolicy {
  /**
   * Wait for all branches and return the first error by parameter order.
   */
  WaitAll,
  /**
   * Cancel remaining branches on the first failure and return that error.
   */
  FailFast
}

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = parallelJoin(a, b, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b),
  eventTypes = listOf(A::class, B::class),
  policy = policy,
  id = buildParallelJoinId(a, b),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(events[0] as A, events[1] as B)
  }
)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c),
  eventTypes = listOf(A::class, B::class, C::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(events[0] as A, events[1] as B, events[2] as C)
  }
)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, reified D : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, reified D : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d),
  eventTypes = listOf(A::class, B::class, C::class, D::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(events[0] as A, events[1] as B, events[2] as C, events[3] as D)
  }
)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, reified D : Event, reified E : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, e, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowInput, reified A : Event, reified B : Event, reified C : Event, reified D : Event, reified E : Event, R : Event> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d, e),
  eventTypes = listOf(A::class, B::class, C::class, D::class, E::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(events[0] as A, events[1] as B, events[2] as C, events[3] as D, events[4] as E)
  }
)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  crossinline merge: (A, B, C, D, E, F) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, e, f, ParallelErrorPolicy.WaitAll, merge)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d, e, f),
  eventTypes = listOf(A::class, B::class, C::class, D::class, E::class, F::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(
      events[0] as A,
      events[1] as B,
      events[2] as C,
      events[3] as D,
      events[4] as E,
      events[5] as F
    )
  }
)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  crossinline merge: (A, B, C, D, E, F, G) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, e, f, g, ParallelErrorPolicy.WaitAll, merge)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F, G) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d, e, f, g),
  eventTypes = listOf(A::class, B::class, C::class, D::class, E::class, F::class, G::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f, g),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(
      events[0] as A,
      events[1] as B,
      events[2] as C,
      events[3] as D,
      events[4] as E,
      events[5] as F,
      events[6] as G
    )
  }
)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  reified H : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  h: Workflow<I, H>,
  crossinline merge: (A, B, C, D, E, F, G, H) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, e, f, g, h, ParallelErrorPolicy.WaitAll, merge)

inline fun <
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  reified H : Event,
  R : Event
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  h: Workflow<I, H>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F, G, H) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d, e, f, g, h),
  eventTypes = listOf(A::class, B::class, C::class, D::class, E::class, F::class, G::class, H::class),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f, g, h),
  merge = { events ->
    @Suppress("UNCHECKED_CAST")
    merge(
      events[0] as A,
      events[1] as B,
      events[2] as C,
      events[3] as D,
      events[4] as E,
      events[5] as F,
      events[6] as G,
      events[7] as H
    )
  }
)

inline fun <UCC : UseCaseCommand, I : WorkflowInput, reified A : Event, reified B : Event, R : Event>
  WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, merge)

inline fun <UCC : UseCaseCommand, I : WorkflowInput, reified A : Event, reified B : Event, R : Event>
  WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  crossinline merge: (A, B, C, D, E, F) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  crossinline merge: (A, B, C, D, E, F, G) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F, G) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, policy, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  reified H : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  h: Workflow<I, H>,
  crossinline merge: (A, B, C, D, E, F, G, H) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, h, merge)

inline fun <
  UCC : UseCaseCommand,
  I : WorkflowInput,
  reified A : Event,
  reified B : Event,
  reified C : Event,
  reified D : Event,
  reified E : Event,
  reified F : Event,
  reified G : Event,
  reified H : Event,
  R : Event
> WorkflowChainBuilderFactory<UCC>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  f: Workflow<I, F>,
  g: Workflow<I, G>,
  h: Workflow<I, H>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E, F, G, H) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, f, g, h, policy, merge)

fun WorkflowResult.requireSingleEvent(eventClass: KClass<out Event>): Either<WorkflowError, Event> {
  val matches = events.filter { eventClass.isInstance(it) }
  return when (matches.size) {
    1 -> Either.Right(matches.first())
    0 -> Either.Left(
      WorkflowError.ExecutionError(
        "parallelJoin expected event ${eventClass.simpleName ?: "UnknownEvent"} but none were emitted"
      )
    )
    else -> Either.Left(
      WorkflowError.ExecutionError(
        "parallelJoin expected a single ${eventClass.simpleName ?: "UnknownEvent"} but found ${matches.size}"
      )
    )
  }
}

inline fun <reified E : Event> WorkflowResult.requireSingleEvent(): Either<WorkflowError, E> {
  return requireSingleEvent(E::class).map {
    @Suppress("UNCHECKED_CAST")
    it as E
  }
}

@PublishedApi internal fun <I : WorkflowInput, R : Event> parallelJoinInternal(
  workflows: List<Workflow<I, out Event>>,
  eventTypes: List<KClass<out Event>>,
  policy: ParallelErrorPolicy,
  id: String,
  merge: (List<Event>) -> R
): Workflow<I, R> {
  require(workflows.size == eventTypes.size) {
    "parallelJoin requires an event type for each workflow"
  }
  return ParallelJoinWorkflow(
    id = id,
    workflows = workflows,
    eventTypes = eventTypes,
    policy = policy,
    merge = merge
  )
}

@PublishedApi internal fun buildParallelJoinId(vararg workflows: Workflow<*, *>): String {
  return "parallelJoin(${workflows.joinToString(",") { it.id }})"
}

private class ParallelJoinWorkflow<I : WorkflowInput, R : Event>(
  override val id: String,
  private val workflows: List<Workflow<I, out Event>>,
  private val eventTypes: List<KClass<out Event>>,
  private val policy: ParallelErrorPolicy,
  private val merge: (List<Event>) -> R
) : Workflow<I, R>() {
  override suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult> = either {
    val results = runParallel(input, workflows, policy).bind()
    val extractedEvents = results.mapIndexed { index, result ->
      result.requireSingleEvent(eventTypes[index]).bind()
    }
    val mergedEvent = merge(extractedEvents)
    val mergedEvents = results.flatMap { it.events } + mergedEvent
    val mergedContext = results.fold(WorkflowContext()) { acc, result ->
      acc.combine(result.context)
    }
    WorkflowResult(mergedEvents, mergedContext)
  }
}

private suspend fun <I : WorkflowInput> runParallel(
  input: I,
  workflows: List<Workflow<I, out Event>>,
  policy: ParallelErrorPolicy
): Either<WorkflowError, List<WorkflowResult>> {
  return when (policy) {
    ParallelErrorPolicy.WaitAll -> runParallelWaitAll(input, workflows)
    ParallelErrorPolicy.FailFast -> runParallelFailFast(input, workflows)
  }
}

private suspend fun <I : WorkflowInput> runParallelWaitAll(
  input: I,
  workflows: List<Workflow<I, out Event>>
): Either<WorkflowError, List<WorkflowResult>> = coroutineScope {
  val deferredResults = workflows.map { workflow ->
    async { workflow.execute(input) }
  }
  val results = deferredResults.awaitAll()
  val firstError = results.filterIsInstance<Either.Left<WorkflowError>>().firstOrNull()
  if (firstError != null) {
    Either.Left(firstError.value)
  } else {
    val values = results.map { (it as Either.Right<WorkflowResult>).value }
    Either.Right(values)
  }
}

private suspend fun <I : WorkflowInput> runParallelFailFast(
  input: I,
  workflows: List<Workflow<I, out Event>>
): Either<WorkflowError, List<WorkflowResult>> = coroutineScope {
  if (workflows.isEmpty()) {
    return@coroutineScope Either.Right(emptyList())
  }

  val deferreds = workflows.map { workflow ->
    async { workflow.execute(input) }
  }
  val indexByDeferred = deferreds.withIndex().associate { it.value to it.index }
  val results = MutableList<Either<WorkflowError, WorkflowResult>?>(deferreds.size) { null }
  val pending = deferreds.toMutableSet()
  var failureObserved = false

  while (pending.isNotEmpty() && !failureObserved) {
    val (completed, result) = select<Pair<Deferred<Either<WorkflowError, WorkflowResult>>, Either<WorkflowError, WorkflowResult>>> {
      pending.forEach { deferred ->
        deferred.onAwait { value -> deferred to value }
      }
    }
    pending.remove(completed)
    results[indexByDeferred.getValue(completed)] = result
    if (result is Either.Left) {
      failureObserved = true
    }
  }

  if (failureObserved) {
    pending.forEach { it.cancel() }
    pending.forEach { it.join() }
    deferreds.forEach { deferred ->
      if (!deferred.isCancelled && deferred.isCompleted) {
        val result = runCatching { deferred.await() }.getOrNull()
        if (result != null) {
          results[indexByDeferred.getValue(deferred)] = result
        }
      }
    }
    val error = results.mapIndexedNotNull { index, result ->
      (result as? Either.Left<WorkflowError>)?.let { index to it.value }
    }.minByOrNull { it.first }?.second
      ?: WorkflowError.ExecutionError("parallelJoin failed")
    Either.Left(error)
  } else {
    val values = results.map { (it as Either.Right<WorkflowResult>).value }
    Either.Right(values)
  }
}
