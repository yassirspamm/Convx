# Network & Data Performance — Deep Reference

## OkHttp / Retrofit Setup (Production-Ready)

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .cache(Cache(context.cacheDir.resolve("http_cache"), 50 * 1024 * 1024)) // 50MB
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
    })
    .addInterceptor(RetryInterceptor(maxRetries = 3))
    .build()

val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com/")
    .client(okHttpClient)
    .addConverterFactory(MoshiConverterFactory.create())
    .build()
```

---

## Caching Strategies

### HTTP Cache (OkHttp)

```kotlin
// Server-side: set Cache-Control headers
// Cache-Control: max-age=300 (cache for 5 min)
// ETag: "abc123" (conditional GET support)

// Client-side: force cache when offline
val request = Request.Builder()
    .url(url)
    .cacheControl(
        if (isOnline) CacheControl.Builder().maxAge(5, TimeUnit.MINUTES).build()
        else CacheControl.FORCE_CACHE // use stale cache when offline
    )
    .build()
```

### Application-Level Cache

```kotlin
// ViewModel caching with StateFlow
class ProductViewModel : ViewModel() {
    // Cache in memory — survives config changes
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products = _products.asStateFlow()
    
    private var lastFetch: Long = 0
    private val CACHE_DURATION = 5 * 60 * 1000L // 5 min
    
    fun loadProducts(forceRefresh: Boolean = false) {
        if (!forceRefresh && System.currentTimeMillis() - lastFetch < CACHE_DURATION) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val data = api.getProducts()
            lastFetch = System.currentTimeMillis()
            _products.value = data
        }
    }
}
```

---

## Payload Size Reduction

```kotlin
// 1. Enable Gzip (OkHttp does this by default for requests)
// Ensure server returns Content-Encoding: gzip

// 2. Use Protobuf instead of JSON for large payloads (3-10x smaller)
// implementation("com.google.protobuf:protobuf-kotlin-lite:3.25.0")

// 3. Request only needed fields (GraphQL or sparse fieldsets)
// GET /users?fields=id,name,avatar (not full user object)

// 4. Paginate large lists
interface ProductApi {
    @GET("products")
    suspend fun getProducts(
        @Query("page") page: Int,
        @Query("limit") limit: Int = 20
    ): ProductPage
}

// 5. Use image CDN with size parameters
// https://cdn.example.com/img/photo.jpg?w=300&h=300&format=webp
```

---

## Paging 3

```kotlin
// Dependency
implementation("androidx.paging:paging-runtime-ktx:3.2.1")
implementation("androidx.paging:paging-compose:3.2.1") // Compose

// PagingSource
class ProductPagingSource(private val api: ProductApi) : PagingSource<Int, Product>() {
    
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Product> {
        val page = params.key ?: 1
        return try {
            val response = api.getProducts(page = page, limit = params.loadSize)
            LoadResult.Page(
                data = response.products,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (response.products.isEmpty()) null else page + 1
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        }
    }
    
    override fun getRefreshKey(state: PagingState<Int, Product>) =
        state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(1) }
}

// ViewModel
class ProductViewModel : ViewModel() {
    val products = Pager(PagingConfig(pageSize = 20, prefetchDistance = 5)) {
        ProductPagingSource(api)
    }.flow.cachedIn(viewModelScope)
}

// Fragment / Compose
val pagingItems = viewModel.products.collectAsLazyPagingItems()
LazyColumn {
    items(pagingItems, key = { it.id }) { product ->
        ProductRow(product)
    }
    item {
        when (pagingItems.loadState.append) {
            is LoadState.Loading -> LoadingSpinner()
            is LoadState.Error -> ErrorRow(retry = { pagingItems.retry() })
            else -> {}
        }
    }
}
```

---

## WebSocket / Real-Time

```kotlin
// OkHttp WebSocket
val request = Request.Builder().url("wss://api.example.com/ws").build()

val listener = object : WebSocketListener() {
    override fun onMessage(ws: WebSocket, text: String) {
        // Parse and emit to Flow
    }
    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        // Reconnect with exponential backoff
    }
}

val webSocket = okHttpClient.newWebSocket(request, listener)

// Remember to close when done
webSocket.close(1000, "App closed")
```

---

## Network Debugging

```bash
# Charles Proxy / mitmproxy — inspect HTTPS traffic
# Add network_security_config.xml for debug builds

# res/xml/network_security_config.xml
# <network-security-config>
#   <debug-overrides>
#     <trust-anchors>
#       <certificates src="user"/>
#     </trust-anchors>
#   </debug-overrides>
# </network-security-config>

# Profile with Android Studio Network Inspector:
# Profile → Network tab — see all requests, timing, payload size
```

---

## Network Checklist

- [ ] OkHttp `Cache` configured (disk cache for HTTP responses)
- [ ] Gzip compression supported server-side
- [ ] Paginating large lists with Paging 3
- [ ] Retry logic with exponential backoff
- [ ] No network calls from the main thread (StrictMode enforces)
- [ ] Offline support via `FORCE_CACHE` strategy
- [ ] Image URLs use CDN with size parameters
- [ ] Network Inspector used to audit payload sizes
