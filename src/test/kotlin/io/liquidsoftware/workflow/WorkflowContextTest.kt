package io.liquidsoftware.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowContextTest {

  private val flags = Key<Set<String>>("flags")
  private val tenant = Key<String>("tenant")

  @Test
  fun `stores and reads typed values including generic types`() {
    val context = WorkflowContext().with(flags, setOf("beta")).with(tenant, "acme")

    assertEquals(setOf("beta"), context[flags])
    assertEquals("acme", context[tenant])
    assertTrue(tenant in context)
  }

  @Test
  fun `keys compare by identity`() {
    val context = WorkflowContext().with(tenant, "acme")

    assertNull(context[Key<String>("tenant")])
    assertFalse(Key<String>("tenant") in context)
  }

  @Test
  fun `plus merges with the right side winning`() {
    val left = WorkflowContext.of(tenant to "left", flags to setOf("a"))
    val right = WorkflowContext.of(tenant to "right")

    val merged = left + right

    assertEquals("right", merged[tenant])
    assertEquals(setOf("a"), merged[flags])
  }

  @Test
  fun `contexts are immutable values`() {
    val empty = WorkflowContext()
    empty.with(tenant, "acme")

    assertNull(empty[tenant])
    assertEquals(WorkflowContext.of(tenant to "acme"), WorkflowContext().with(tenant, "acme"))
  }

  @Test
  fun `lastEvent finds the most recent event of a type`() {
    data class Seen(val n: Int) : BaseEvent()
    data class Other(val n: Int) : BaseEvent()

    val events = listOf(Seen(1), Other(2), Seen(3))

    assertEquals(Seen(3), events.lastEvent<Seen>())
    assertNull(emptyList<Event>().lastEvent<Seen>())
  }
}
