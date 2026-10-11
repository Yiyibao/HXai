package me.rerere.rikkahub.feature.material

/** Only verified metadata links define a family; names are never evidence of lineage. */
object MaterialVersions {
    fun family(files: List<MaterialFile>, target: String): List<MaterialFile> {
        val map = files.associateBy { it.id }
        val source = map[target] ?: error("成果不存在")
        fun root(item: MaterialFile): String {
            var current = item
            val seen = mutableSetOf<String>()
            repeat(128) {
                check(seen.add(current.id)) { "版本关联循环" }
                current = current.sourceId?.let(map::get) ?: return current.id
            }
            error("版本关联过深")
        }
        val root = root(source)
        return files.filter { it.profileId == source.profileId && root(it) == root }.sortedBy { it.createdAt }
    }
}
