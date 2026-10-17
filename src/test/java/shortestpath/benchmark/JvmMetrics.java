package shortestpath.benchmark;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

/** The benchmark JVM's memory: allocation by this thread, heap retained after a GC, and peaks. */
final class JvmMetrics {
    private static final com.sun.management.ThreadMXBean THREADS = threads();

    private JvmMetrics() { }

    private static com.sun.management.ThreadMXBean threads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) threads;
            if (sun.isThreadAllocatedMemorySupported()) {
                sun.setThreadAllocatedMemoryEnabled(true);
                return sun;
            }
        }
        return null;
    }

    /** Bytes this thread has allocated so far, or -1 where the JVM can't say. */
    static long allocatedBytes() {
        return THREADS == null ? -1 : THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Heap in use once garbage is collected: what the JVM is keeping alive at this point. */
    static long retainedHeapBytes() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * The sum of each heap pool's peak use. The pools peak at different moments, so this is an
     * upper bound on the heap's peak; it moves with allocation pressure as well as live data.
     */
    static long peakHeapBytes() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                total += pool.getPeakUsage().getUsed();
            }
        }
        return total;
    }

    /** The process's peak resident set (VmHWM), or -1 off Linux. */
    static long peakResidentBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Not Linux.
        }
        return -1;
    }

    static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionCount());
        }
        return total;
    }

    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }
}
