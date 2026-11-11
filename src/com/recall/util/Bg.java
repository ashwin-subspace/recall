package com.recall.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal off-main-thread runner. Network on the main thread throws on Android. */
public class Bg {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Work<T> { T run() throws Exception; }

    public interface Done<T> {
        void ok(T value);
        void fail(Exception e);
    }

    public static <T> void go(final Work<T> work, final Done<T> done) {
        POOL.execute(new Runnable() {
            public void run() {
                try {
                    final T v = work.run();
                    MAIN.post(new Runnable() { public void run() { done.ok(v); } });
                } catch (final Exception e) {
                    MAIN.post(new Runnable() { public void run() { done.fail(e); } });
                }
            }
        });
    }

    public static void main(Runnable r) { MAIN.post(r); }
}
