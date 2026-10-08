package opensamguk.logic.retainer

import kotlin.test.*

class RetinueHierarchyTest {
    private val people = (1..5).map { RetinueHierarchyPerson(it, 7) }
    private val links = listOf(RetinueHierarchyLink(1, 3), RetinueHierarchyLink(1, 2),
        RetinueHierarchyLink(2, 5), RetinueHierarchyLink(2, 4))

    @Test fun `preorder and superior chain are deterministic and distinguish direct from total descendants`() {
        val tree = RetinueHierarchy.build(people, links)
        assertEquals(listOf(1, 2, 4, 5, 3), tree.subtree(1))
        assertEquals(listOf(2, 4, 5), tree.subtree(2))
        assertEquals(listOf(2, 1), tree.ancestors(4))
        assertEquals(emptyList(), tree.ancestors(1))
        assertEquals(listOf(2, 3), tree.childrenByOwner[1])
        assertEquals(tree.subtree(1), RetinueHierarchy.build(people.reversed(), links.reversed()).subtree(1))
    }

    @Test fun `invalid ownership graphs fail without manufacturing a root or subordinate`() {
        val invalid = listOf(
            links + RetinueHierarchyLink(3, 4), // second superior
            links + RetinueHierarchyLink(4, 1), // cycle
            links + RetinueHierarchyLink(3, 3), // self
            links + RetinueHierarchyLink(3, 99), // dangling person
            links + RetinueHierarchyLink(99, 1), // dangling superior
        )
        for (edges in invalid) assertFailsWith<IllegalArgumentException> { RetinueHierarchy.build(people, edges) }
        assertFailsWith<IllegalArgumentException> { RetinueHierarchy.build(people + people.first(), links) }
        assertFailsWith<IllegalArgumentException> {
            RetinueHierarchy.build(people.map { if (it.id == 4) it.copy(nationId = 8) else it }, links)
        }
    }

    @Test fun `long existing personal divisions do not depend on recursive stack depth`() {
        val count = 1000
        val tree = RetinueHierarchy.build((1..count).map { RetinueHierarchyPerson(it, 0) },
            (2..count).map { RetinueHierarchyLink(it - 1, it) })
        assertEquals((1..count).toList(), tree.subtree(1))
        assertEquals((count - 1 downTo 1).toList(), tree.ancestors(count))
    }
}
