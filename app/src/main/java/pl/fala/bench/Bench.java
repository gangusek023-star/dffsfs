package pl.fala.bench;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Testy wydajności w czystej Javie (procesor, pamięć, dysk).
 * Każdy test pracuje przez stały czas i zwraca wynik „na sekundę”.
 */
public final class Bench {

    public interface Live {
        void update(float fraction, String value);
    }

    // Punkty odniesienia: przy tych wynikach kategoria ma 1000 pkt.
    static final double REF_INT = 520e6;    // operacje całkowite / s
    static final double REF_FLOAT = 80e6;   // iteracje Mandelbrota / s
    static final double REF_SHA = 500;      // MB/s
    static final double REF_SORT = 3.3e6;   // elementów / s
    static final double REF_MULTI = 2000;   // jednostki pracy / s (wszystkie rdzenie)
    static final double REF_BW = 6.0;       // GB/s
    static final double REF_LAT = 150;      // ns
    static final double REF_WRITE = 150;    // MB/s
    static final double REF_IOPS = 300;     // losowe zapisy 4 KB z synchronizacją / s
    static final double REF_GPU = 45;       // przebiegi / s

    static volatile int sink;

    private Bench() {
    }

    // ---------- procesor: elementy pracy ----------

    /** Mieszanka operacji całkowitych (xorshift, mnożenia, dzielenie, przesunięcia). */
    static int intWork(int seed, int n) {
        int x = seed | 1, acc = 0;
        for (int i = 0; i < n; i++) {
            x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
            acc += (x * 31 + i) ^ (acc >>> 3);
            acc += x % 1013;
        }
        return acc;
    }

    /** Iteracje zbioru Mandelbrota – arytmetyka zmiennoprzecinkowa. Zwraca liczbę iteracji. */
    static long floatWork(double cx0, int pixels) {
        long iters = 0;
        for (int p = 0; p < pixels; p++) {
            double cx = cx0 + (p % 64) * 0.004, cy = -0.3 + (p / 64) * 0.004;
            double zx = 0, zy = 0;
            int i = 0;
            while (i < 200 && zx * zx + zy * zy < 4.0) {
                double t = zx * zx - zy * zy + cx;
                zy = 2 * zx * zy + cy;
                zx = t;
                i++;
            }
            iters += i;
        }
        return iters;
    }

    // ---------- procesor: testy ----------

    public static double intSingle(long ms, Live live) {
        long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
        long ops = 0;
        int chunk = 200_000;
        while (System.nanoTime() < end) {
            sink += intWork((int) ops, chunk);
            ops += chunk;
            if (live != null) live.update(frac(start, ms), fmt(ops / secs(start)) + " op/s");
        }
        return ops * 6.0 / secs(start); // ~6 operacji na iterację
    }

    public static double floatSingle(long ms, Live live) {
        long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
        long it = 0;
        double cx = -0.75;
        while (System.nanoTime() < end) {
            it += floatWork(cx, 4096);
            cx += 1e-7;
            if (live != null) live.update(frac(start, ms), fmt(it / secs(start)) + " it/s");
        }
        return it / secs(start);
    }

