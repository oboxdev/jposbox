package com.jposbox.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Installs the jPosBox CA ({@link SelfSignedCert#caCertPath()}) as a trusted
 * root for the current user, so browsers on <em>this</em> machine accept the
 * HTTPS server certificate without a warning. Other devices on the LAN have to
 * install the CA themselves (it is downloadable from {@code /ca.crt}).
 *
 * <p>Everything is installed per-user, never system-wide, so no admin rights
 * are needed. The OS still shows its own confirmation (macOS password prompt,
 * Windows security dialog) -- that is deliberate and cannot be bypassed.
 *
 * <p>Firefox keeps its own certificate store on macOS and Windows and is not
 * covered there; on Linux the Firefox profiles' NSS databases are covered too.
 */
public final class CaTrustInstaller {

    static final String NICKNAME = "jPosBox Local CA";
    private static final long TIMEOUT_SECONDS = 120; // the OS may be waiting on the user's password

    public record Result(boolean ok, String message) {
    }

    /** One command to run; {@code ignoreFailure} for best-effort cleanup steps (e.g. deleting an absent cert). */
    record Command(List<String> args, boolean ignoreFailure) {
        static Command of(String... args) {
            return new Command(List.of(args), false);
        }

        static Command bestEffort(String... args) {
            return new Command(List.of(args), true);
        }
    }

    private CaTrustInstaller() {
    }

    public static Result install() {
        try {
            Path ca = SelfSignedCert.ensureCaCertFile();
            return install(ca, System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
        } catch (Exception e) {
            return new Result(false, "Could not prepare the certificate: " + e.getMessage());
        }
    }

    static Result install(Path caCrt, String osName, Path home) {
        String os = osName.toLowerCase();
        boolean linux = !os.contains("mac") && !os.contains("win");
        if (linux) {
            try {
                Files.createDirectories(home.resolve(".pki").resolve("nssdb"));
            } catch (IOException e) {
                return new Result(false, "Could not create " + home.resolve(".pki/nssdb") + ": " + e.getMessage());
            }
        }

        for (Command cmd : commandsFor(osName, caCrt, home)) {
            Result r = run(cmd);
            if (!r.ok()) {
                return r;
            }
        }
        return new Result(true, successMessage(os));
    }

    static List<Command> commandsFor(String osName, Path caCrt, Path home) {
        String os = osName.toLowerCase();
        String crt = caCrt.toString();
        if (os.contains("mac")) {
            Path keychain = home.resolve("Library").resolve("Keychains").resolve("login.keychain-db");
            return List.of(Command.of("security", "add-trusted-cert", "-r", "trustRoot", "-k", keychain.toString(), crt));
        }
        if (os.contains("win")) {
            return List.of(Command.of("certutil", "-addstore", "-user", "-f", "Root", crt));
        }
        List<Command> cmds = new ArrayList<>();
        for (Path db : linuxNssDirs(home)) {
            String sql = "sql:" + db;
            if (!Files.exists(db.resolve("cert9.db"))) {
                cmds.add(Command.of("certutil", "-d", sql, "-N", "--empty-password"));
            }
            cmds.add(Command.bestEffort("certutil", "-d", sql, "-D", "-n", NICKNAME)); // keep re-install idempotent
            cmds.add(Command.of("certutil", "-d", sql, "-A", "-t", "C,,", "-n", NICKNAME, "-i", crt));
        }
        return cmds;
    }

    /** Chrome/Chromium's shared NSS db, plus every Firefox profile that already has one. */
    static List<Path> linuxNssDirs(Path home) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(home.resolve(".pki").resolve("nssdb"));
        for (Path firefoxRoot : List.of(
                home.resolve(".mozilla").resolve("firefox"),
                home.resolve("snap").resolve("firefox").resolve("common").resolve(".mozilla").resolve("firefox"))) {
            if (!Files.isDirectory(firefoxRoot)) {
                continue;
            }
            try (Stream<Path> profiles = Files.list(firefoxRoot)) {
                profiles.filter(p -> Files.exists(p.resolve("cert9.db"))).sorted().forEach(dirs::add);
            } catch (IOException ignored) {
            }
        }
        return dirs;
    }

    private static Result run(Command cmd) {
        try {
            Process p = new ProcessBuilder(cmd.args()).redirectErrorStream(true).start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, "Timed out waiting for `" + cmd.args().get(0) + "` (was a confirmation dialog left open?).");
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0 && !cmd.ignoreFailure()) {
                return new Result(false, "`" + String.join(" ", cmd.args()) + "` failed (exit " + p.exitValue() + ")"
                        + (output.isEmpty() ? "" : ":\n" + output));
            }
            return new Result(true, output);
        } catch (IOException e) {
            String hint = cmd.args().get(0).equals("certutil") && !System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? " Install it (Debian/Ubuntu: `sudo apt install libnss3-tools`) or import ca.crt in the browser manually."
                    : "";
            return new Result(false, "Could not run `" + cmd.args().get(0) + "`: " + e.getMessage() + hint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted.");
        }
    }

    private static String successMessage(String os) {
        String base = "The jPosBox certificate is now trusted for your user account. Fully quit and reopen the browser.";
        if (os.contains("mac") || os.contains("win")) {
            return base + "\n\nFirefox uses its own certificate store: in Firefox, open about:config and set"
                    + " security.enterprise_roots.enabled to true (or import ca.crt in Settings > Certificates).";
        }
        return base;
    }
}
