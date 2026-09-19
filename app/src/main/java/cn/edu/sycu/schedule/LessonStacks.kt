package cn.edu.sycu.schedule

data class LessonBlock(val course: Course, val periods: List<Int>, val order: Int)
data class LessonStack(val blocks: List<LessonBlock>) {
    val front get() = blocks.first()
    val start get() = blocks.minOf { it.periods.first() }
    val end get() = blocks.maxOf { it.periods.last() }
    val hasMakeup get() = blocks.any { it.course.isMakeup }
}
fun lessonBlocks(courses: List<Course>): List<LessonBlock> = courses.flatMapIndexed { index, c ->
    val groups = mutableListOf<MutableList<Int>>()
    c.periodList().forEach { p -> if (groups.isEmpty() || groups.last().last() + 1 != p) groups.add(mutableListOf(p)) else groups.last().add(p) }
    groups.map { LessonBlock(c, it, index) }
}
fun lessonStacks(courses: List<Course>): List<LessonStack> {
    val remaining = lessonBlocks(courses).toMutableList()
    val result = mutableListOf<LessonStack>()
    while (remaining.isNotEmpty()) {
        val group = mutableListOf(remaining.removeAt(0))
        do {
            val added = remaining.filter { candidate -> group.any { it.course.weekday == candidate.course.weekday && it.periods.any { p -> p in candidate.periods } } }
            group.addAll(added); remaining.removeAll(added.toSet())
        } while (added.isNotEmpty())
        result.add(LessonStack(group.sortedWith(compareByDescending<LessonBlock> { it.periods.size }.thenBy { it.order })))
    }
    return result.sortedWith(compareBy<LessonStack> { it.front.course.weekday }.thenBy { it.start })
}
