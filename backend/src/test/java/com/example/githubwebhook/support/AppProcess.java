package com.example.githubwebhook.support;

import com.example.githubwebhook.GithubWebhookApplication;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real application as a child JVM, for tests that must kill it (Phase 2 crash tests, Phase 5 forced-kill scenario).
 * Deliberately no Spring annotations: test sources are on the child's classpath and would be component-scanned.
 * Launched from the test runtime classpath through an argfile (the classpath is ~22,000 characters, Windows' command
 * line limit is ~32,767), directly through the java binary so that destroyForcibly kills the JVM itself.
 * Verified on Windows; Linux is confirmed in Phase 4 CI.
 */
public final class AppProcess implements AutoCloseable {

    private final String name;
    private final int port;
    private final Process process;
    private final Path argfile;
    private final Path logFile;
    private final Thread shutdownHook;

    private AppProcess(String name, int port, Process process, Path argfile, Path logFile) {
        this.name = name;
        this.port = port;
        this.process = process;
        this.argfile = argfile;
        this.logFile = logFile;
        this.shutdownHook = new Thread(process::destroyForcibly, "kill-" + name);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Starts the app on a free port. Inherited SPRING_* and APP_* variables are stripped (a developer machine's
     * SPRING_DATASOURCE_PASSWORD would silently beat application.yml), so callers pass every setting the app needs.
     */
    public static AppProcess start(String name, Map<String, String> env) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort(); // tiny race window between close and the app binding, accepted
        }

        String classpath = String.join(File.pathSeparator, Arrays.stream(
                System.getProperty("java.class.path").split(File.pathSeparator)).map(e -> Path.of(e).toString()).toList());
        String appClasses = Path.of(codeSource()).toString();
        if (!classpath.contains(appClasses)) {
            throw new IllegalStateException("java.class.path does not contain the application classes (" + appClasses
                    + "); pass the test runtime classpath from build.gradle.kts instead");
        }
        Path argfile = Files.createTempFile("app-" + name + "-", ".args");
        // argfile quoting: a double-quoted value needs every backslash doubled
        Files.writeString(argfile, "-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8);
        Path logFile = Files.createTempFile("app-" + name + "-", ".log");

        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argfile, GithubWebhookApplication.class.getName()));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("SPRING_") || k.startsWith("APP_"));
        pb.environment().put("SERVER_PORT", Integer.toString(port));
        pb.environment().putAll(env);
        try {
            return new AppProcess(name, port, pb.start(), argfile, logFile);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(argfile);
            Files.deleteIfExists(logFile);
            throw e;
        }
    }

    private static String codeSource() {
        try {
            return Path.of(GithubWebhookApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Polls the webhook endpoint with an unsigned POST until any HTTP status comes back (401 is expected). */
    public void awaitReady(Duration timeout) throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create(webhookUrl())).timeout(Duration.ofSeconds(1))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("Process " + name + " exited before it was ready:\n" + logTail(40));
            }
            try {
                http.send(probe, java.net.http.HttpResponse.BodyHandlers.discarding());
                return;
            } catch (IOException notYet) {
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Process " + name + " not ready after " + timeout + ":\n" + logTail(40));
    }

    public int port() {
        return port;
    }

    public String webhookUrl() {
        return "http://localhost:" + port + "/webhooks/github";
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public String logTail(int lines) {
        try {
            List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(log unreadable: " + e + ")";
        }
    }

    /** SIGKILL equivalent; the exit value is not asserted (Windows 1, Linux 137). */
    public void kill() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException, IOException {
        try {
            if (process.isAlive()) {
                kill();
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shuttingDown) {
                // JVM is already shutting down; the hook itself does the kill
            }
            deleteQuietly(argfile);
            deleteQuietly(logFile);
        }
    }

    // Windows can hold the log file for a moment after the process died; retry briefly, never fail the test over it.
    private static void deleteQuietly(Path file) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException stillLocked) {
                Thread.sleep(100);
            }
        }
        file.toFile().deleteOnExit();
    }
}
