package org.junit.jupiter.api;

import java.util.Objects;
import org.junit.jupiter.api.function.Executable;

/** Subset of JUnit 5 Assertions with the same signatures, for offline runs only. */
public final class Assertions {
    private Assertions() { }

    private static AssertionError err(String msg, Object e, Object a) {
        return new AssertionError((msg == null ? "" : msg + " ==> ") + "expected: <" + e + "> but was: <" + a + ">");
    }

    public static void fail(String message) { throw new AssertionError(message); }

    public static void assertTrue(boolean c) { assertTrue(c, (String) null); }
    public static void assertTrue(boolean c, String m) { if (!c) throw new AssertionError(m == null ? "expected: <true> but was: <false>" : m); }
    public static void assertFalse(boolean c) { assertFalse(c, (String) null); }
    public static void assertFalse(boolean c, String m) { if (c) throw new AssertionError(m == null ? "expected: <false> but was: <true>" : m); }

    public static void assertNull(Object a) { assertNull(a, null); }
    public static void assertNull(Object a, String m) { if (a != null) throw err(m, null, a); }
    public static void assertNotNull(Object a) { assertNotNull(a, null); }
    public static void assertNotNull(Object a, String m) { if (a == null) throw new AssertionError(m == null ? "expected: not <null>" : m); }

    public static void assertEquals(int e, int a) { assertEquals(e, a, (String) null); }
    public static void assertEquals(int e, int a, String m) { if (e != a) throw err(m, e, a); }
    public static void assertEquals(long e, long a) { assertEquals(e, a, (String) null); }
    public static void assertEquals(long e, long a, String m) { if (e != a) throw err(m, e, a); }
    public static void assertEquals(double e, double a) { assertEquals(e, a, (String) null); }
    public static void assertEquals(double e, double a, String m) { if (Double.doubleToLongBits(e) != Double.doubleToLongBits(a)) throw err(m, e, a); }
    public static void assertEquals(double e, double a, double delta) { assertEquals(e, a, delta, null); }
    public static void assertEquals(double e, double a, double delta, String m) { if (Math.abs(e - a) > delta) throw err(m, e, a); }
    public static void assertEquals(Object e, Object a) { assertEquals(e, a, (String) null); }
    public static void assertEquals(Object e, Object a, String m) { if (!Objects.equals(e, a)) throw err(m, e, a); }

    public static void assertNotEquals(Object u, Object a) { assertNotEquals(u, a, null); }
    public static void assertNotEquals(Object u, Object a, String m) {
        if (Objects.equals(u, a)) throw new AssertionError((m == null ? "" : m + " ==> ") + "expected: not equal but was: <" + a + ">");
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex) { return assertThrows(type, ex, null); }
    public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex, String m) {
        try {
            ex.execute();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            AssertionError e = new AssertionError((m == null ? "" : m + " ==> ") + "unexpected exception type thrown, expected: <"
                    + type.getName() + "> but was: <" + t.getClass().getName() + ">");
            e.initCause(t);
            throw e;
        }
        throw new AssertionError((m == null ? "" : m + " ==> ") + "expected " + type.getName() + " to be thrown, but nothing was thrown");
    }
}
