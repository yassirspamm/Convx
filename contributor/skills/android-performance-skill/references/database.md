# Database & Storage — Deep Reference

## Room (SQLite) Optimization

### Setup

```kotlin
@Database(entities = [User::class, Order::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun orderDao(): OrderDao
    
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app.db")
                    .fallbackToDestructiveMigration() // for dev only!
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
```

### Indexing — Most Impactful Optimization

```kotlin
// ❌ BAD — no index on frequently queried column
@Entity
data class Order(
    @PrimaryKey val id: String,
    val userId: String,  // queried often, but no index
    val status: String,
    val createdAt: Long
)

// ✅ GOOD — index on foreign key and filter columns
@Entity(
    indices = [
        Index(value = ["userId"]),          // JOIN / WHERE userId = ?
        Index(value = ["status", "userId"]) // WHERE status = ? AND userId = ?
    ]
)
data class Order(
    @PrimaryKey val id: String,
    val userId: String,
    val status: String,
    val createdAt: Long
)
```

### Query Optimization

```kotlin
@Dao
interface OrderDao {
    
    // ✅ SELECT only needed columns (avoid SELECT *)
    @Query("SELECT id, status, createdAt FROM orders WHERE userId = :userId")
    fun getOrderSummaries(userId: String): Flow<List<OrderSummary>>
    
    // ✅ Paginated query with Paging 3
    @Query("SELECT * FROM orders WHERE userId = :userId ORDER BY createdAt DESC")
    fun getOrdersPaged(userId: String): PagingSource<Int, Order>
    
    // ✅ Use transactions for multiple writes
    @Transaction
    suspend fun replaceAll(orders: List<Order>) {
        deleteAll()
        insertAll(orders)
    }
    
    // ✅ UPSERT (Room 2.5+)
    @Upsert
    suspend fun upsert(orders: List<Order>)
    
    // ❌ BAD — N+1 problem: one query per user
    @Query("SELECT * FROM orders WHERE userId = :userId")
    suspend fun getOrdersForUser(userId: String): List<Order>
    
    // ✅ GOOD — single query with JOIN using @Relation
    @Transaction
    @Query("SELECT * FROM users")
    fun getUsersWithOrders(): Flow<List<UserWithOrders>>
}

data class UserWithOrders(
    @Embedded val user: User,
    @Relation(parentColumn = "id", entityColumn = "userId")
    val orders: List<Order>
)
```

### Write Performance

```kotlin
// ✅ Batch inserts in a transaction (100x faster than one-by-one)
@Transaction
suspend fun insertAll(items: List<Item>) {
    // Room wraps in single SQLite transaction automatically for list inserts
    dao.insertAll(items)
}

// ✅ Write-ahead logging (WAL) for better concurrent read/write
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .build()
```

### Query Debugging

```kotlin
// Check query plans in debug
if (BuildConfig.DEBUG) {
    db.query("EXPLAIN QUERY PLAN SELECT * FROM orders WHERE userId = ?",
        arrayOf(userId)).use { cursor ->
        while (cursor.moveToNext()) {
            Log.d("QueryPlan", cursor.getString(3)) // detail column
        }
    }
}
// "USING INDEX" in output = index being used ✅
// "SCAN TABLE" = full table scan ❌
```

---

## DataStore vs SharedPreferences

```kotlin
// SharedPreferences: synchronous API, main-thread safe issues, no type safety
// DataStore (Preferences): async Flow API, safe for multi-process, type safe

// ✅ GOOD — Preferences DataStore
val Context.dataStore by preferencesDataStore(name = "settings")

// Write
suspend fun saveTheme(isDark: Boolean) {
    context.dataStore.edit { settings ->
        settings[IS_DARK_THEME] = isDark
    }
}

// Read (reactive Flow)
val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
    prefs[IS_DARK_THEME] ?: false
}

// ✅ Proto DataStore for complex structured data (more type-safe)
// Uses protobuf schema — see DataStore docs
```

---

## File I/O Best Practices

```kotlin
// ✅ Always do file I/O on Dispatchers.IO
suspend fun readConfig(): Config = withContext(Dispatchers.IO) {
    context.openFileInput("config.json").bufferedReader().use { reader ->
        Json.decodeFromString(reader.readText())
    }
}

// ✅ Use BufferedReader/Writer for text files
suspend fun appendLog(entry: String) = withContext(Dispatchers.IO) {
    context.openFileOutput("app.log", Context.MODE_APPEND).bufferedWriter().use {
        it.write(entry + "\n")
    }
}

// ✅ Use channels/streams for large files instead of readBytes()
suspend fun copyLargeFile(src: File, dst: File) = withContext(Dispatchers.IO) {
    src.inputStream().buffered().use { input ->
        dst.outputStream().buffered().use { output ->
            input.copyTo(output, bufferSize = 8192)
        }
    }
}

// ✅ Use appropriate storage location
// Internal storage: context.filesDir (private, not backed up by default)
// Cache: context.cacheDir (OS can delete, no guaranteed persistence)
// External: MediaStore API (API 29+) or SAF for user-visible files
```

---

## Database Profiling

```bash
# Enable query logging
adb shell setprop log.tag.SQLiteLog V
adb logcat -s SQLiteLog

# DB Inspector in Android Studio
# View → Tool Windows → App Inspection → Database Inspector
# Live query execution and table inspection
```

---

## Database Checklist

- [ ] Indices on all foreign keys and frequently filtered/sorted columns
- [ ] `SELECT` only needed columns — no `SELECT *` in production queries
- [ ] Batch writes in `@Transaction`
- [ ] WAL journal mode enabled for write-heavy apps
- [ ] `@Relation` used for related data — no N+1 queries
- [ ] `Room.databaseBuilder().allowMainThreadQueries()` NOT present
- [ ] SharedPreferences migrated to DataStore
- [ ] File I/O on `Dispatchers.IO`
- [ ] Query plans checked with `EXPLAIN QUERY PLAN` — no `SCAN TABLE`