package com.stockpulse.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON reader and writer (RFC 8259).
 *
 * Objects become {@code LinkedHashMap<String,Object>}, arrays {@code ArrayList<Object>},
 * integral numbers {@code Long}, other numbers {@code BigDecimal}. Nesting depth is
 * capped so a hostile body cannot exhaust the stack.
 */
public final class Json {
    public static final int MAX_DEPTH = 32;

    private Json() {}

    public static final class ParseException extends RuntimeException {
        public ParseException(String msg, int pos) { super(msg + " at position " + pos); }
    }

    // ------------------------------------------------------------------ parse
    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new ParseException("trailing characters", p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new ParseException("expected a JSON object", 0);
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
            }
        }

        char peek() {
            if (i >= s.length()) throw new ParseException("unexpected end of input", i);
            return s.charAt(i);
        }

        void expect(char c) {
            if (peek() != c) throw new ParseException("expected '" + c + "'", i);
            i++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new ParseException("nesting too deep", i);
            char c = peek();
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw new ParseException("unexpected character '" + c + "'", i);
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw new ParseException("invalid literal", i);
            i += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new ParseException("expected string key", i);
                String k = string();
                ws();
                expect(':');
                ws();
                if (m.containsKey(k)) throw new ParseException("duplicate key \"" + k + "\"", i);
                m.put(k, value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new ParseException("expected ',' or '}'", i - 1);
            }
        }

        List<Object> array(int depth) {
            expect('[');
            List<Object> a = new ArrayList<>();
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw new ParseException("expected ',' or ']'", i - 1);
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return b.toString();
                if (c < 0x20) throw new ParseException("control character in string", i - 1);
                if (c != '\\') { b.append(c); continue; }
                char e = peek();
                i++;
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new ParseException("short \\u escape", i);
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad \\u escape", i);
                        }
                        i += 4;
                    }
                    default -> throw new ParseException("bad escape '\\" + e + "'", i - 1);
                }
            }
        }

        Object number() {
            int start = i;
            if (peek() == '-') i++;
            if (i < s.length() && s.charAt(i) == '0') {
                i++;
            } else {
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (d == i) throw new ParseException("bad number", start);
            }
            boolean integral = true;
            if (i < s.length() && s.charAt(i) == '.') {
                integral = false;
                i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (d == i) throw new ParseException("bad fraction", start);
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                integral = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (d == i) throw new ParseException("bad exponent", start);
            }
            String t = s.substring(start, i);
            if (integral && t.length() <= 18) return Long.parseLong(t);
            return new BigDecimal(t);
        }
    }

    // ------------------------------------------------------------------ write
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short) {
            b.append(v);
        } else if (v instanceof BigDecimal d) {
            b.append(d.toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite number");
            b.append(v);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
