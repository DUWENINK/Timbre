package voice.features.bookOverview.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineRecommendGroup
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceService
import voice.core.online.OnlineSourceServiceProvider
import voice.features.bookOverview.paging.PagedList
import voice.features.bookOverview.search.OnlineChaptersDialog
import voice.features.bookOverview.search.OnlineResultRow

private data class RecommendSource(
  val source: String,
  val name: String,
  val description: String,
  val groups: List<OnlineRecommendGroup>,
)

/** Matches the limit the extension backend sends for one browse page. */
private const val DISCOVER_PAGE_SIZE = 30

/**
 * Stable identity of a recommendation row, also used as the LazyColumn key.
 * Duplicates are dropped before they reach the list (see [PagedList]) because
 * a repeated key crashes Compose.
 */
private fun discoverKey(result: OnlineSearchResult): String = result.source + result.bookId + result.title

private fun OnlineRecommendGroup.cacheKey(): String = "$source/$groupId"

/** Turns raw engine errors into something that tells the user what to do. */
private fun loadErrorMessage(e: Exception): String {
  val raw = e.message.orEmpty().trim()
  return when {
    raw.contains("timeout", ignoreCase = true) ->
      "连接接口源超时：站点或网络暂时没响应，可点「重试」再试一次。"
    raw.isBlank() -> "加载失败，可点「重试」再试一次。"
    else -> "加载失败：$raw"
  }
}

