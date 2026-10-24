# Threading & Coroutines — Deep Reference

## Dispatcher Reference

| Dispatcher | Thread | Use For |
|---|---|---|
| `Dispatchers.Main` | UI thread | UI updates, LiveData/StateFlow collection, `suspend` funs that need UI |
| `Dispatchers.IO` | Shared thread pool (up to 64 threads) | File I/O, network, database |
| `Dispatchers.Default` | CPU core count threads | CPU-intensive work: sorting, parsing, computation |
| `Dispatchers.Unconfined` | Caller's thread (then resumes anywhere) | Rarely used — only for specific cases |

---

## Structured Concurrency — Use the Right Scope

```kotlin
// ✅ ViewModel scope — cancelled when ViewModel cleared
class MyViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {
            val data = repository.fetchData() // suspend fun — won't block UI
            _uiState.value = UiState.Success(data)
        }
    }
}

// ✅ Lifecycle scope — cancelled when lifecycle destroyed
class MyFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state -> renderState(state) }
        }
    }
}

// ✅ repeatOnLifecycle — suspends when STOPPED, resumes when STARTED
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state -> renderState(state) }
    }
}

// ❌ BAD — GlobalScope leaks, not cancelled on lifecycle
GlobalScope.launch { /* runs forever */ }

// ❌ BAD — custom scope without cancellation
val scope = CoroutineScope(Dispatchers.IO)
scope.launch { /* not tied to any lifecycle */ }
```

---

## Moving Work Off the Main Thread

```kotlin
// Pattern: launch on main, switch to IO for heavy work
class ProductViewModel : ViewModel() {
    fun loadProducts() {
        viewModelScope.launch { // starts on Main
            _loading.value = true
            
            val products = withContext(Dispatchers.IO) { // switch to IO
                repository.getProducts() // database / network
            }
            
            // Back on Main automatically after withContext
            _products.value = products
            _loading.value = false
        }
    }
}

// Pattern: suspend function declares its own dispatcher
class ProductRepository {
    suspend fun getProducts(): List<Product> = withContext(Dispatchers.IO) {
        db.productDao().getAll()
    }
    
    suspend fun processProducts(raw: List<RawProduct>): List<Product> =
        withContext(Dispatchers.Default) {
            raw.map { transform(it) } // CPU work
        }
}
```

---

## Parallel Execution

```kotlin
// ✅ async/await — parallel concurrent work
class DashboardViewModel : ViewModel() {
    fun loadDashboard() {
        viewModelScope.launch {
            // These two run in PARALLEL
            val userDeferred = async(Dispatchers.IO) { userRepo.getUser() }
            val statsDeferred = async(Dispatchers.IO) { statsRepo.getStats() }
            
            // Wait for both
            val user = userDeferred.await()
            val stats = statsDeferred.await()
            
            _uiState.value = DashboardState(user, stats)
        }
    }
}

// ✅ Process list in parallel
suspend fun processAll(items: List<Item>): List<Result> = coroutineScope {
    items.map { item ->
        async(Dispatchers.Default) { processItem(item) }
    }.awaitAll()
}
```

---

## Flow for Reactive Data

```kotlin
// Repository exposes Flow
class OrderRepository(private val dao: OrderDao) {
    // Room returns Flow automatically — emits on every DB change
    fun getOrders(): Flow<List<Order>> = dao.observeOrders()
    
    // Combining flows
    fun getOrdersWithUser(): Flow<Pair<List<Order>, User>> =
        dao.observeOrders().combine(userDao.observeUser()) { orders, user ->
            Pair(orders, user)
        }
}

// ViewModel transforms
class OrderViewModel : ViewModel() {
    val pendingOrders: StateFlow<List<Order>> = repository
        .getOrders()
        .map { it.filter { o -> o.status == PENDING } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}

// ✅ WhileSubscribed(5000) — stops collection 5s after last subscriber
// Good for surviving config changes
```

---

## Common Threading Mistakes

### 1. `runBlocking` in Production Code

```kotlin
// ❌ BAD — blocks current thread entirely
fun getData(): List<Item> = runBlocking {
    repository.fetchItems() // if called on Main thread → ANR!
}

// ✅ GOOD — make the caller a suspend fun
suspend fun getData(): List<Item> = repository.fetchItems()
```

### 2. Thread-Unsafe Shared State

```kotlin
// ❌ BAD — mutableListOf not thread-safe
var cache = mutableListOf<Item>()

fun addItem(item: Item) {
    cache.add(item) // concurrent add can corrupt list
}

// ✅ OPTION 1 — confine mutations to single coroutine (Mutex)
val mutex = Mutex()
var cache = mutableListOf<Item>()

suspend fun addItem(item: Item) {
    mutex.withLock { cache.add(item) }
}

// ✅ OPTION 2 — use concurrent data structures
val cache = ConcurrentLinkedQueue<Item>()

// ✅ OPTION 3 — use a single-threaded dispatcher as a "actor"
private val cacheDispatcher = Dispatchers.IO.limitedParallelism(1)

suspend fun addItem(item: Item) = withContext(cacheDispatcher) {
    cache.add(item) // only one thread accesses cache
}
```

### 3. Forgetting `withContext` in Repository

```kotlin
// ❌ BAD — library call may block, but we're on Default (CPU) dispatcher
class SearchRepository {
    suspend fun search(query: String) = withContext(Dispatchers.Default) {
        heavyAlgorithm(query) // fine for CPU
        networkCall(query)    // BAD — network I/O on Default!
    }
}

// ✅ GOOD — use IO for network
suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
    val networkResult = api.search(query)
    withContext(Dispatchers.Default) { processResults(networkResult) }
}
```

### 4. Flow Collection in Wrong Lifecycle State

```kotlin
// ❌ BAD — collects even when UI is in background (wastes resources, can crash)
lifecycleScope.launch {
    viewModel.data.collect { updateUI(it) } // runs even when STOPPED
}

// ✅ GOOD — use repeatOnLifecycle
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.data.collect { updateUI(it) } // pauses when STOPPED
    }
}

// ✅ COMPOSE equivalent
val data by viewModel.data.collectAsStateWithLifecycle()
```

---

## StrictMode for Thread Violations

```kotlin
// Catches thread violations in development — see SKILL.md baseline
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .detectDiskReads()      // file reads on main thread
        .detectDiskWrites()     // file writes on main thread
        .detectNetwork()        // network on main thread
        .detectCustomSlowCalls() // your own marked slow calls
        .penaltyLog()           // logcat output
        // .penaltyDeath()     // crash on violation (CI)
        .build()
)
```

---

## Threading Checklist

- [ ] All network/database calls in `Dispatchers.IO`
- [ ] CPU-intensive work in `Dispatchers.Default`
- [ ] UI updates only on `Dispatchers.Main`
- [ ] No `GlobalScope` usage — use `viewModelScope` or `lifecycleScope`
- [ ] No `runBlocking` in UI or business logic layers
- [ ] `repeatOnLifecycle(STARTED)` for collecting flows in Fragments/Activities
- [ ] `stateIn(WhileSubscribed(5000))` in ViewModels
- [ ] Shared mutable state protected with `Mutex` or confined to single dispatcher
- [ ] StrictMode in debug builds to catch violations