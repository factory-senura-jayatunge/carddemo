package org.carddemo.identity.user;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The timing part of the password hashing pilot (identity-service.md, section 6, item 7), run in
 * a fresh JVM with the service's heap: {@code java -Xmx256m -cp <test classpath>
 * org.carddemo.identity.user.PasswordHashingBench}. It prints the median and p95 of one hash and
 * one check, of a wrong password and of one above 72 bytes against a bcrypt(12) hash (they should
 * take the same time), then runs 50 concurrent checks on virtual threads, as 50 sign-ons at once do, and
 * prints their p95 latency, the wall time and the heap used.
 */
public final class PasswordHashingBench {

    private PasswordHashingBench() {
    }

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        int concurrent = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        long t0 = System.nanoTime();
        PasswordHasher hasher = new PasswordHasher(12);
        System.out.printf("first use (encoder setup and dummy hash): %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        String password = "é".repeat(36) + "x";
        String stored = hasher.hash(password);

        long[] hash = new long[rounds];
        long[] check = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long s = System.nanoTime();
            hasher.hash(password);
            hash[i] = System.nanoTime() - s;
            s = System.nanoTime();
            if (!hasher.matches(password, stored)) {
                throw new IllegalStateException("check failed");
            }
            check[i] = System.nanoTime() - s;
        }
        System.out.printf("hash  n=%d p50=%.1f ms p95=%.1f ms%n", rounds, pct(hash, 50), pct(hash, 95));
        System.out.printf("check n=%d p50=%.1f ms p95=%.1f ms%n", rounds, pct(check, 50), pct(check, 95));

        String stored72 = "é".repeat(36);
        String bcryptStored = "{bcrypt}" + new BCryptPasswordEncoder(12).encode(stored72);
        int bcryptRounds = Math.max(5, rounds / 4);
        long[] wrong = new long[bcryptRounds];
        long[] tooLong = new long[bcryptRounds];
        for (int i = 0; i < bcryptRounds; i++) {
            long s = System.nanoTime();
            if (hasher.matches("é".repeat(35) + "e", bcryptStored)) {
                throw new IllegalStateException("bcrypt check matched");
            }
            wrong[i] = System.nanoTime() - s;
            s = System.nanoTime();
            if (hasher.matches(stored72 + "x", bcryptStored)) {
                throw new IllegalStateException("73 bytes matched");
            }
            tooLong[i] = System.nanoTime() - s;
        }
        System.out.printf("bcrypt(12) wrong password n=%d p50=%.1f ms p95=%.1f ms%n", bcryptRounds, pct(wrong, 50), pct(wrong, 95));
        System.out.printf("bcrypt(12) above 72 bytes n=%d p50=%.1f ms p95=%.1f ms%n", bcryptRounds, pct(tooLong, 50), pct(tooLong, 95));

        long[] latency = new long[concurrent];
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < concurrent; i++) {
                int n = i;
                calls.add(pool.submit(() -> {
                    long s = System.nanoTime();
                    boolean ok = hasher.matches(n % 2 == 0 ? password : password + "!", stored);
                    latency[n] = System.nanoTime() - s;
                    if (ok != (n % 2 == 0)) {
                        throw new IllegalStateException("wrong result " + n);
                    }
                    return null;
                }));
            }
            for (Future<?> f : calls) {
                f.get();
            }
        }
        long wall = System.nanoTime() - start;
        Runtime rt = Runtime.getRuntime();
        System.out.printf("%d concurrent checks: p50=%.1f ms p95=%.1f ms max=%.1f ms wall=%.1f ms, no OutOfMemoryError%n",
                concurrent, pct(latency, 50), pct(latency, 95), pct(latency, 100), wall / 1e6);
        System.out.printf("heap max=%d MiB used after=%d MiB%n", rt.maxMemory() >> 20,
                (rt.totalMemory() - rt.freeMemory()) >> 20);
    }

    private static double pct(long[] values, int p) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
    }
}
