package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.uuid.Uuid

// ---------- 工具输出源头瘦身 ----------
// 防止搜索/抓取输出超长触发 GenerationHandler 截断(条目丢失 + 卡片 UI 降级)。
// 按条数自适应分摊预算: 条多则单条上限收窄, 总体积有确定上界。

private const val SEARCH_TEXT_BUDGET_CHARS = 20_000
private const val SEARCH_ITEM_TEXT_MIN = 400
private const val SEARCH_ITEM_TEXT_MAX = 1_200
private const val SEARCH_ANSWER_MAX_CHARS = 4_000
private const val SEARCH_IMAGES_MAX = 10

private const val SCRAPE_CONTENT_BUDGET_CHARS = 24_000
private const val SCRAPE_PAGE_MIN = 2_000
private const val SCRAPE_PAGE_MAX = 12_000

private fun capText(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text
    else text.take(maxChars) + "…[truncated ${text.length - maxChars} chars]"

private fun adaptiveCap(count: Int, budget: Int, min: Int, max: Int, overhead: Int = 500): Int {
    if (count <= 0) return max
    return ((budget / count) - overhead).coerceIn(min, max)
}

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](url)` after the sentence.
                    - The link target must be the item's real `url` (never the short id, never a fabricated url).
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](https://www.example.com/paris)
                    The population is about 2.1 million. [citation,example.com](https://www.example.com/paris) [citation,example2.org](https://www.example2.org/population)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val searchResult = result.getOrThrow()
                    // 源头瘦身: 超长摘要/答案截断, 条目全保留, 总体积有上界
                    val textCap = adaptiveCap(
                        count = searchResult.items.size,
                        budget = SEARCH_TEXT_BUDGET_CHARS,
                        min = SEARCH_ITEM_TEXT_MIN,
                        max = SEARCH_ITEM_TEXT_MAX,
                    )
                    val shaped = searchResult.copy(
                        answer = searchResult.answer?.let { capText(it, SEARCH_ANSWER_MAX_CHARS) },
                        images = searchResult.images.take(SEARCH_IMAGES_MAX),
                        items = searchResult.items.map { item ->
                            item.copy(text = capText(item.text, textCap))
                        },
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(shaped).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val scraped = result.getOrThrow()
                        // 源头瘦身: 多页均摊预算截断超长正文, 单页上限 12K
                        val contentCap = adaptiveCap(
                            count = scraped.urls.size,
                            budget = SCRAPE_CONTENT_BUDGET_CHARS,
                            min = SCRAPE_PAGE_MIN,
                            max = SCRAPE_PAGE_MAX,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(
                            scraped.copy(urls = scraped.urls.map { it.copy(content = capText(it.content, contentCap)) })
                        ).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}