    public static double sha(long ms, Live live) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            new Random(1).nextBytes(buf);
            long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
            long bytes = 0;
            while (System.nanoTime() < end) {
                md.update(buf);
                bytes += buf.length;
                if ((bytes & ((4 << 20) - 1)) == 0) sink += md.digest()[0];
                if (live != null) live.update(frac(start, ms), String.format("%.0f MB/s", bytes / 1048576.0 / secs(start)));
            }
            return bytes / 1048576.0 / secs(start);
        } catch (Exception e) {
            return 0;
        }
    }

    public static double sort(long ms, Live live) {
        int n = 200_000;
        int[] src = new int[n];
        Random r = new Random(7);
        for (int i = 0; i < n; i++) src[i] = r.nextInt();
        long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
        long items = 0;
        while (System.nanoTime() < end) {
            int[] a = src.clone();
            Arrays.sort(a);
            sink += a[n / 2];
            items += n;
            if (live != null) live.update(frac(start, ms), fmt(items / secs(start)) + " el/s");
        }
        return items / secs(start);
    }

    /** Wszystkie rdzenie naraz: ta sama mieszanka pracy całkowitej i zmiennoprzecinkowej. */
    public static double multi(long ms, int threads, Live live) {
        AtomicLong units = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                long local = 0;
                double cx = -0.75 + id * 0.01;
                while (!stop.get()) {
                    sink += intWork(id * 7919 + (int) local, 100_000);
                    floatWork(cx, 512);
                    local++;
                    units.incrementAndGet();
                }
            });
            ts[t].setPriority(Thread.MAX_PRIORITY);
            ts[t].start();
        }
        long start = System.nanoTime(), end = start + ms * 1_000_000L;
        try {
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                if (live != null) live.update(frac(start, ms), String.format("%.0f jedn./s · %d wątków", units.get() / secs(start), threads));
            }
        } finally {
            // zawsze zatrzymaj wątki – także przy przerwaniu testu
            stop.set(true);
            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException ignored) {
                }
            }
        }
        return units.get() / secs(start);
    }

    /** Wydajność jednego wątku tą samą jednostką co multi() – do porównania skalowania. */
    public static double multiUnitSingle(long ms) {
        long start = System.nanoTime(), end = start + ms * 1_000_000L, u = 0;
        while (System.nanoTime() < end) {
            sink += intWork((int) u, 100_000);
            floatWork(-0.75, 512);
            u++;
        }
        return u / secs(start);
    }

    // ---------- pamięć ----------

    public static double bandwidth(long ms, Live live) {
        int n = 4 * 1024 * 1024; // 16 MB na tablicę
        int[] a = new int[n], b = new int[n];
        for (int i = 0; i < n; i += 1024) a[i] = i;
        long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
        long bytes = 0;
        while (System.nanoTime() < end) {
            System.arraycopy(a, 0, b, 0, n);
            System.arraycopy(b, 0, a, 0, n);
            bytes += 4L * n * 2 * 2; // odczyt + zapis, dwa kopiowania
            if (live != null) live.update(frac(start, ms), String.format("%.1f GB/s", bytes / 1e9 / secs(start)));
        }
        sink += a[n - 1024];
        return bytes / 1e9 / secs(start);
    }

    /** Opóźnienie losowego dostępu (pointer chasing). Zwraca ns na dostęp. */
    public static double latency(long ms, Live live) {
        int n = 8 * 1024 * 1024; // 32 MB – większe niż pamięć podręczna
        int[] next = new int[n];
        for (int i = 0; i < n; i++) next[i] = i;
        Random r = new Random(3);
        for (int i = n - 1; i > 0; i--) { // algorytm Sattolo – jeden duży cykl
            int j = r.nextInt(i);
            int t = next[i]; next[i] = next[j]; next[j] = t;
        }
        long end = System.nanoTime() + ms * 1_000_000L, start = System.nanoTime();
        long steps = 0;
        int p = 0;
        while (System.nanoTime() < end) {
            for (int k = 0; k < 100_000; k++) p = next[p];
            steps += 100_000;
            if (live != null) live.update(frac(start, ms), String.format("%.0f ns", secs(start) * 1e9 / steps));
        }
        sink += p;
        return secs(start) * 1e9 / steps;
    }

    // ---------- dysk ----------

    /**
     * Dysk: zapis sekwencyjny 96 MB (z synchronizacją) i losowe zapisy 4 KB, każdy wymuszony na dysk.
     * Zwraca {zapis MB/s, IOPS}. Odczytu nie mierzymy – zaraz po zapisie szedłby z pamięci RAM.
     */
    public static double[] storage(File dir, Live live) {
        File f = new File(dir, "bench.tmp");
        int mb = 96;
        byte[] buf = new byte[1024 * 1024];
        new Random(5).nextBytes(buf);
        double w = 0, iops = 0;
        try {
            long s = System.nanoTime();
            try (FileOutputStream fo = new FileOutputStream(f)) {
                for (int i = 0; i < mb; i++) {
                    buf[0] = (byte) i;
                    fo.write(buf);
                    if ((i & 15) == 15) fo.getFD().sync();
                    if (live != null) live.update(i / (float) mb * 0.6f, String.format("zapis %.0f MB/s", (i + 1) / secs(s)));
                }
                fo.getFD().sync();
            }
            w = mb / secs(s);
            byte[] small = new byte[4096];
            Random r = new Random(9);
            long pages = (mb * 1048576L) / 4096;
            s = System.nanoTime();
            int ops = 0;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                long end = s + 2_000_000_000L;
                while (System.nanoTime() < end && ops < 2000) {
                    r.nextBytes(small);
                    raf.seek((long) r.nextInt((int) pages) * 4096);
                    raf.write(small);
                    raf.getFD().sync();
                    ops++;
                    if (live != null) live.update(0.6f + Math.min(1f, (System.nanoTime() - s) / 2e9f) * 0.4f,
                            String.format("losowo %.0f IOPS", ops / secs(s)));
                }
            }
            iops = ops / secs(s);
        } catch (Exception ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return new double[]{w, iops};
    }

    // ---------- pomocnicze ----------

    static double secs(long startNs) {
        return Math.max(1e-6, (System.nanoTime() - startNs) / 1e9);
    }

    static float frac(long startNs, long ms) {
        return (float) Math.min(1.0, (System.nanoTime() - startNs) / 1e6 / ms);
    }

    static String fmt(double v) {
        if (v >= 1e9) return String.format("%.2f G", v / 1e9);
        if (v >= 1e6) return String.format("%.1f M", v / 1e6);
        if (v >= 1e3) return String.format("%.1f k", v / 1e3);
        return String.format("%.0f", v);
    }

    static int score(double v, double ref) {
        return (int) Math.round(Math.max(0, v / ref * 1000));
    }

    /** Lokalny test kalibracyjny na komputerze. */
    public static void main(String[] a) {
        System.out.println("int   " + fmt(intSingle(2000, null)));
        System.out.println("float " + fmt(floatSingle(2000, null)));
        System.out.println("sha   " + sha(2000, null));
        System.out.println("sort  " + fmt(sort(2000, null)));
        int th = Runtime.getRuntime().availableProcessors();
        double m = multi(3000, th, null), s1 = multiUnitSingle(2000);
        System.out.println("multi " + m + " single " + s1 + " threads " + th);
        System.out.println("bw    " + bandwidth(2000, null));
        System.out.println("lat   " + latency(2000, null));
        double[] st = storage(new File("/tmp"), null);
        System.out.println("disk  " + st[0] + " MB/s / " + st[1] + " IOPS");
    }
}
