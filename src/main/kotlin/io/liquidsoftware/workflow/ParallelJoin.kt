package io.liquidsoftware.workflow

import arrow.core.Either
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.selects.select

/**
 * Controls how parallel branches handle failures.
 */
public enum class ParallelErrorPolicy {
  /** Wait for every branch, then fail with the first error by parameter order. */
  WaitAll,

  /**
   * Cancel the remaining branches on the first failure. Fails with the first error by parameter
   * order among the branches that finished.
   */
  FailFast,
}

/**
 * Runs 2 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2) }

/**
 * Runs 3 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3) }

/**
 * Runs 4 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, T4, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  d: Workflow<I, T4>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3, T4) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c, d), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3, states[3] as T4) }

/**
 * Runs 5 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, T4, T5, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  d: Workflow<I, T4>,
  e: Workflow<I, T5>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3, T4, T5) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c, d, e), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3, states[3] as T4, states[4] as T5) }

/**
 * Runs 6 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, T4, T5, T6, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  d: Workflow<I, T4>,
  e: Workflow<I, T5>,
  f: Workflow<I, T6>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3, T4, T5, T6) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c, d, e, f), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3, states[3] as T4, states[4] as T5, states[5] as T6) }

/**
 * Runs 7 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, T4, T5, T6, T7, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  d: Workflow<I, T4>,
  e: Workflow<I, T5>,
  f: Workflow<I, T6>,
  g: Workflow<I, T7>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3, T4, T5, T6, T7) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c, d, e, f, g), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3, states[3] as T4, states[4] as T5, states[5] as T6, states[6] as T7) }

/**
 * Runs 8 workflows concurrently on the same input and merges their outputs with [merge].
 * The input is the receiver of [merge], so the merged state can keep fields of the input.
 */
@Suppress("UNCHECKED_CAST")
public fun <I, T1, T2, T3, T4, T5, T6, T7, T8, R> parallelJoin(
  a: Workflow<I, T1>,
  b: Workflow<I, T2>,
  c: Workflow<I, T3>,
  d: Workflow<I, T4>,
  e: Workflow<I, T5>,
  f: Workflow<I, T6>,
  g: Workflow<I, T7>,
  h: Workflow<I, T8>,
  policy: ParallelErrorPolicy = ParallelErrorPolicy.WaitAll,
  merge: I.(T1, T2, T3, T4, T5, T6, T7, T8) -> R,
): Workflow<I, R> = parallelJoinOf(listOf(a, b, c, d, e, f, g, h), policy) { input, states -> input.merge(states[0] as T1, states[1] as T2, states[2] as T3, states[3] as T4, states[4] as T5, states[5] as T6, states[6] as T7, states[7] as T8) }

private fun <I, R> parallelJoinOf(
  workflows: List<Workflow<I, Any?>>,
  policy: ParallelErrorPolicy,
  merge: (I, List<Any?>) -> R,
): Workflow<I, R> =
  Workflow("parallelJoin(${workflows.joinToString(",") { it.id }})") { input ->
    val results = runBranches(workflows, input, context, recorder, policy) { absorb(it) }.bind()
    merge(input, results.map { it.state })
  }

/**
 * Runs [workflows] concurrently on [input]. Each branch sees [context]. Every branch that succeeds
 * is passed to [absorb], even when another branch fails, so its events and context are not lost.
 * The returned results keep branch order.
 */
internal suspend fun <I> runBranches(
  workflows: List<Workflow<I, Any?>>,
  input: I,
  context: WorkflowContext,
  recorder: ExecutionRecorder,
  policy: ParallelErrorPolicy,
  absorb: (WorkflowResult<Any?>) -> Unit,
): Either<WorkflowError, List<WorkflowResult<Any?>>> = coroutineScope {
  val branchRecorders = workflows.map { recorder.forBranch() }
  val deferreds = workflows.mapIndexed { index, workflow ->
    async { workflow.runStep(input, context, branchRecorders[index]) }
  }
  val outcomes = when (policy) {
    ParallelErrorPolicy.WaitAll -> deferreds.awaitAll()
    ParallelErrorPolicy.FailFast -> failFast(deferreds)
  }
  outcomes.forEach { outcome -> outcome?.onRight { absorb(it.changesSince(context)) } }
  // The first failure by parameter order wins.
  val failed = outcomes.indexOfFirst { it is Either.Left }
  if (failed >= 0) {
    val error = (outcomes[failed] as Either.Left).value
    recorder.attribute(error, branchRecorders[failed].failedStepId(error))
    Either.Left(error)
  } else {
    Either.Right(outcomes.map { (it as Either.Right).value })
  }
}

/**
 * Waits for the branches until one fails, then cancels the rest. Cancelled branches have a `null`
 * outcome.
 */
private suspend fun failFast(
  deferreds: List<Deferred<Either<WorkflowError, WorkflowResult<Any?>>>>,
): List<Either<WorkflowError, WorkflowResult<Any?>>?> {
  val pending = deferreds.toMutableSet()
  while (pending.isNotEmpty()) {
    val (completed, result) = select {
      pending.forEach { deferred -> deferred.onAwait { deferred to it } }
    }
    pending.remove(completed)
    if (result is Either.Left) {
      pending.forEach { it.cancel() }
      pending.joinAll()
      break
    }
  }
  return deferreds.map { if (it.isCompleted && !it.isCancelled) it.await() else null }
}
