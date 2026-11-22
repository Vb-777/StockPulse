package org.junit.shim;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs JUnit-5-style tests (default lifecycle: a new instance per test method) from a classes
 * directory. Offline stand-in for the JUnit Platform; exits non-zero if any test fails.
 */
public final class ShimRunner {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        List<String> classes = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith("Test.class") && !p.toString().contains("$"))
             .forEach(p -> classes.add(root.relativize(p).toString().replace('/', '.').replace(".class", "")));
        }
        classes.sort(null);
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (String cn : classes) {
            Class<?> c = Class.forName(cn);
            Method[] ms = c.getDeclaredMethods();
            Arrays.sort(ms, Comparator.comparing(Method::getName));
            List<Method> tests = new ArrayList<>(), before = new ArrayList<>(), after = new ArrayList<>(),
                    beforeAll = new ArrayList<>(), afterAll = new ArrayList<>();
            for (Method m : ms) {
                m.setAccessible(true);
                if (m.isAnnotationPresent(Test.class)) tests.add(m);
                if (m.isAnnotationPresent(BeforeEach.class)) before.add(m);
                if (m.isAnnotationPresent(AfterEach.class)) after.add(m);
                if (m.isAnnotationPresent(BeforeAll.class)) beforeAll.add(m);
                if (m.isAnnotationPresent(AfterAll.class)) afterAll.add(m);
            }
            if (tests.isEmpty()) continue;
            try {
                for (Method m : beforeAll) { requireStatic(m); m.invoke(null); }
            } catch (Throwable t) {
                failed += tests.size();
                failures.add(cn + " @BeforeAll: " + describe(unwrap(t)));
                continue;
            }
            for (Method t : tests) {
                long t0 = System.nanoTime();
                Throwable err = null;
                Object inst = null;
                try {
                    var ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    inst = ctor.newInstance();
                    for (Method m : before) m.invoke(inst);
                    t.invoke(inst);
                } catch (Throwable e) {
                    err = unwrap(e);
                }
                if (inst != null) {
                    for (Method m : after) {
                        try { m.invoke(inst); } catch (Throwable e) { if (err == null) err = unwrap(e); }
                    }
                }
                long ms2 = (System.nanoTime() - t0) / 1_000_000;
                if (err == null) {
                    passed++;
                    System.out.printf("  PASS %s.%s (%d ms)%n", c.getSimpleName(), t.getName(), ms2);
                } else {
                    failed++;
                    failures.add(c.getSimpleName() + "." + t.getName() + ": " + describe(err));
                    System.out.printf("  FAIL %s.%s (%d ms)%n", c.getSimpleName(), t.getName(), ms2);
                    err.printStackTrace(System.out);
                }
            }
            for (Method m : afterAll) {
                try { m.invoke(null); } catch (Throwable e) { failures.add(cn + " @AfterAll: " + describe(unwrap(e))); failed++; }
            }
        }
        System.out.printf("%n%d tests, %d passed, %d failed%n", passed + failed, passed, failed);
        failures.forEach(f -> System.out.println("  - " + f));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void requireStatic(Method m) {
        if (!Modifier.isStatic(m.getModifiers())) throw new IllegalStateException(m + " must be static");
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException ite && ite.getCause() != null) t = ite.getCause();
        return t;
    }

    private static String describe(Throwable t) { return t.getClass().getSimpleName() + ": " + t.getMessage(); }

    @SuppressWarnings("unused")
    private static void unused() throws IOException { }
}
