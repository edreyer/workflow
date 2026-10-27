package io.liquidsoftware.workflow.recipes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the compiled recipes in docs/recipes honest: every ```kotlin block on a page marked
 * "Compiled" must appear, line for line and ignoring indentation, in the source file the page
 * links to.
 */
class RecipeSnippetsTest {

  private val recipes = File("docs/recipes")
  private val sourceLink = Regex("""\]\(\.\./\.\./(src/test/kotlin/[^)]+\.kt)\)""")
  private val kotlinBlock = Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)

  @Test
  fun `compiled recipe snippets match their sources`() {
    val pages = recipes.listFiles { file -> file.extension == "md" }.orEmpty()
      .filter { "> Compiled:" in it.readText() }
    assertTrue(pages.isNotEmpty(), "no compiled recipes found in $recipes")

    val missing = pages.flatMap { page ->
      val text = page.readText()
      val source = sourceLink.find(text)?.groupValues?.get(1) ?: fail("${page.name} links to no source file")
      val sourceLines = File(source).readLines().map { it.trim() }
      kotlinBlock.findAll(text).map { it.groupValues[1].trimEnd().lines().map(String::trim) }
        .filterNot { block -> sourceLines.containsRun(block) }
        .map { block -> "${page.name}: ${block.first()}" }
        .toList()
    }

    assertTrue(missing.isEmpty(), "Snippets not found in their sources:\n" + missing.joinToString("\n"))
  }

  private fun List<String>.containsRun(run: List<String>): Boolean =
    (0..size - run.size).any { start -> run.indices.all { this[start + it] == run[it] } }
}
