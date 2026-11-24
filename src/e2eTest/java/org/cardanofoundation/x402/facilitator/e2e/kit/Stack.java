package org.cardanofoundation.x402.facilitator.e2e.kit;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives the e2e compose project from a test:
 * {@code docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE <args>}.
 * Restart a service with {@link #kill} + {@link #start}, never a bare {@code up -d}: a
 * differing environment could recreate the shim or postgres and reset their state.
 */
@Log4j2
public final class Stack {

    private Stack() {
    }

    public static void kill(String service) {
        compose("kill", service);
    }

    public static void start(String service) {
        compose("start", service);
    }

    public static void stop(String service) {
        compose("stop", service);
    }

    /** Starts the service only if it is not running; never recreates it or its dependencies. */
    public static void upNoRecreate(String service) {
        compose("up", "-d", "--no-deps", "--no-recreate", service);
    }

    /** A SQL statement against a postgres database, as bare tuples (psql -At). */
    public static String psql(String database, String sql) {
        return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-At", "-c", sql).strip();
    }

    /** Runs a compose subcommand; fails loudly on a non-zero exit. */
    public static String compose(String... args) {
        String project = requiredEnv("E2E_COMPOSE_PROJECT");
        String file = requiredEnv("E2E_COMPOSE_FILE");
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", project, "-f", file));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Drain first: a full pipe would block the child, and waitFor would then only time out.
            // A watchdog kills a hung child so readAllBytes cannot wait forever.
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(90, TimeUnit.SECONDS)) {
                        // A child of the CLI (e.g. the compose plugin) may hold stdout open after docker dies.
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            watchdog.interrupt();
            if (process.exitValue() == 137 || process.exitValue() == 143) {
                throw new IllegalStateException("timed out or killed: " + command + ": " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(command + " exited " + process.exitValue() + ": " + output);
            }
            log.info("{} -> {}", String.join(" ", args), output.strip());
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + command, e);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set - run the suite through e2e/run.sh");
        }
        return value;
    }
}