/** Second page of the shelf pager. Only sources that declare recommenders appear. */
@Composable
internal fun DiscoverContent(
  onBookClick: (BookId) -> Unit,
  contentPadding: PaddingValues,
  modifier: Modifier = Modifier,
) {
  val graph = remember { rootGraphAs<OnlineSourceServiceProvider>() }
  val service: OnlineSourceService = graph.onlineSourceService
  val catalog: OnlinePlaybackCatalog = graph.onlinePlaybackCatalog
  var groups by remember { mutableStateOf(emptyList<OnlineRecommendGroup>()) }
  var groupsLoading by remember { mutableStateOf(true) }
  var selectedSource by remember { mutableStateOf<String?>(null) }
  var selectedGroup by remember { mutableStateOf<OnlineRecommendGroup?>(null) }
  var paging by remember { mutableStateOf(PagedList<OnlineSearchResult>()) }
  var loading by remember { mutableStateOf(false) }
  var loadingMore by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var moreError by remember { mutableStateOf<String?>(null) }
  var retryToken by remember { mutableIntStateOf(0) }
  var moreRetryToken by remember { mutableIntStateOf(0) }
  var handledMoreRetry by remember { mutableIntStateOf(0) }
  var cachedBooks by remember { mutableStateOf(emptyMap<String, PagedList<OnlineSearchResult>>()) }
  var chaptersFor by remember { mutableStateOf<OnlineSearchResult?>(null) }
  val listState = rememberLazyListState()

  // groupBy keeps only sources that actually declared recommendation slots.
  val sources = remember(groups) {
    groups.groupBy { it.source }.entries.map { (source, list) ->
      RecommendSource(
        source = source,
        name = list.first().sourceName,
        description = list.first().sourceDescription,
        groups = list,
      )
    }
  }
  val currentSource = sources.firstOrNull { it.source == selectedSource }
  val visibleGroups = currentSource?.groups.orEmpty()

  LaunchedEffect(Unit) {
    try {
      groups = service.recommendGroups()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      groups = emptyList()
    }
    groupsLoading = false
    val first = groups.firstOrNull()
    selectedSource = first?.source
    selectedGroup = first
  }
  LaunchedEffect(selectedGroup, retryToken) {
    val group = selectedGroup ?: return@LaunchedEffect
    // Rapid slot switches cancel this effect during the window, so only the
    // slot the user actually settles on issues a request. Without it every tap
    // started its own connection and they all timed out together.
    delay(250)
    val cacheKey = group.cacheKey()
    loading = true
    loadingMore = false
    error = null
    moreError = null
    paging = PagedList()
    try {
      val result = service.browse(group.source, group.groupId, 1)
      val loaded = paging.append(result, DISCOVER_PAGE_SIZE, ::discoverKey)
      paging = loaded
      cachedBooks = cachedBooks + (cacheKey to loaded)
      // 换分类后列表从顶部开始，否则会停在上一分类的滚动位置，直接触发下一页。
      // 空列表时页面显示转圈、LazyColumn 不在组合中，scrollToItem 会一直等
      // layout，这里先判空。
      if (loaded.items.isNotEmpty()) listState.scrollToItem(0)
    } catch (e: CancellationException) {
      // Switching slots (or swiping away) cancels the previous load.
      // Cancellation is not a failure and its message must never be shown.
      throw e
    } catch (e: Exception) {
      error = loadErrorMessage(e)
      // A flaky link must not blank the page: fall back to the last good list.
      paging = cachedBooks[cacheKey] ?: PagedList()
      if (paging.items.isNotEmpty()) listState.scrollToItem(0)
    }
    loading = false
  }

  // 触底加载下一页。三条终止条件由 PagedList 负责（空页 / 不满一页 /
  // 整页重复），这里只判断「该不该发请求」。
  //
  // loading/loadingMore 故意不放进 key：它们只在协程内部变化，放进 key 会让
  // 每次置 true 都取消并重启自己，导致请求被取消后重发。
  val shouldLoadMore by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
      info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
    }
  }
  LaunchedEffect(shouldLoadMore, paging.page, paging.endReached, moreRetryToken) {
    val group = selectedGroup ?: return@LaunchedEffect
    val retryRequested = moreRetryToken > handledMoreRetry
    if (!shouldLoadMore && !retryRequested) return@LaunchedEffect
    if (loading || loadingMore || paging.endReached || !paging.isStarted) return@LaunchedEffect
    handledMoreRetry = moreRetryToken
    loadingMore = true
    moreError = null
    try {
      val next = service.browse(group.source, group.groupId, paging.page + 1)
      // 切了推荐位/源之后返回的旧页必须丢掉，否则会拼进新的列表
      if (selectedGroup != group) return@LaunchedEffect
      val appended = paging.append(next, DISCOVER_PAGE_SIZE, ::discoverKey)
      paging = appended
      cachedBooks = cachedBooks + (group.cacheKey() to appended)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 保留已加载的内容，只在底部显示重试
      moreError = loadErrorMessage(e)
    } finally {
      loadingMore = false
    }
  }

  fun selectSource(source: String) {
    selectedSource = source
    selectedGroup = sources.firstOrNull { it.source == source }?.groups?.firstOrNull()
  }

  Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    Text(
      text = "接口源",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      sources.forEach { source ->
        FilterChip(
          selected = selectedSource == source.source,
          onClick = { selectSource(source.source) },
          label = { Text(source.name) },
        )
      }
    }
    currentSource?.description?.takeIf { it.isNotBlank() }?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
    }
    if (visibleGroups.isNotEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        visibleGroups.forEach { group ->
          FilterChip(
            selected = selectedGroup == group,
            onClick = { selectedGroup = group },
            label = { Text(group.name) },
          )
        }
      }
    }
    selectedGroup?.description?.takeIf { it.isNotBlank() }?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    if (groupsLoading) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    } else {
      if (error != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { retryToken++ }) { Text("重试") }
        }
      }
      when {
        loading && paging.items.isEmpty() -> {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        sources.isEmpty() -> Text(text = "未安装带推荐位的接口源", modifier = Modifier.padding(16.dp))
        paging.items.isEmpty() -> Text(text = "暂无推荐书籍", modifier = Modifier.padding(16.dp))
        else -> {
          LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(paging.items, key = ::discoverKey) { result ->
              OnlineResultRow(result = result, onClick = { chaptersFor = result })
            }
            when {
              loadingMore -> item {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  horizontalArrangement = Arrangement.Center,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                  Text(
                    text = "加载更多…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                  )
                }
              }
              moreError != null -> item {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = moreError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                  )
                  TextButton(onClick = { moreRetryToken++ }) { Text("重试") }
                }
              }
              paging.endReached && paging.items.isNotEmpty() -> item {
                Text(
                  text = "已经到底了",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  chaptersFor?.let { book ->
    OnlineChaptersDialog(
      service = service,
      catalog = catalog,
      book = book,
      onDismiss = { chaptersFor = null },
      onBookClick = onBookClick,
    )
  }
}
