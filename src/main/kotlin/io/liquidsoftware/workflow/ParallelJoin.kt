package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

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

inline fun <I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, R : WorkflowState> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = parallelJoin(a, b, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, R : WorkflowState> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b),
  policy = policy,
  id = buildParallelJoinId(a, b),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(states[0] as A, states[1] as B)
  }
)

inline fun <I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, reified C : WorkflowState, R : WorkflowState> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, ParallelErrorPolicy.WaitAll, merge)

inline fun <I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, reified C : WorkflowState, R : WorkflowState> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c),
  policy = policy,
  id = buildParallelJoinId(a, b, c),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(states[0] as A, states[1] as B, states[2] as C)
  }
)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  R : WorkflowState
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, ParallelErrorPolicy.WaitAll, merge)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  R : WorkflowState
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(states[0] as A, states[1] as B, states[2] as C, states[3] as D)
  }
)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  R : WorkflowState
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = parallelJoin(a, b, c, d, e, ParallelErrorPolicy.WaitAll, merge)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  R : WorkflowState
> parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, C>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, C, D, E) -> R,
): Workflow<I, R> = parallelJoinInternal(
  workflows = listOf(a, b, c, d, e),
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(states[0] as A, states[1] as B, states[2] as C, states[3] as D, states[4] as E)
  }
)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  R : WorkflowState
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
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  R : WorkflowState
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
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(states[0] as A, states[1] as B, states[2] as C, states[3] as D, states[4] as E, states[5] as F)
  }
)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  reified G : WorkflowState,
  R : WorkflowState
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
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  reified G : WorkflowState,
  R : WorkflowState
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
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f, g),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(
      states[0] as A,
      states[1] as B,
      states[2] as C,
      states[3] as D,
      states[4] as E,
      states[5] as F,
      states[6] as G
    )
  }
)

inline fun <
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  reified G : WorkflowState,
  reified H : WorkflowState,
  R : WorkflowState
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
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified C : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  reified F : WorkflowState,
  reified G : WorkflowState,
  reified H : WorkflowState,
  R : WorkflowState
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
  policy = policy,
  id = buildParallelJoinId(a, b, c, d, e, f, g, h),
  merge = { states ->
    @Suppress("UNCHECKED_CAST")
    merge(
      states[0] as A,
      states[1] as B,
      states[2] as C,
      states[3] as D,
      states[4] as E,
      states[5] as F,
      states[6] as G,
      states[7] as H
    )
  }
)

inline fun <C : UseCaseCommand, I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, R : WorkflowState>
  WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, merge)

inline fun <C : UseCaseCommand, I : WorkflowState, reified A : WorkflowState, reified B : WorkflowState, R : WorkflowState>
  WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, policy, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  crossinline merge: (A, B, CState) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, CState) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, policy, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  reified D : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  d: Workflow<I, D>,
  crossinline merge: (A, B, CState, D) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  reified D : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  d: Workflow<I, D>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, CState, D) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, policy, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  crossinline merge: (A, B, CState, D, E) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, merge)

inline fun <
  C : UseCaseCommand,
  I : WorkflowState,
  reified A : WorkflowState,
  reified B : WorkflowState,
  reified CState : WorkflowState,
  reified D : WorkflowState,
  reified E : WorkflowState,
  R : WorkflowState
> WorkflowChainBuilderFactory<C>.parallelJoin(
  a: Workflow<I, A>,
  b: Workflow<I, B>,
  c: Workflow<I, CState>,
  d: Workflow<I, D>,
  e: Workflow<I, E>,
  policy: ParallelErrorPolicy,
  crossinline merge: (A, B, CState, D, E) -> R,
): Workflow<I, R> = io.liquidsoftware.workflow.parallelJoin(a, b, c, d, e, policy, merge)

@PublishedApi
internal fun <I : WorkflowState, R : WorkflowState> parallelJoinInternal(
  workflows: List<Workflow<I, out WorkflowState>>,
  policy: ParallelErrorPolicy,
  id: String,
  merge: (List<WorkflowState>) -> R
): Workflow<I, R> {
  return ParallelJoinWorkflow(
    id = id,
    workflows = workflows,
    policy = policy,
    merge = merge
  )
}

@PublishedApi
internal fun buildParallelJoinId(vararg workflows: Workflow<*, *>): String {
  return "parallelJoin(${workflows.joinToString(",") { it.id }})"
}

private class ParallelJoinWorkflow<I : WorkflowState, R : WorkflowState>(
  override val id: String,
  private val workflows: List<Workflow<I, out WorkflowState>>,
  private val policy: ParallelErrorPolicy,
  private val merge: (List<WorkflowState>) -> R
) : Workflow<I, R>() {
  override suspend fun executeWorkflow(input: I): Either<WorkflowError, WorkflowResult<R>> = either {
    val results = runParallel(input, workflows, policy).bind()
    val mergedState = merge(results.map { it.state })
    val mergedEvents = results.flatMap { it.events }
    val mergedContext = results.fold(WorkflowContext()) { acc, result -> acc.combine(result.context) }
    WorkflowResult(mergedState, mergedEvents, mergedContext)
  }
}

private suspend fun <I : WorkflowState> runParallel(
  input: I,
  workflows: List<Workflow<I, out WorkflowState>>,
  policy: ParallelErrorPolicy
): Either<WorkflowError, List<WorkflowResult<out WorkflowState>>> {
  return when (policy) {
    ParallelErrorPolicy.WaitAll -> runParallelWaitAll(input, workflows)
    ParallelErrorPolicy.FailFast -> runParallelFailFast(input, workflows)
  }
}

private suspend fun <I : WorkflowState> runParallelWaitAll(
  input: I,
  workflows: List<Workflow<I, out WorkflowState>>
): Either<WorkflowError, List<WorkflowResult<out WorkflowState>>> = coroutineScope {
  val deferredResults = workflows.map { workflow ->
    async { workflow.execute(input) }
  }
  val results = deferredResults.awaitAll()
  val firstError = results.filterIsInstance<Either.Left<WorkflowError>>().firstOrNull()
  if (firstError != null) {
    Either.Left(firstError.value)
  } else {
    val values = results.map { (it as Either.Right<WorkflowResult<WorkflowState>>).value }
    Either.Right(values)
  }
}

private suspend fun <I : WorkflowState> runParallelFailFast(
  input: I,
  workflows: List<Workflow<I, out WorkflowState>>
): Either<WorkflowError, List<WorkflowResult<out WorkflowState>>> = coroutineScope {
  if (workflows.isEmpty()) {
    return@coroutineScope Either.Right(emptyList())
  }

  val deferreds = workflows.map { workflow ->
    async { workflow.execute(input) }
  }
  val indexByDeferred = deferreds.withIndex().associate { it.value to it.index }
  val results = MutableList<Either<WorkflowError, WorkflowResult<out WorkflowState>>?>(deferreds.size) { null }
  val pending = deferreds.toMutableSet()
  var failureObserved = false

  while (pending.isNotEmpty() && !failureObserved) {
    val (completed, result) = select<Pair<Deferred<Either<WorkflowError, WorkflowResult<out WorkflowState>>>, Either<WorkflowError, WorkflowResult<out WorkflowState>>>> {
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
    val values = results.map { (it as Either.Right<WorkflowResult<WorkflowState>>).value }
    Either.Right(values)
  }
}
