package voice.features.bookOverview.paging

/**
 * 追加式分页状态：只做「把新一页并进来」和「判断是否已经到底」，
 * 不发起请求、不持有页面的生命周期。
 *
 * 去重必须发生在写进状态之前：LazyColumn 的 key 一旦重复，Compose 会直接
 * 抛 IllegalArgumentException 崩溃，不能指望渲染层过滤。接口返回重复条目
 * （老源忽略 page、榜单数据有重复集）时，这里把重复项丢掉。
 */
internal data class PagedList<T>(
  val items: List<T> = emptyList(),
  /** 已并入的页数；0 表示还没加载过任何一页。 */
  val page: Int = 0,
  val endReached: Boolean = false,
) {

  val isStarted: Boolean get() = page > 0

  /**
   * 并入一页。[pageSize] 是请求时用的 limit，用于判断「不满一页 = 到底」。
   *
   * 三种情况都算到底：
   * - 返回空列表；
   * - 返回条数少于 [pageSize]；
   * - 整页都是已存在的条目（服务端忽略 offset，再请求也只会拿到同一批）。
   */
  fun append(
    pageItems: List<T>,
    pageSize: Int,
    key: (T) -> String,
  ): PagedList<T> {
    val known = items.mapTo(HashSet(items.size), key)
    val fresh = ArrayList<T>(pageItems.size)
    for (item in pageItems) {
      if (!known.add(key(item))) continue
      fresh += item
    }
    val end = pageItems.isEmpty() || pageItems.size < pageSize || fresh.isEmpty()
    val merged = ArrayList<T>(items.size + fresh.size)
    merged.addAll(items)
    merged.addAll(fresh)
    return PagedList(items = merged, page = page + 1, endReached = end)
  }
}
