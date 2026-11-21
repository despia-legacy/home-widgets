package despia.ext.widgets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared storage of the widgets sample on Android: one SharedPreferences file, "nx_widgets", read by
 * the Glance widgets and written by the routes and the widget callbacks. Everything runs in the app's
 * main process (Glance renders and runs its callbacks there), so one lock orders every
 * read-modify-write and commit() makes each write durable before it returns.
 *
 * <p>Values are WidgetModel's canonical text; a stored value is validated again when it is read, so a
 * value that does not validate shows the empty state, never half a widget.
 */
public final class WidgetStore {
    private WidgetStore() {
    }

    static final String PREFS = "nx_widgets";
    private static final String TAPS = "taps";
    private static final Object LOCK = new Object();
    private static volatile Listener listener;

    /** Told about every tap after it is stored (the extension emits it to the page). */
    public interface Listener {
        void onTap(WidgetModel.Tap tap);
    }

    /** A yes or no answer from asynchronous work (pin). */
    public interface Done {
        void done(boolean ok);
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String dataKey(String template) {
        return "data." + template;
    }

    /** Stat, ListData or null. */
    public static Object load(Context c, String template) {
        String text;
        synchronized (LOCK) {
            text = prefs(c).getString(dataKey(template), null);
        }
        if (text == null) return null;
        try {
            return WidgetModel.validate(template, text);
        } catch (WidgetModel.DataError e) {
            return null;
        }
    }

    public static WidgetModel.Stat stat(Context c) {
        Object d = load(c, WidgetModel.STAT);
        return d instanceof WidgetModel.Stat ? (WidgetModel.Stat) d : null;
    }

    public static WidgetModel.ListData list(Context c) {
        Object d = load(c, WidgetModel.LIST);
        return d instanceof WidgetModel.ListData ? (WidgetModel.ListData) d : null;
    }

    /** The canonical text of validated data, or null to remove it. False when the write failed. */
    static boolean store(Context c, String template, String canonical) {
        synchronized (LOCK) {
            SharedPreferences.Editor e = prefs(c).edit();
            if (canonical == null) e.remove(dataKey(template));
            else e.putString(dataKey(template), canonical);
            return e.commit();
        }
    }

    static String text(Context c, String template) {
        synchronized (LOCK) {
            return prefs(c).getString(dataKey(template), null);
        }
    }

    public static WidgetModel.TapQueue taps(Context c) {
        synchronized (LOCK) {
            return WidgetModel.TapQueue.decode(prefs(c).getString(TAPS, null));
        }
    }

    /** The queue after acknowledging every tap up to {@code upto}, or null when the write failed. */
    static WidgetModel.TapQueue ack(Context c, long upto) {
        synchronized (LOCK) {
            WidgetModel.TapQueue q = WidgetModel.TapQueue.decode(prefs(c).getString(TAPS, null));
            q.ack(upto);
            return prefs(c).edit().putString(TAPS, q.encode()).commit() ? q : null;
        }
    }

    /** Queues a tap and tells the listener; returns it with its sequence number, or null. */
    public static WidgetModel.Tap record(Context c, WidgetModel.Tap tap) {
        WidgetModel.Tap stored;
        synchronized (LOCK) {
            WidgetModel.TapQueue q = WidgetModel.TapQueue.decode(prefs(c).getString(TAPS, null));
            tap.at = System.currentTimeMillis();
            stored = q.push(tap);
            if (!prefs(c).edit().putString(TAPS, q.encode()).commit()) return null;
        }
        Listener l = listener;
        if (l != null) l.onTap(stored);
        return stored;
    }

    /** A stat button or the list's header button, recorded only when the stored data declares it. */
    public static boolean recordAction(Context c, String template, String button) {
        Object d = load(c, template);
        boolean declared = false;
        if (d instanceof WidgetModel.Stat && ((WidgetModel.Stat) d).actions != null) {
            for (WidgetModel.Button b : ((WidgetModel.Stat) d).actions) declared |= b.id.equals(button);
        } else if (d instanceof WidgetModel.ListData && ((WidgetModel.ListData) d).action != null) {
            declared = ((WidgetModel.ListData) d).action.id.equals(button);
        }
        return declared && record(c, new WidgetModel.Tap(template, "action", button)) != null;
    }

    /** A list item's check box: sets the stored state, then queues the toggle tap. */
    public static boolean toggle(Context c, String item, boolean checked) {
        synchronized (LOCK) {
            String text = prefs(c).getString(dataKey(WidgetModel.LIST), null);
            if (text == null) return false;
            WidgetModel.ListData next;
            try {
                next = WidgetModel.toggle((WidgetModel.ListData) WidgetModel.validate(WidgetModel.LIST, text), item, checked);
            } catch (WidgetModel.DataError e) {
                return false;
            }
            if (next == null || !prefs(c).edit().putString(dataKey(WidgetModel.LIST), WidgetModel.canonical(next)).commit()) {
                return false;
            }
        }
        WidgetModel.Tap tap = new WidgetModel.Tap(WidgetModel.LIST, "toggle", item);
        tap.checked = checked;
        return record(c, tap) != null;
    }
}
