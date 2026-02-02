# Virtual Threads Friendliness

How to ensure your code works efficiently with Java 21+ virtual threads.

## Key Concept: Unmounting vs Pinning

When a virtual thread blocks on I/O, it should **unmount** from its carrier (platform) thread, allowing other virtual threads to run. If it doesn't unmount, it's called **pinning** - the virtual thread holds onto the carrier thread, defeating the purpose of virtual threads.

```
Virtual Thread blocks on I/O
         │
         ▼
    ┌─────────┐
    │ Unmount │ ← Good: carrier thread freed for other work
    └─────────┘
         │
         ▼
    I/O completes
         │
         ▼
    ┌─────────┐
    │ Remount │ ← Virtual thread continues on (possibly different) carrier
    └─────────┘
```

## Safe Operations (Unmount Properly)

Most JDK I/O operations automatically unmount on blocking:

| Category | Safe Operations |
|----------|-----------------|
| **Network** | `java.net.Socket`, `java.net.http.HttpClient`, `java.nio.channels.SocketChannel` |
| **Blocking** | `Thread.sleep()`, `LockSupport.park()` |
| **Concurrency** | `BlockingQueue.take()`, `Future.get()`, `CompletableFuture.join()`, `ReentrantLock.lock()` |
| **I/O Streams** | When backed by sockets |

## Problem Areas (May Pin)

### 1. `synchronized` Blocks with I/O

```java
// BAD: Pins the carrier thread during I/O
synchronized (lock) {
    socket.read(buffer);  // Pinned!
}

// GOOD: Use ReentrantLock instead
lock.lock();
try {
    socket.read(buffer);  // Unmounts properly
} finally {
    lock.unlock();
}
```

### 2. Native Code / JNI

Native libraries may pin because the JVM can't track their blocking behavior.

### 3. Some File Operations

File I/O behavior varies by OS and filesystem. Network I/O is generally safer.

## How to Detect Pinning

### JVM Flag (Recommended)

```bash
# Log all pinning events with full stack traces
java -Djdk.tracePinnedThreads=full -jar myapp.jar

# Or short format
java -Djdk.tracePinnedThreads=short -jar myapp.jar
```

### Programmatically

```java
// Set before creating virtual threads
System.setProperty("jdk.tracePinnedThreads", "full");

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        suspiciousLibraryCall();  // Check console for pinning warnings
    }).get();
}
```

### Example Output

```
Thread[#21,ForkJoinPool-1-worker-1,5,CarrierThreads]
    java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:180)
    java.base/java.lang.VirtualThread.parkOnCarrierThread(VirtualThread.java:632)
    ...
    com.example.MyClass.synchronizedMethod(MyClass.java:42)  <-- Pinning cause
```

## Third-Party Library Compatibility

### Known Safe Libraries

| Library | Safe Version | Notes |
|---------|--------------|-------|
| `java.net.http.HttpClient` | JDK 11+ | Used in this project |
| HikariCP | 5.0+ | Connection pool |
| Jedis | 4.3+ | Redis client |
| Netty | 4.1.86+ | Network framework |
| Most JDBC drivers | Recent versions | Check your specific driver |

### Checking a Library

1. **Check documentation** - Many libraries now document virtual thread support
2. **Check for `synchronized` in hot paths** - Look at source code for I/O methods
3. **Run with tracing** - Use `-Djdk.tracePinnedThreads=full` in tests
4. **Check issue trackers** - Search for "virtual thread" or "Loom" issues

## This Project's Compatibility

The `HttpPageFetcher` uses `java.net.http.HttpClient`, which is virtual-thread-safe:

```java
// From HttpPageFetcher.java - safe for virtual threads
HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
```

The `HttpClient.send()` method properly unmounts on network I/O, making it efficient to run many concurrent requests on virtual threads.

## Best Practices

1. **Prefer `ReentrantLock` over `synchronized`** for code that does I/O
2. **Update dependencies** to latest versions for virtual thread support
3. **Test with tracing enabled** to catch pinning issues early
4. **Avoid long-running CPU work** on virtual threads (use platform threads for compute)
5. **Don't pool virtual threads** - create them freely, they're cheap

## Further Reading

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Virtual Threads - Oracle Documentation](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
