package dev.lily.app.media

import kotlin.random.Random

/**
 * 按地点挑图的纯逻辑：上下文或她的城市里点到某个地点就命中。
 * 标签可能写全称（杭州西湖），聊天里只说「西湖」，所以上下文除全名外还比中文后缀别名；
 * 城市只用全名或「标签包含城市」来补充。多个命中取标签最长（更具体）的那组，
 * 再在组内随机挑一张（设计稿：同一地点多张时随机，不按上传顺序）。
 */
object SceneryPicker {
    fun pick(
        items: List<SceneryStore.Scenery>,
        context: String,
        city: String,
        random: Random = Random.Default,
    ): SceneryStore.Scenery? {
        if (items.isEmpty()) return null
        val byContext = items.filter { item ->
            aliases(item.place).any { context.contains(it, ignoreCase = true) }
        }
        val byCity = if (city.isBlank()) emptyList() else items.filter { item ->
            val place = item.place
            place.isNotBlank() && (place.contains(city, ignoreCase = true) || city.contains(place, ignoreCase = true))
        }
        val hits = (byContext + byCity).distinctBy { it.id }
        if (hits.isEmpty()) return null
        val best = hits.maxOf { it.place.length }
        val pool = hits.filter { it.place.length == best }
        return pool[random.nextInt(pool.size)]
    }

    private fun aliases(place: String): List<String> {
        val clean = place.trim()
        if (clean.length < 2) return listOf(clean)
        if (clean.none { it.code in 0x4E00..0x9FFF }) return listOf(clean)
        return buildList {
            add(clean)
            if (clean.length >= 3) add(clean.takeLast(3))
            add(clean.takeLast(2))
        }.distinct()
    }
}
