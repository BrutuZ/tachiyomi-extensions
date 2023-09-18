package eu.kanade.tachiyomi.extension.en.koushoku

import android.net.Uri.decode
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import kotlin.math.pow

// TODO: Check SY Raised Tags
// import exh.metadata.metadata.base.RaisedTag
// import exh.metadata.metadata.RaisedSearchMetadata

class Koushoku : ParsedHttpSource() {
    companion object {
        const val PARAMS_PREFIX = " PARAMS:"
        const val thumbnailSelector = "figure img"
        const val magazinesSelector = "#metadata a[href^='/magazines/'] > span:first-child"
    }

    override val baseUrl = "https://ksk.moe"
    override val name = "Koushoku"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(KoushokuWebViewInterceptor())
        // Site: 40req per 1 minute
        // Here: 1req per 2 sec -> 30req per 1 minute
        // (somewhat lower due to caching)
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 2)
        .build()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse/page/$page", headers)
    override fun latestUpdatesSelector() = "#galleries > main > article"
    override fun latestUpdatesNextPageSelector() =
        "footer nav li:has(a.active) + li:not(:last-child) > a"

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("a")!!.attr("title")
        thumbnail_url = element.selectFirst(thumbnailSelector)!!.absUrl("src")
    }

    private fun searchMangaBundleRequest(url: HttpUrl.Builder): Request {
        Log.v("Koushoku", "Bundling up $url")
        return GET(url.toString(), headers)
    }

    private fun searchMangaBundleParse(response: Response, query: String): MangasPage {
        val details = mangaDetailsParse(response.asJsoup())
        Log.v("Koushoku", "Got Details")
        val name = decode(query.substringAfter("&s=").removePrefix("&s="))
        Log.v("Koushoku", "New name: $name")
//        details.url = "/browse/?adv=1&$query"
        details.url = query
        details.description = "Bundled from $name"
        details.title = "[Bundle] $name"
        return MangasPage(listOf(details), false)
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/view/$id", headers)

    // taken from Tsumino ext
    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response.asJsoup())
        details.url = "/view/$id"
        return MangasPage(listOf(details), false)
    }

    // taken from Tsumino ext
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        val id = query.removePrefix("id:").removePrefix("bundle:").removeSuffix(PARAMS_PREFIX)
        Log.v("Koushoku", "Received intent - Page: $page - Query: $query")
        return if (query.startsWith("id:")) {
            Log.v("Koushoku", "Doing this $id")
            client.newCall(searchMangaByIdRequest(id)).asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else if ("bundle=1" in query || query.startsWith("bundle:")) {
            Log.v("Koushoku", "And that $id")
            val url = "$baseUrl/browse/page/$page".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("adv", "1")
                .addQueryParameter("s", id.substringBefore(PARAMS_PREFIX))
            id.substringAfter(PARAMS_PREFIX).split("&").forEach {
                url.addQueryParameter(it.substringBefore("="), it.substringAfter("="))
            }
            client.newCall(searchMangaBundleRequest(url)).asObservableSuccess()
                .map { response ->
                    searchMangaBundleParse(
                        response,
                        url.toString().removePrefix(baseUrl),
                    )
                }
        } else {
            Log.v("Koushoku", "Also dis $query")
            super.fetchSearchManga(page, id, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        Log.v("Koushoku", "Query: $query")
        val params = query.substringAfter(PARAMS_PREFIX, "")
        Log.v("Koushoku", "Params: $params")
        val search = query.substringBefore(PARAMS_PREFIX)
        Log.v("Koushoku", "Search: $search")
        val url = "$baseUrl/browse/page/$page".toHttpUrlOrNull()!!.newBuilder()

        buildAdvQuery(url, query, if (filters.isEmpty()) getFilterList() else filters)

        Log.v("Koushoku", "HTTP: $url")

        return GET(url.toString(), headers)
    }

    private fun buildAdvQuery(
        url: HttpUrl.Builder,
        query: String,
        filters: FilterList,
    ) {
        url.addQueryParameter("s", query)
        url.addQueryParameter("adv", "1")
        filters.forEach { filter ->
            if (filter.state == null) return@forEach
            with(filter.name) {
                when {
                    equals("Title") -> url.addQueryParameter("title", filter.state?.toString())
                    equals("Artist") -> url.addQueryParameter("a", filter.state?.toString())
                    equals("Circle") -> url.addQueryParameter("c", filter.state?.toString())
                    equals("Magazine") -> url.addQueryParameter("m", filter.state?.toString())
                    equals("Parody") -> url.addQueryParameter("p", filter.state?.toString())
                    equals("Tags") -> url.addQueryParameter("t", filter.state?.toString())
                    equals("Min. Pages") -> url.addQueryParameter("ps", filter.state?.toString())
                    equals("Max. Pages") -> url.addQueryParameter("pe", filter.state?.toString())
                    equals("Sort") -> url.addQueryParameter(
                        "sort",
                        2.0.pow(filter.state as Int).toInt().toString(),
                    )

                    equals("Order") -> {
                        url.addQueryParameter("order", (filter.state as Int + 1).toString())
                    }
                }
            }
        }
//            it.state = params?.queryParameterValues(it.name.first().lowercase())?.joinToString()
//            with () {
//                when {
//                    equals("t") -> it.state = params.queryParameterValues("t")
//                    equals("a") -> filters.add(ArtistFilter(state = it.second))
//                    equals("c") -> filters.add(CircleFilter(state = it.second))
//                    equals("p") -> filters.add(ParodyFilter(state = it.second))
//                    equals("m") -> filters.add(MagazineFilter(state = it.second))
//                    equals("ps") -> filters.add(PagesFilter(state = ">${it.second}"))
//                    equals("pe") -> filters.add(PagesFilter(state = "<${it.second}"))
//                    equals("sort") -> filters.add(SortFilter(state = it.second.toInt()))
//                    equals("order") -> filters.add(OrderFilter(state = it.second.toInt()))
//                }
//            }
//        }
//        return FilterList(filters)
    }

    override fun searchMangaSelector() = latestUpdatesSelector()
    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()
    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular/weekly/page/$page", headers)
    override fun popularMangaSelector() = latestUpdatesSelector()
    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()
    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

//    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
//        return if (!manga.initialized) {
//            super.fetchMangaDetails(manga)
//        } else {
//            Observable.just(manga)
//        }
//    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        Log.v("Koushoku", "Getting Manga Details")
        val isView = document.selectFirst("#metadata") != null
        val art =
            document.select("#metadata a[href^='/artists/'] > span:first-child, main > article > a h3 > span:first-child")
                .map {
                    it.text()
                }.distinct().joinToString()
        artist = art
        author = document.select("#metadata a[href^='/circles/'] > span:first-child")
            .joinToString { it.text() }.ifBlank { art }

        title =
            document.selectFirst("#metadata h1, main > article > a h3 > span:last-child")?.text()
                .toString()

        // commented out since it's defined afterwards
//        url =
//            document.selectFirst("#cover > a, main > article > a")!!.attr("href")
//                .replace("read", "view").removeSuffix("/1")

        // Reuse cover from browse
        thumbnail_url = document.selectFirst(thumbnailSelector)!!.absUrl("src")
            .replace("/320/", "/896/")

        genre = getTags(document)
        if (isView) description = getDesc(document)
        status = if (isView) SManga.COMPLETED else SManga.ONGOING
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun getTags(document: Document) = listOf(
        document.select("#metadata a[href^='/artists/'] > span:first-child, main > article > a h3 > span:first-child")
            .map { "artist:${it.text()}" },
        document.select("#metadata a[href^='/circles/'] > span:first-child")
            .map { "group:${it.text()}" },
        document.select("#metadata a[href^='/parodies/'] > span:first-child")
            .map { "parody:${it.text()}" }.sorted(),
        document.select(magazinesSelector)
            .map { "magazine:${it.text()}" }.sorted(),
        document.select("#metadata a[href^='/tags/'] > span:first-child, #metadata a[href^='/out/'] > span:first-child, #metadata a[href^='/browse?cat='] > span:first-child, main > article > a > footer > span")
            .map { "tag:${it.text()}" }.sorted(),
    ).flatten().joinToString().lowercase()

    private fun getDesc(document: Document) = buildString {
        val pages = document.selectFirst("#metadata a span:contains(Pages)")
        append("\uD83D\uDCC4 Pages: ")
        append(pages!!.text().split(" ")[0])
        append("\t")

        val size: Element? =
            document.selectFirst("#metadata strong:contains(Size) + div > span:first-child")
        append("\uD83D\uDCBE Size: ")
        append(size?.text() ?: "Unknown")
        append("\n")

//        val magazines = document.select(magazinesSelector)
//        if (magazines.isNotEmpty()) {
//            append("\uD83D\uDCD6 Magazine: ")
//            append(magazines.joinToString { it.text() })
//            append("\t")
//        }

        val source = document.select("#metadata a[href^='/out/'] > span:first-child")
        if (source.isNotEmpty()) {
            append("\uD83C\uDF10 Source: ")
            append(source.joinToString { it.text() })
            append("\t")
        }

        val parodies = document.select("#metadata a[href^='/parodies/'] > span:first-child")
        if (parodies.isNotEmpty()) {
            append("\uD83D\uDC65 Parody: ")
            append(parodies.joinToString { it.text() })
            append("\t")
        }
    }

//    override fun chapterListRequest(manga: SManga): Request {
//        return GET(baseUrl + manga.url, headers)
//    }
//
//    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
//        return if (manga.status != SManga.LICENSED) {
//            client.newCall(chapterListRequest(manga))
//                .asObservableSuccess()
//                .map { response ->
//                    chapterListParse(response)
//                }
//        } else {
//            Observable.error(Exception("Licensed - No chapters to show"))
//        }
//    }
//
//    fun bundleChapterListParse(response: Response) : SChapter {
//        val document = response.asJsoup()
//
//        return SChapter.create().apply {
//            setUrlWithoutDomain(response.request.url.encodedPath)
//            val title = document.selectFirst("#metadata h1")?.text() ?: ""
//            val ch = Regex("\\b[1-5]?\\d$").find(title)?.value ?: "1"
//            Log.v("Koushoku", "Chapter $ch")
//            name = "$ch - $title"
//            date_upload = document.select("#metadata time").eachAttr("data-timestamp").min()
//                .toLong() * 1000
//            scanlator =
//                document.selectFirst("#metadata  a[href^='/browse?ps='] > span")!!.text()
//        }
//    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return if (response.request.url.pathSegments.first() == "view") {
            listOf(
                SChapter.create().apply {
                    setUrlWithoutDomain(response.request.url.encodedPath)
                    val title: String = document.selectFirst("#metadata h1")!!.text() ?: ""
                    val ch = Regex("(?<!20\\d\\d-)\\b[\\d.]{1,4}$").find(title)?.value?.trim('.') ?: "1"
                    Log.v("Koushoku", "Chapter $ch")
                    name = "$ch. $title"
                    chapter_number = ch.toFloat()
                    date_upload = document.select("#metadata time").eachAttr("data-timestamp").min()
                        .toLong() * 1000
                    scanlator =
                        document.selectFirst("#metadata  a[href^='/browse?ps='] > span")!!.text()
                },
            )
        } else {
//        {
            document.select(chapterListSelector()).map { chapterFromElement(it) }
//            document.select("article").map { element ->
//                val href = element.select("a").attr("href").substringAfter("/view/")
//                Log.v("Koushoku", "Chapter URL: $href")
//                super.chapterListParse(
//                    client.newCall(searchMangaByIdRequest(href)).execute(),
//                ).first()
//            }
//                SChapter.create().apply {
//                    Log.v("Koushoku", "Chapter HTML: ${element.outerHtml()}")
//                    url = element.select("a").attr("href")
//                    name = element.select("a").attr("title")
//                    scanlator = element.select("strong").text()
//                }
        }
    }

    override fun chapterFromElement(element: Element): SChapter {
//        Log.v("Koushoku", "Chapter HTML: ${element.outerHtml()}")
        val title = element.select("a").attr("title")
        val ch = Regex("(?<!20\\d\\d-)\\b[\\d.]{1,4}$").find(title)?.value?.trim('.') ?: "1"
//        val manga = mangaDetailsParse(
//            Response.Builder().request(GET(href.removePrefix("/view/"), headers)).build(),
//        )
        return SChapter.create().apply {
            url = element.select("a").attr("href")
            name = "$ch. $title"
            Log.v("Koushoku", "Title: $title")
            chapter_number = ch.toFloat()
            Log.v("Koushoku", "Chapter $ch")
            scanlator = element.select("strong").text()
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        Log.v("Koushoku", "Preparing... ${chapter.name}")
        if (chapter.date_upload != 0L) return
        Log.v("Koushoku", "Prepared!!! \\o/")
        chapter.date_upload =
            client.newCall(pageListRequest(chapter)).execute()
            .asJsoup().select("#metadata time")
            .eachAttr("data-timestamp").min()
            .toLong() * 1000
    }

    override fun chapterListSelector() = latestUpdatesSelector()

    override fun pageListRequest(chapter: SChapter) = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val totalPages = document.selectFirst("#metadata  a[href^='/browse?ps='] > span")!!.text()
            .split(" ")[0]
            .toInt()
        if (totalPages == 0) {
            throw UnsupportedOperationException("Error: Empty pages (try Webview)")
        }

        val srcs = document.select("#previews img").eachAttr("src")
            .plus(document.select("#previews img").eachAttr("data-src")).toMutableList()
        if (srcs.size == 0) {
            Log.v("Koushoku", "No previews, guessing image URLs")
            val cover = document.selectFirst("#cover img")!!.attr("src").replace("/896/", "/320/")
            Log.v("Koushoku", "Cover: $cover")
            val (filename, ext) = cover.split("/").last().split(".")
            Log.v("Koushoku", "Filename: $filename")
            for (i in 1..totalPages) {
                srcs.add(
                    cover.replace(
                        "$filename.$ext",
                        "${i.toString().padStart(filename.length, '0')}.$ext",
                    ),
                )
            }
        }
        Log.d("Koushoku", "Page URLs (${srcs.size}): $srcs")

        return srcs.mapIndexed { idx, img ->
//            val img = if (src is String) { src } else { src.attr("src").ifEmpty { src.attr("data-src") } }
            Page(idx, imageUrl = img.replace("/t/", "/original/").replace("/320/", "/"))
        }
    }

    override fun pageListParse(document: Document): List<Page> =
        throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(document: Document): String =
        document.selectFirst(".main img, main img")!!.absUrl("src")

    override fun getFilterList() = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TitleFilter(),
        ArtistFilter(),
        CircleFilter(),
        MagazineFilter(),
        ParodyFilter(),
        TagFilter(),
        Filter.Separator("Pages"),
        MinPagesFilter(),
        MaxPagesFilter(),
        SortFilter(),
        OrderFilter(),
    )

    class TitleFilter : Filter.Text("Title")
    class ArtistFilter : Filter.Text("Artist")
    class CircleFilter : Filter.Text("Circle")
    class MagazineFilter : Filter.Text("Magazine")
    class ParodyFilter : Filter.Text("Parody")
    class TagFilter : Filter.Text("Tags")
    class MinPagesFilter : Filter.Text("Min. Pages")
    class MaxPagesFilter : Filter.Text("Max. Pages")
    class SortFilter : Filter.Select<String>(
        "Sort",
        arrayOf(
            "Title",
            "Pages",
            "Uploaded Date",
            "Published Date",
            "Popularity",
        ),
        2,
    )

    class OrderFilter : Filter.Select<String>(
        "Order",
        arrayOf(
            "Ascending",
            "Descending",
        ),
    )

    // Taken from nhentai ext
//    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T
}
