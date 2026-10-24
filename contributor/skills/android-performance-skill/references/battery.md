# Battery & Background Work — Deep Reference

## Android Battery Restrictions

| Mechanism | API Level | Effect |
|---|---|---|
| Doze Mode | API 23+ | Defers network, wakelocks, jobs when screen off & stationary |
| App Standby | API 23+ | Restricts background activity for rarely used apps |
| Background Limits | API 26+ | Kills background services; restricts implicit broadcasts |
| Battery Saver | User opt-in | Reduces background work system-wide |
| App Hibernation | API 31+ | Removes permissions + cache for unused apps |

---

## WorkManager — The Right Tool for Background Work

WorkManager is the **recommended solution** for deferrable, guaranteed background work.

```kotlin
// Dependency
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Define work
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val data = api.fetchLatestData()
            db.insertAll(data)
            Result.success()
        } catch (e: IOException) {
            // Retry up to 3 times with exponential backoff
            if (runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }
}

// Schedule periodic work
val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
    repeatInterval = 1,
    repeatIntervalTimeUnit = TimeUnit.HOURS
)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    )
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "sync",
    ExistingPeriodicWorkPolicy.KEEP,
    syncRequest
)

// One-time work with input data
val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
    .setInputData(workDataOf("file_path" to filePath))
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) // fast-track
    .build()

WorkManager.getInstance(context).enqueue(uploadRequest)
```

---

## Wake Locks — Use Sparingly

```kotlin
// ❌ BAD — holding wakelock indefinitely
val wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SyncLock"
)
wakeLock.acquire() // never released! kills battery

// ✅ GOOD — always release, use timeout
wakeLock.acquire(10 * 60 * 1000L) // 10 min max timeout

try {
    doWork()
} finally {
    if (wakeLock.isHeld) wakeLock.release()
}

// ✅ BEST — let WorkManager manage wakelocks for you
// WorkManager automatically holds a wakelock while your Worker runs
```

---

## Foreground Services

```kotlin
// Required for long-running user-visible operations (music, navigation, download)
class DownloadService : Service() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading...")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, 0, true)
            .build()
        
        // Must call within 5 seconds of onStartCommand
        startForeground(NOTIFICATION_ID, notification)
        
        // Do work in coroutine
        serviceScope.launch {
            downloadFile()
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
}

// Declare in manifest
// <service android:name=".DownloadService"
//          android:foregroundServiceType="dataSync" />
```

---

## Network Efficiency

```kotlin
// 1. Batch network requests — don't make many small calls
// ❌ BAD — one call per item
items.forEach { item -> api.updateItem(item) }

// ✅ GOOD — batch update endpoint
api.updateItems(items)

// 2. Schedule non-urgent syncs for when charging + Wi-Fi
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)   // Wi-Fi
    .setRequiresCharging(true)
    .build()

// 3. Use conditional GET / ETag caching in OkHttp
val client = OkHttpClient.Builder()
    .cache(Cache(cacheDir, 10 * 1024 * 1024)) // 10MB disk cache
    .build()

// 4. Respect Doze — don't use setExact alarms for network work
// Use WorkManager with NetworkType constraint instead

// 5. Check network availability before starting work
val networkInfo = connectivityManager.activeNetworkInfo
if (networkInfo?.isConnected == true) { /* proceed */ }

// Better: use NetworkCallback for reactive monitoring
val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) { /* start sync */ }
    override fun onLost(network: Network) { /* pause sync */ }
}
connectivityManager.registerDefaultNetworkCallback(networkCallback)
```

---

## Location Efficiency

```kotlin
// ❌ BAD — high frequency, high accuracy (GPS drains battery)
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_HIGH_ACCURACY,
    1000 // every second
).build()

// ✅ GOOD — request minimum needed
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_BALANCED_POWER_ACCURACY, // cell/wifi, not GPS
    60_000 // every minute
)
    .setMinUpdateIntervalMillis(30_000)
    .setMaxUpdateDelayMillis(120_000) // allow OS to batch
    .build()

// ✅ Use Geofencing for location triggers instead of polling
val geofence = Geofence.Builder()
    .setRequestId("home_zone")
    .setCircularRegion(lat, lng, radiusMeters)
    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
    .setExpirationDuration(Geofence.NEVER_EXPIRE)
    .build()
```

---

## Sensor Efficiency

```kotlin
// ❌ BAD — highest rate sensor
sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)

// ✅ GOOD — use appropriate rate
sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)

// ✅ Unregister when not in foreground
override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
}
override fun onResume() {
    super.onResume()
    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
}
```

---

## Battery Profiler

```bash
# Battery Historian (web tool)
# 1. Reset battery stats
adb shell dumpsys batterystats --reset

# 2. Unplug and use the app

# 3. Collect bugreport
adb bugreport bugreport.zip

# 4. Open at https://bathist.ef.lc/ or run locally
# Look for: wakelocks, wake-up alarms, sync activity, GPS usage
```

---

## Battery Checklist

- [ ] Deferrable background work uses WorkManager with appropriate constraints
- [ ] No indefinite wakelocks — always use timeout + try/finally release
- [ ] Foreground services used only for truly user-visible operations
- [ ] Location requests use minimum accuracy/frequency needed
- [ ] Sensors unregistered in `onPause()`
- [ ] Network batched — no per-item API calls
- [ ] No polling loops — use callbacks / Flow / WorkManager
- [ ] Battery Historian run to identify unexpected wakeups