package despia.ext.widgets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The widgets sample's shared rules on Android, the same as WidgetModel.swift on iOS: the two
 * templates (stat, list) and their one validator, a strict JSON reader and the canonical writer, the
 * widget deep link and the tap queue. Plain Java with no Android types, so it also runs on a JVM.
 *
 * <p>Customise here: the deep-link scheme (also in manifest.json, claims.intent_schemes), the limits
 * and the fields of a template (add them to the reader, the writer and WidgetUi.kt together).
 */
public final class WidgetModel {
    private WidgetModel() {
    }

    public static final int MAX_BYTES = 4096;
    public static final int MAX_DEPTH = 8;
    public static final int QUEUE_CAP = 32;
    public static final int MAX_LINK = 512;
    public static final String SCHEME = "nxwidgets";
    public static final String STAT = "stat";
    public static final String LIST = "list";

    public static boolean isTemplate(String t) {
        return STAT.equals(t) || LIST.equals(t);
    }

    /** A data error: the path of the first wrong field and the reason. */
    public static final class DataError extends Exception {
        public final String path;
        public final String reason;

        DataError(String path, String reason) {
            super(path + ": " + reason);
            this.path = path;
            this.reason = reason;
        }
    }

    /** JSON null, distinct from an absent key. */
    public static final Object NULL = new Object();

    // ------------------------------------------------------------------ JSON reader
    static final class Reader {
        private final int[] s;
        private int i;

        Reader(String text) {
            s = text.codePoints().toArray();
        }

        static DataError bad() {
            return new DataError("$", "bad json");
        }

        static Object parse(String text) throws DataError {
            Reader r = new Reader(text);
            Object v = r.value(1);
            r.space();
            if (r.i != r.s.length) throw bad();
            return v;
        }

        private int peek() {
            return i < s.length ? s[i] : -1;
        }

        private void space() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') i++;
        }

        private boolean literal(String w) {
            int[] u = w.codePoints().toArray();
            if (i + u.length > s.length) return false;
            for (int k = 0; k < u.length; k++) if (s[i + k] != u[k]) return false;
            i += u.length;
            return true;
        }

        private Object value(int depth) throws DataError {
            space();
            int c = peek();
            if ((c == '{' || c == '[') && depth > MAX_DEPTH) throw bad();
            if (c == '{') {
                i++;
                Map<String, Object> out = new LinkedHashMap<>();
                space();
                if (peek() == '}') {
                    i++;
                    return out;
                }
                while (true) {
                    space();
                    if (peek() != '"') throw bad();
                    String k = string();
                    space();
                    if (peek() != ':') throw bad();
                    i++;
                    Object v = value(depth + 1);
                    if (out.containsKey(k)) throw bad();
                    out.put(k, v);
                    space();
                    int e = peek();
                    i++;
                    if (e == '}') return out;
                    if (e != ',') throw bad();
                }
            }
            if (c == '[') {
                i++;
                List<Object> out = new ArrayList<>();
                space();
                if (peek() == ']') {
                    i++;
                    return out;
                }
                while (true) {
                    out.add(value(depth + 1));
                    space();
                    int e = peek();
                    i++;
                    if (e == ']') return out;
                    if (e != ',') throw bad();
                }
            }
            if (c == '"') return string();
            if (literal("true")) return Boolean.TRUE;
            if (literal("false")) return Boolean.FALSE;
            if (literal("null")) return NULL;
            return number();
        }

        private boolean digit(int c) {
            return c >= '0' && c <= '9';
        }

        private Double number() throws DataError {
            int start = i;
            if (peek() == '-') i++;
            if (peek() == '0') {
                i++;
            } else if (digit(peek())) {
                while (digit(peek())) i++;
            } else {
                throw bad();
            }
            if (peek() == '.') {
                i++;
                if (!digit(peek())) throw bad();
                while (digit(peek())) i++;
            }
            if (peek() == 'e' || peek() == 'E') {
                i++;
                if (peek() == '+' || peek() == '-') i++;
                if (!digit(peek())) throw bad();
                while (digit(peek())) i++;
            }
            // Only ASCII digits, signs, '.' and 'e' reach here; 1e999 parses as Infinity.
            return Double.parseDouble(new String(s, start, i - start));
        }

