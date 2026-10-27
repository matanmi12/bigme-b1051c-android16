package org.b1051c.einkctl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs shell commands as root through Magisk's su. One worker thread keeps async commands ordered.
 * Review 2026-10-21 (Medium): every command used to start its own `su -c`, which with Magisk's default policy meant
 * a "granted superuser rights" toast and a log row per app switch, and a hung command blocked the queue for good.
 * Now one su shell per mount namespace stays open; each command runs in a subshell, its output is read up to a
 * sentinel, and a command that does not finish within its timeout takes the shell down (the next one restarts it).
 */
final class Root {
    private static final ExecutorService Q = Executors.newSingleThreadExecutor();
    private static final Shell NORMAL = new Shell(false), GLOBAL = new Shell(true);
    static final long TIMEOUT_MS = 10_000;

    private Root() {}

    /** Synchronous; returns stdout+stderr (trimmed), or "ERR: …". Do not call on the UI thread. */
    static String run(String cmd) { return NORMAL.exec(cmd, TIMEOUT_MS); }

    /** Like run, but in the global mount namespace (su -mm). su inherits this app's namespace, where app data
     *  isolation hides other apps' /data/user_de and /data/data folders (e.g. LatinIME's prefs). */
    static String runGlobal(String cmd) { return GLOBAL.exec(cmd, TIMEOUT_MS); }

    /** Fire-and-forget, in order. */
    static void async(String cmd) { Q.execute(() -> run(cmd)); }

    static void async(String cmd, java.util.function.Consumer<String> done) { Q.execute(() -> done.accept(run(cmd))); }

    private static final class Shell {
        private final boolean global;
        private Process p;
        private BufferedWriter in;
        private BufferedReader out;
        private long n;

        Shell(boolean global) { this.global = global; }

        private void start() throws java.io.IOException {
            kill();
            ProcessBuilder pb = new ProcessBuilder(global ? new String[] {"su", "-mm"} : new String[] {"su"});
            p = pb.redirectErrorStream(true).start();
            in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), "UTF-8"));
            out = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        }

        private void kill() {
            if (p != null) { try { p.destroyForcibly(); } catch (RuntimeException ignored) {} }
            p = null; in = null; out = null;
        }

        synchronized String exec(String cmd, long timeoutMs) {
            try {
                if (p == null || !p.isAlive()) start();
                String tag = "__einkctl_rc_" + (++n) + "__";
                in.write("( " + cmd + "\n) 2>&1; echo " + tag + "\n");
                in.flush();
                StringBuilder sb = new StringBuilder();
                long deadline = System.currentTimeMillis() + timeoutMs;
                for (;;) {
                    if (!out.ready()) {
                        if (!p.isAlive()) { kill(); return "ERR: shell exited"; }
                        if (System.currentTimeMillis() > deadline) {
                            android.util.Log.w("einkctl", "root command timed out (" + timeoutMs + " ms), restarting su: " + cmd);
                            kill();
                            return "ERR: timeout";
                        }
                        Thread.sleep(4);
                        continue;
                    }
                    String line = out.readLine();
                    if (line == null) { kill(); return "ERR: shell closed"; }
                    if (line.startsWith(tag)) break;
                    sb.append(line).append('\n');
                }
                return sb.toString().trim();
            } catch (Exception e) {
                kill();
                return "ERR: " + e;
            }
        }
    }
}
