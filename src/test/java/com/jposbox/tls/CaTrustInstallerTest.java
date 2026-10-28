package com.jposbox.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaTrustInstallerTest {

    private static final Path CRT = Path.of("/home/u/.jposbox/ca.crt");

    @Test
    void macInstallsIntoUsersLoginKeychainWithoutAdmin(@TempDir Path home) {
        List<CaTrustInstaller.Command> cmds = CaTrustInstaller.commandsFor("Mac OS X", CRT, home);

        assertEquals(1, cmds.size());
        List<String> args = cmds.get(0).args();
        assertEquals("security", args.get(0));
        assertEquals("add-trusted-cert", args.get(1));
        assertFalse(args.contains("-d"), "-d means admin domain, which needs root");
        assertTrue(args.contains(home.resolve("Library/Keychains/login.keychain-db").toString()));
        assertEquals(CRT.toString(), args.get(args.size() - 1));
    }

    @Test
    void windowsInstallsIntoCurrentUserRootStore(@TempDir Path home) {
        List<CaTrustInstaller.Command> cmds = CaTrustInstaller.commandsFor("Windows 11", CRT, home);

        assertEquals(List.of("certutil", "-addstore", "-user", "-f", "Root", CRT.toString()), cmds.get(0).args());
    }

    @Test
    void linuxInitialisesMissingNssDbThenReplacesAndAddsCert(@TempDir Path home) {
        List<CaTrustInstaller.Command> cmds = CaTrustInstaller.commandsFor("Linux", CRT, home);
        String sql = "sql:" + home.resolve(".pki/nssdb");

        assertEquals(List.of("certutil", "-d", sql, "-N", "--empty-password"), cmds.get(0).args());
        assertTrue(cmds.get(1).ignoreFailure(), "deleting a not-yet-installed cert must not abort");
        assertTrue(cmds.get(1).args().contains("-D"));
        assertTrue(cmds.get(2).args().containsAll(List.of("-A", "-t", "C,,", "-i", CRT.toString())));
    }

    @Test
    void linuxAlsoCoversExistingFirefoxProfilesButNotEmptyOnes(@TempDir Path home) throws Exception {
        Path root = home.resolve(".mozilla/firefox");
        Path withDb = Files.createDirectories(root.resolve("abc.default-release"));
        Files.createFile(withDb.resolve("cert9.db"));
        Files.createDirectories(root.resolve("Crash Reports"));

        List<Path> dirs = CaTrustInstaller.linuxNssDirs(home);

        assertEquals(List.of(home.resolve(".pki/nssdb"), withDb), dirs);
        // existing db: no -N init step needed
        List<CaTrustInstaller.Command> cmds = CaTrustInstaller.commandsFor("Linux", CRT, home);
        assertFalse(cmds.stream().anyMatch(c -> c.args().contains("-N") && c.args().contains("sql:" + withDb)));
    }
}
