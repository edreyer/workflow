package io.liquidsoftware.workflow

import arrow.core.Either
import arrow.resilience.Schedule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Every step combinator wrapped around every other, to check that none of them hides what another
 * one does: raised errors reach each enclosing mapError, a success keeps its events, exceptions stay
 * unexpected, and a failure is attributed to the step that raised it.
 */
class CombinatorCompositionTest {

  /** An error that records each mapError it passed through. */
  data class Err(val path: String)
  data class Emitted(val n: Int) : Event

  private class Wrapper(val name: String, val mapsErrors: Boolean, val wrap: (Workflow<Int, Int, Err>) -> Workflow<Int, Int, Err>)

  private val noop = workflow<Int, Int, Nothing>("noop") { it }

  private val wrappers = listOf(
    Wrapper("mapError", mapsErrors = true) { it.mapError { e -> Err(e.path + "+m") } },
    Wrapper("mapInput", mapsErrors = false) { it.mapInput { n: Int -> n } },
    Wrapper("mapOutput", mapsErrors = false) { it.mapOutput { n -> n } },
    Wrapper("timeout", mapsErrors = false) { it.timeout(10.seconds) { Err("timeout") } },
    Wrapper("retry", mapsErrors = false) { it.retry(Schedule.recurs(1)) },
    Wrapper("recover", mapsErrors = false) { it.recover { _, e -> raise(e) } },
    Wrapper("parallelJoin", mapsErrors = false) { parallelJoin(it, noop) { n, _ -> n } },
  )


  private sealed interface Behaviour
  private data object Raises : Behaviour
  private data object Throws : Behaviour
  private data object Succeeds : Behaviour

  private fun base(behaviour: Behaviour) = workflow<Int, Int, Err>("base") { n ->
    when (behaviour) {
      Raises -> raise(Err("e"))
      Throws -> error("boom")
      Succeeds -> { emit(Emitted(n)); n }
    }
  }

  /** Wraps the base step in [layers], innermost first. */
  private suspend fun check(layers: List<Wrapper>, behaviour: Behaviour) {
    val label = layers.fold("base") { inside, layer -> "${layer.name}($inside)" }.let { "$it $behaviour" }
    val step = layers.fold(base(behaviour)) { inside, layer -> layer.wrap(inside) }
    val summaries = mutableListOf<UseCaseSummary>()
    val useCase = useCase<Int, Err, Pair<Int, List<Event>>>("Compose", config = useCaseConfig { onSummary { summaries += it } }) {
      startWith { it }.then(step).result { it to events }
    }

    val result = useCase(7)

    when (behaviour) {
      Succeeds -> assertEquals(Either.Right(7 to listOf<Event>(Emitted(7))), result, label)
      Raises -> {
        val mapped = layers.count { it.mapsErrors }
        assertEquals(Either.Left(Failure.Raised(Err("e" + "+m".repeat(mapped)))), result, label)
        assertEquals("base", summaries.single().rootError?.workflowId, label)
      }
      Throws -> {
        val failure = assertIs<Either.Left<Failure<Err>>>(result, label).value
        assertEquals("boom", assertIs<Failure.Unexpected>(failure, label).cause.message, label)
        assertEquals("base", summaries.single().rootError?.workflowId, label)
      }
    }
  }

  /** Ways a pipeline can run a step on state Int. */
  private val placements: List<Pair<String, (Workflow<Int, Int, Err>) -> UseCase<Int, Failure<Err>, Int>>> = listOf(
    "then" to { step -> useCaseOf { it.then(step) } },
    "thenIf" to { step -> useCaseOf { it.thenIf(step) { true } } },
    "branch" to { step -> useCaseOf { it.branch(step, noop) { true } } },
    "parallel" to { step -> useCaseOf { it.parallel(step, noop) } },
    "parallelJoin" to { step -> useCaseOf { it.parallelJoin(step, noop) { n, _ -> n } } },
    "focused then" to { step -> useCaseOf { it.then(step, input = { n -> n }) { n -> n } } },
    "sub-pipeline" to { step -> useCaseOf { it.then(pipeline<Int, Err>().then(step)) } },
  )

  private val summaries = mutableListOf<UseCaseSummary>()

  private fun useCaseOf(steps: (Pipeline<Int, Err, Int>) -> Pipeline<Int, Err, Int>): UseCase<Int, Failure<Err>, Int> =
    useCase("Place", config = useCaseConfig { onSummary { summaries += it } }) {
      steps(startWith { it }).result { it }
    }

  @Test
  fun `every pipeline method passes on a wrapped step's mapped error and its attribution`() = runTest {
    val failures = mutableListOf<String>()
    for ((name, place) in placements) for (wrapper in wrappers) {
      val label = "$name(${wrapper.name}(mapError(base)))"
      summaries.clear()
      val result = place(wrapper.wrap(base(Raises).mapError { Err(it.path + "+m") }))(7)
      val expected = Err("e+m" + if (wrapper.mapsErrors) "+m" else "")
      if (result != Either.Left(Failure.Raised(expected))) failures += "$label: $result"
      if (summaries.single().rootError?.workflowId != "base") failures += "$label: ${summaries.single().rootError}"
    }
    assertEquals(emptyList(), failures)
  }

  @Test
  fun `every stack of up to three combinators composes`() = runTest {
    val stacks = listOf(emptyList<Wrapper>()) + wrappers.map { listOf(it) } +
      wrappers.flatMap { a -> wrappers.map { b -> listOf(a, b) } } +
      wrappers.flatMap { a -> wrappers.flatMap { b -> wrappers.map { c -> listOf(a, b, c) } } }
    val failures = mutableListOf<String>()
    for (layers in stacks) for (behaviour in listOf(Raises, Throws, Succeeds)) {
      try {
        check(layers, behaviour)
      } catch (e: AssertionError) {
        failures += e.message.orEmpty()
      }
    }
    assertEquals(emptyList(), failures)
  }
}
