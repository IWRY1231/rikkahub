package me.rerere.search

/**
 * 搜索服务额度查询结果(可选能力, 目前仅 Tavily 实现)
 *
 * @see SearchService.getUsage
 */
data class SearchUsage(
    val items: List<SearchKeyUsage> = emptyList(),
)

/**
 * 单把 API Key 的额度
 *
 * @param label 掩码后的 Key 标识(仅多 Key 场景用于区分每一行)
 * @param used 已用额度; null 表示未取到
 * @param limit 额度上限; null 表示不限量
 * @param error 该 Key 查询失败的原因(单把失败不影响其余 Key)
 */
data class SearchKeyUsage(
    val label: String,
    val used: Long? = null,
    val limit: Long? = null,
    val error: String? = null,
)