        private int hex4() throws DataError {
            if (i + 4 > s.length) throw bad();
            int v = 0;
            for (int k = 0; k < 4; k++) {
                int c = s[i++];
                int d;
                if (c >= '0' && c <= '9') d = c - '0';
                else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
                else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
                else throw bad();
                v = v * 16 + d;
            }
            return v;
        }

        private String string() throws DataError {
            i++;
            StringBuilder out = new StringBuilder();
            while (true) {
                int c = peek();
                if (c < 0x20) throw bad();
                i++;
                if (c == '"') return out.toString();
                if (c != '\\') {
                    // A lone surrogate code unit in the Java string itself.
                    if (c >= 0xD800 && c <= 0xDFFF) throw bad();
                    out.appendCodePoint(c);
                    continue;
                }
                int e = peek();
                i++;
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u': {
                        int u = hex4();
                        if (u >= 0xD800 && u <= 0xDBFF) {
                            if (i + 2 > s.length || s[i] != '\\' || s[i + 1] != 'u') throw bad();
                            i += 2;
                            int lo = hex4();
                            if (lo < 0xDC00 || lo > 0xDFFF) throw bad();
                            u = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
                        } else if (u >= 0xDC00 && u <= 0xDFFF) {
                            throw bad();
                        }
                        out.appendCodePoint(u);
                        break;
                    }
                    default:
                        throw bad();
                }
            }
        }
    }

    // ------------------------------------------------------------------ canonical writer
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder b) {
        if (v instanceof Map) {
            Map<String, Object> m = new TreeMap<>((Map<String, Object>) v);
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                escape(e.getKey(), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<Object>) v) {
                if (!first) b.append(',');
                first = false;
                write(x, b);
            }
            b.append(']');
        } else if (v instanceof String) {
            escape((String) v, b);
        } else if (v instanceof Double) {
            b.append(progressText((Double) v));
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer) {
            b.append(v.toString());
        } else {
            b.append("null");
        }
    }

    public static String progressText(double d) {
        long n = (long) Math.floor(d * 10000 + 0.5);
        if (n >= 10000) return "1";
        if (n <= 0) return "0";
        StringBuilder t = new StringBuilder(Long.toString(n));
        while (t.length() < 4) t.insert(0, '0');
        while (t.charAt(t.length() - 1) == '0') t.setLength(t.length() - 1);
        return "0." + t;
    }

    private static void escape(String s, StringBuilder b) {
        b.append('"');
        s.codePoints().forEach(c -> {
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 0x20) b.append(String.format("\\u%04x", c));
            else b.appendCodePoint(c);
        });
        b.append('"');
    }

    // ------------------------------------------------------------------ typed data
    public static final class Button {
        public final String id;
        public final String label;

        Button(String id, String label) {
            this.id = id;
            this.label = label;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            return m;
        }
    }

    public static final class Stat {
        public String title;
        public String value;
        public String unit;
        public String caption;
        public Double progress;
        public String trend;
        public String tint;
        public String link;
        public List<Button> actions;

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("value", value);
            put(m, "unit", unit);
            put(m, "caption", caption);
            put(m, "progress", progress);
            put(m, "trend", trend);
            put(m, "tint", tint);
            put(m, "link", link);
            if (actions != null) {
                List<Object> a = new ArrayList<>();
                for (Button x : actions) a.add(x.json());
                m.put("actions", a);
            }
            return m;
        }
    }

    public static final class Item {
        public String id;
        public String title;
        public String subtitle;
        public Boolean checked;
        public String link;

        Item copy() {
            Item c = new Item();
            c.id = id;
            c.title = title;
            c.subtitle = subtitle;
            c.checked = checked;
            c.link = link;
            return c;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            put(m, "subtitle", subtitle);
            put(m, "checked", checked);
            put(m, "link", link);
            return m;
        }
    }

    public static final class ListData {
        public String title;
        public List<Item> items = new ArrayList<>();
        public String empty;
        public String tint;
        public String link;
        public Button action;

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            List<Object> a = new ArrayList<>();
            for (Item x : items) a.add(x.json());
            m.put("items", a);
            put(m, "empty", empty);
            put(m, "tint", tint);
            put(m, "link", link);
            if (action != null) m.put("action", action.json());
            return m;
        }
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    /** Stat or ListData. */
    public static Object validate(String template, String text) throws DataError {
        if (!isTemplate(template)) throw new DataError("$", "bad template");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new DataError("$", "too large");
        Object v = Reader.parse(text);
        return STAT.equals(template) ? stat(v) : list(v);
    }

    public static String canonical(Object data) {
        if (data instanceof Stat) return write(((Stat) data).json());
        return write(((ListData) data).json());
    }

    static String join(String path, String key) {
        return "$".equals(path) ? key : path + "." + key;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v, String path, List<String> allowed) throws DataError {
        if (!(v instanceof Map)) throw new DataError(path, "not an object");
        Map<String, Object> o = (Map<String, Object>) v;
        List<String> unknown = new ArrayList<>();
        for (String k : o.keySet()) if (!allowed.contains(k)) unknown.add(k);
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            throw new DataError(join(path, unknown.get(0)), "unknown key");
        }
        return o;
    }

    static String text(Map<String, Object> o, String key, String path, int lo, int hi, boolean required) throws DataError {
        if (!o.containsKey(key)) {
            if (required) throw new DataError(path, "missing");
            return null;
        }
        Object v = o.get(key);
        if (!(v instanceof String)) throw new DataError(path, "not a string");
        String s = (String) v;
        if (s.codePoints().anyMatch(c -> c < 0x20 || c == 0x7F)) throw new DataError(path, "control character");
        int n = s.codePointCount(0, s.length());
        if (n < lo) throw new DataError(path, "empty");
        if (n > hi) throw new DataError(path, "too long");
        return s;
    }

    public static boolean isId(String s) {
        if (s == null || s.length() < 1 || s.length() > 32) return false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
        }
        return true;
    }

    static String id(Map<String, Object> o, String key, String path) throws DataError {
        String s = text(o, key, path, 1, 32, true);
        if (!isId(s)) throw new DataError(path, "bad id");
        return s;
    }

    /** A path inside the page's own app: starts with one "/", printable ASCII, no backslash. */
    public static boolean isLink(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_LINK || s.charAt(0) != '/') return false;
        if (s.length() > 1 && s.charAt(1) == '/') return false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < 0x21 || c > 0x7E || c == '\\') return false;
        }
        return true;
    }

    static String link(Map<String, Object> o, String key, String path) throws DataError {
        String s = text(o, key, path, 1, MAX_LINK, false);
        if (s != null && !isLink(s)) throw new DataError(path, "bad link");
        return s;
    }

    static String color(Map<String, Object> o, String key, String path) throws DataError {
        String s = text(o, key, path, 1, 7, false);
        if (s == null) return null;
        boolean ok = s.length() == 7 && s.charAt(0) == '#';
        for (int k = 1; ok && k < 7; k++) {
            char c = s.charAt(k);
            ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
        if (!ok) throw new DataError(path, "bad color");
        return s;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Map<String, Object> o, String key, String path, int hi, boolean required) throws DataError {
        if (!o.containsKey(key)) {
            if (required) throw new DataError(path, "missing");
            return null;
        }
        Object v = o.get(key);
        if (!(v instanceof List)) throw new DataError(path, "not an array");
        List<Object> a = (List<Object>) v;
        if (a.size() > hi) throw new DataError(path, "too many");
        return a;
    }

    static final List<String> BUTTON_KEYS = java.util.Arrays.asList("id", "label");
    static final List<String> STAT_KEYS = java.util.Arrays.asList(
            "title", "value", "unit", "caption", "progress", "trend", "tint", "link", "actions");
    static final List<String> LIST_KEYS = java.util.Arrays.asList("title", "items", "empty", "tint", "link", "action");
    static final List<String> ITEM_KEYS = java.util.Arrays.asList("id", "title", "subtitle", "checked", "link");

    static Button button(Object v, String path) throws DataError {
        Map<String, Object> o = object(v, path, BUTTON_KEYS);
        String id = id(o, "id", join(path, "id"));
        return new Button(id, text(o, "label", join(path, "label"), 1, 20, true));
    }

    static Stat stat(Object v) throws DataError {
        Map<String, Object> o = object(v, "$", STAT_KEYS);
        Stat d = new Stat();
        d.title = text(o, "title", "title", 1, 40, true);
        d.value = text(o, "value", "value", 1, 16, true);
        d.unit = text(o, "unit", "unit", 1, 12, false);
        d.caption = text(o, "caption", "caption", 1, 60, false);
        if (o.containsKey("progress")) {
            Object p = o.get("progress");
            if (!(p instanceof Double)) throw new DataError("progress", "not a number");
            double n = (Double) p;
            if (!(n >= 0 && n <= 1)) throw new DataError("progress", "out of range");
            d.progress = n;
        }
        if (o.containsKey("trend")) {
            String t = text(o, "trend", "trend", 1, 16, true);
            if (!t.equals("up") && !t.equals("down") && !t.equals("flat")) throw new DataError("trend", "bad value");
            d.trend = t;
        }
        d.tint = color(o, "tint", "tint");
        d.link = link(o, "link", "link");
        List<Object> acts = array(o, "actions", "actions", 2, false);
        if (acts != null) {
            Set<String> seen = new HashSet<>();
            d.actions = new ArrayList<>();
            for (int k = 0; k < acts.size(); k++) {
                Button b = button(acts.get(k), "actions[" + k + "]");
                if (!seen.add(b.id)) throw new DataError("actions[" + k + "].id", "duplicate id");
                d.actions.add(b);
            }
        }
        return d;
    }

    static ListData list(Object v) throws DataError {
        Map<String, Object> o = object(v, "$", LIST_KEYS);
        ListData d = new ListData();
        d.title = text(o, "title", "title", 1, 40, true);
        List<Object> raw = array(o, "items", "items", 8, true);
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < raw.size(); k++) {
            String p = "items[" + k + "]";
            Map<String, Object> it = object(raw.get(k), p, ITEM_KEYS);
            Item item = new Item();
            item.id = id(it, "id", p + ".id");
            item.title = text(it, "title", p + ".title", 1, 60, true);
            if (!seen.add(item.id)) throw new DataError(p + ".id", "duplicate id");
            item.subtitle = text(it, "subtitle", p + ".subtitle", 1, 60, false);
            if (it.containsKey("checked")) {
                Object c = it.get("checked");
                if (!(c instanceof Boolean)) throw new DataError(p + ".checked", "not a bool");
                item.checked = (Boolean) c;
            }
            item.link = link(it, "link", p + ".link");
            d.items.add(item);
        }
        d.empty = text(o, "empty", "empty", 1, 60, false);
        d.tint = color(o, "tint", "tint");
        d.link = link(o, "link", "link");
        if (o.containsKey("action")) d.action = button(o.get("action"), "action");
        return d;
    }

    /** List data with one item's checked state set; null when the item is unknown or not checkable. */
    public static ListData toggle(ListData l, String item, boolean checked) {
        ListData out = new ListData();
        out.title = l.title;
        out.empty = l.empty;
        out.tint = l.tint;
        out.link = l.link;
        out.action = l.action;
        boolean found = false;
        for (Item it : l.items) {
            Item c = it.copy();
            if (c.id.equals(item) && c.checked != null) {
                c.checked = checked;
                found = true;
            }
            out.items.add(c);
        }
        return found ? out : null;
    }

    // ------------------------------------------------------------------ deep link
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/";

    public static String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int u = x & 0xFF;
            if (u < 0x80 && UNRESERVED.indexOf(u) >= 0) b.append((char) u);
            else b.append(String.format("%%%02X", u));
        }
        return b.toString();
    }

    static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    public static String decode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int k = 0; k < s.length(); ) {
            char c = s.charAt(k);
            if (c == '%') {
                if (k + 2 >= s.length()) return null;
                int h = hex(s.charAt(k + 1));
                int l = hex(s.charAt(k + 2));
                if (h < 0 || l < 0) return null;
                out.write(h * 16 + l);
                k += 3;
            } else if (c < 0x21 || c > 0x7E) {
                return null;
            } else {
                out.write(c);
                k++;
            }
        }
        java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    public static String linkUrl(String template, String id, String link) {
        StringBuilder q = new StringBuilder("t=").append(template).append("&k=open");
        if (id != null && !id.isEmpty()) q.append("&id=").append(encode(id));
        if (link != null && !link.isEmpty()) q.append("&l=").append(encode(link));
        return SCHEME + "://tap?" + q;
    }

    /** The open tap a deep link stands for, or null. Any app can open a custom scheme: all is checked. */
    public static Tap parseLink(String url) {
        String head = SCHEME + "://tap?";
        if (url == null || url.length() > 2048 || url.length() < head.length()
                || !url.substring(0, head.length()).toLowerCase(java.util.Locale.ROOT).equals(head)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : url.substring(head.length()).split("&", -1)) {
            int eq = part.indexOf('=');
            if (eq < 0) return null;
            String k = part.substring(0, eq);
            String v = decode(part.substring(eq + 1));
            if (!(k.equals("t") || k.equals("k") || k.equals("id") || k.equals("l")) || fields.containsKey(k) || v == null) {
                return null;
            }
            fields.put(k, v);
        }
        if (!isTemplate(fields.get("t")) || !"open".equals(fields.get("k"))) return null;
        Tap t = new Tap(fields.get("t"), "open", "");
        if (fields.containsKey("id")) {
            if (!isId(fields.get("id"))) return null;
            t.id = fields.get("id");
        }
        if (fields.containsKey("l")) {
            if (!isLink(fields.get("l"))) return null;
            t.link = fields.get("l");
        }
        return t;
    }

    // ------------------------------------------------------------------ taps
    /** One widget tap: open (a deep link), action (a button) or toggle (a list item's check box). */
    public static final class Tap {
        public long seq;
        public String template;
        public String kind;
        public String id;
        public String link;
        public Boolean checked;
        public long at;

        public Tap(String template, String kind, String id) {
            this.template = template;
            this.kind = kind;
            this.id = id;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("template", template);
            m.put("kind", kind);
            m.put("id", id);
            m.put("at", at);
            put(m, "link", link);
            put(m, "checked", checked);
            return m;
        }

        public String toJson() {
            return write(json());
        }
    }

    /** Taps not yet acknowledged by the page, capped (oldest dropped and counted). */
    public static final class TapQueue {
        public long next = 1;
        public long dropped = 0;
        public final List<Tap> taps = new ArrayList<>();

        public Tap push(Tap tap) {
            tap.seq = next++;
            taps.add(tap);
            while (taps.size() > QUEUE_CAP) {
                taps.remove(0);
                dropped++;
            }
            return tap;
        }

        public void ack(long upto) {
            taps.removeIf(t -> t.seq <= upto);
        }

        public String encode() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("next", next);
            m.put("dropped", dropped);
            List<Object> a = new ArrayList<>();
            for (Tap t : taps) a.add(t.json());
            m.put("taps", a);
            return write(m);
        }

        /** The stored queue; an unreadable one starts empty. Numbers here are integers. */
        @SuppressWarnings("unchecked")
        public static TapQueue decode(String text) {
            TapQueue q = new TapQueue();
            if (text == null) return q;
            try {
                Object v = Reader.parse(text);
                if (!(v instanceof Map)) return q;
                Map<String, Object> m = (Map<String, Object>) v;
                q.next = Math.max(1, num(m.get("next")));
                q.dropped = Math.max(0, num(m.get("dropped")));
                Object list = m.get("taps");
                if (list instanceof List) {
                    for (Object x : (List<Object>) list) {
                        if (!(x instanceof Map)) continue;
                        Map<String, Object> t = (Map<String, Object>) x;
                        if (!(t.get("template") instanceof String) || !(t.get("kind") instanceof String)
                                || !(t.get("id") instanceof String) || !(t.get("seq") instanceof Double)) continue;
                        Tap tap = new Tap((String) t.get("template"), (String) t.get("kind"), (String) t.get("id"));
                        tap.seq = num(t.get("seq"));
                        tap.at = num(t.get("at"));
                        if (t.get("link") instanceof String) tap.link = (String) t.get("link");
                        if (t.get("checked") instanceof Boolean) tap.checked = (Boolean) t.get("checked");
                        if (tap.seq < q.next) q.taps.add(tap);
                    }
                }
                while (q.taps.size() > QUEUE_CAP) q.taps.remove(0);
            } catch (DataError e) {
                return new TapQueue();
            }
            return q;
        }

        private static long num(Object o) {
            return o instanceof Double ? (long) (double) (Double) o : 0;
        }
    }
}
