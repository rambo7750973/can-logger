package com.philips.canlogger.util

/**
 * CAN ID 过滤。
 *
 * - 全部写正数：白名单，只记录列表里的 ID
 * - 其中某个写成 `-7DF`：黑名单模式，记录除它以外的所有 ID
 * - 留空：全部记录
 *
 * 支持 `0x` 前缀、大小写、逗号/分号/空格分隔。
 */
class IdFilter private constructor(
    private val whitelist: Set<Int>,
    private val blacklist: Set<Int>
) {

    fun accept(id: Int): Boolean {
        if (blacklist.isNotEmpty()) return !blacklist.contains(id)
        if (whitelist.isNotEmpty()) return whitelist.contains(id)
        return true
    }

    val isActive: Boolean get() = whitelist.isNotEmpty() || blacklist.isNotEmpty()

    fun describe(): String = when {
        blacklist.isNotEmpty() -> "排除 " + blacklist.joinToString(",") { Integer.toHexString(it).uppercase() }
        whitelist.isNotEmpty() -> "仅 " + whitelist.joinToString(",") { Integer.toHexString(it).uppercase() }
        else -> "全部"
    }

    companion object {
        fun parse(raw: String?): IdFilter {
            val wl = HashSet<Int>()
            val bl = HashSet<Int>()
            raw?.split(',', ';', ' ', '\n', '\t')
                ?.filter { it.isNotBlank() }
                ?.forEach { token ->
                    var t = token.trim()
                    val black = t.startsWith("-")
                    if (black) t = t.substring(1)
                    t = t.removePrefix("0x").removePrefix("0X")
                    val v = t.toIntOrNull(16)
                    if (v != null && v >= 0) {
                        if (black) bl.add(v) else wl.add(v)
                    }
                }
            return IdFilter(wl, bl)
        }

        val ALL = IdFilter(emptySet(), emptySet())
    }
}
