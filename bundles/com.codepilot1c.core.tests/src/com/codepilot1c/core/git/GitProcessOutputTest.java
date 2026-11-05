package com.codepilot1c.core.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;

public class GitProcessOutputTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void largeStatusReturnsEveryEntry() throws Exception {
        Path repository = temporary.newFolder("repository").toPath();
        git(repository, "init");
        for (int index = 0; index < 1000; index++) {
            Files.writeString(repository.resolve(String.format("%04d-%s.txt", index, "question".repeat(15))), "data");
        }
        git(repository, "add", ".");
        JsonObject result = new GitService().inspect("large-status", GitOperation.STATUS, repository, Map.of());
        assertEquals(1001, result.getAsJsonArray("entries").size());
    }

    @Test public void capturesBothLargeStreamsWithoutTruncating() throws Exception {
        Object output = runFixture("output", 5);
        String stdout = field(output, "stdout");
        String stderr = field(output, "stderr");
        assertTrue(stdout.length() > 200000);
        assertTrue(stderr.length() > 200000);
        assertTrue(stdout.startsWith("stdout-first\n"));
        assertTrue(stdout.endsWith("stdout-last\n"));
        assertTrue(stderr.startsWith("stderr-first\n"));
        assertTrue(stderr.endsWith("stderr-last\n"));
    }

    @Test public void classifiesFailureAfterReadingLargeErrorStream() throws Exception {
        try {
            runFixture("failure", 5);
            fail("The nonzero exit code must be reported");
        } catch (GitToolException expected) {
            assertEquals(expected.getMessage(), GitErrorCode.AUTH_FAILED, expected.getCode());
        }
    }

    @Test public void timeoutStopsTheCreatedProcess() throws Exception {
        Path pidFile = temporary.getRoot().toPath().resolve("pid.txt");
        try {
            invoke(command("timeout", pidFile.toString()), 2);
            fail("The long command must time out");
        } catch (GitToolException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        long pid = Long.parseLong(Files.readString(pidFile));
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle != null) {
            handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(handle.isAlive());
        }
    }

    private Object runFixture(String mode, int seconds) throws Exception {
        return invoke(command(mode, "unused"), seconds);
    }

    private List<String> command(String mode, String pidFile) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path arguments = temporary.newFile("fixture.args").toPath();
        String classpath = System.getProperty("java.class.path").replace('\\', '/');
        String pidPath = pidFile.replace('\\', '/');
        String content = String.join("\n", "-cp", "\"" + classpath + "\"",
                OutputFixture.class.getName(), mode, "\"" + pidPath + "\"");
        Files.writeString(arguments, content, StandardCharsets.UTF_8);
        return List.of(executable, "@" + arguments);
    }

    private static Object invoke(List<String> command, int seconds) throws Exception {
        Method method = GitService.class.getDeclaredMethod("runCommand", String.class, Path.class, int.class, List.class);
        method.setAccessible(true);
        try {
            return method.invoke(new GitService(), "output-test", null, seconds, command);
        } catch (InvocationTargetException exception) {
            throw (Exception) exception.getCause();
        }
    }

    private static String field(Object output, String name) throws Exception {
        Method method = output.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (String) method.invoke(output);
    }

    private static void git(Path directory, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
    }

    public static class OutputFixture {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("timeout")) {
                Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60000);
                return;
            }
            System.out.print("stdout-first\n");
            System.err.print("stderr-first\n");
            for (int index = 0; index < 2000; index++) {
                System.out.print("s".repeat(128) + "\n");
                System.err.print("e".repeat(128) + "\n");
            }
            System.out.print("stdout-last\n");
            System.err.print("stderr-last\n");
            if (arguments[0].equals("failure")) {
                System.err.println("Authentication failed");
                System.exit(128);
            }
        }
    }
}
