package org.b1051c.einkctl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs shell commands as root through Magisk's su. One worker thread keeps commands ordered. */
final class Root {
    private static final ExecutorService Q = Executors.newSingleThreadExecutor();

    private Root() {}

    /** Synchronous; returns stdout+stderr (trimmed), or "ERR: …". Do not call on the UI thread. */
    static String run(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            p.waitFor();
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "ERR: " + e;
        }
    }

    /** Fire-and-forget, in order. */
    static void async(String cmd) {
        Q.execute(() -> run(cmd));
    }

    static void async(String cmd, java.util.function.Consumer<String> done) {
        Q.execute(() -> done.accept(run(cmd)));
    }
}
