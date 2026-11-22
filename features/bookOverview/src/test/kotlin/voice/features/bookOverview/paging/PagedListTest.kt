package voice.features.bookOverview.paging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagedListTest {

  private fun keyOf(value: String): String = value

  @Test
  fun `a full page keeps the list open`() {
    val state = PagedList<String>().append(listOf("a", "b", "c"), pageSize = 3, key = ::keyOf)

    assertEquals(listOf("a", "b", "c"), state.items)
    assertEquals(1, state.page)
    assertFalse(state.endReached)
  }

  @Test
  fun `a short page ends the list`() {
    val state = PagedList<String>().append(listOf("a", "b"), pageSize = 3, key = ::keyOf)

    assertTrue(state.endReached)
  }

  @Test
  fun `an empty page ends the list`() {
    val state = PagedList<String>().append(emptyList(), pageSize = 3, key = ::keyOf)

    assertEquals(1, state.page)
    assertTrue(state.endReached)
  }

  @Test
  fun `duplicates across pages are dropped before they reach the list`() {
    val first = PagedList<String>().append(listOf("a", "b", "c"), pageSize = 3, key = ::keyOf)
    val second = first.append(listOf("c", "d", "e"), pageSize = 3, key = ::keyOf)

    assertEquals(listOf("a", "b", "c", "d", "e"), second.items)
    assertEquals(2, second.page)
    assertFalse(second.endReached)
  }

  @Test
  fun `a page that is entirely duplicate stops the list`() {
    val first = PagedList<String>().append(listOf("a", "b", "c"), pageSize = 3, key = ::keyOf)
    val second = first.append(listOf("a", "b", "c"), pageSize = 3, key = ::keyOf)

    assertEquals(listOf("a", "b", "c"), second.items)
    assertTrue(second.endReached)
  }

  @Test
  fun `order of loaded pages is preserved`() {
    var state = PagedList<String>()
    state = state.append(listOf("1", "2"), pageSize = 2, key = ::keyOf)
    state = state.append(listOf("3", "4"), pageSize = 2, key = ::keyOf)
    state = state.append(listOf("5"), pageSize = 2, key = ::keyOf)

    assertEquals(listOf("1", "2", "3", "4", "5"), state.items)
    assertEquals(3, state.page)
    assertTrue(state.endReached)
  }
}
