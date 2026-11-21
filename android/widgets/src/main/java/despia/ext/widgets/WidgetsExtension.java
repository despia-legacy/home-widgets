package despia.ext.widgets;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home-screen widgets the page fills, built with Jetpack Glance (WidgetUi.kt): routes that set, read
 * and clear each template's data and update its widgets, list the placed widgets, ask the launcher to
 * pin one, and read the widget taps. Taps (a deep link, a button, a check box) are stored first and
 * then emitted as the "tap" event, so a page that loads later still reads them with "taps".
 */
public final class WidgetsExtension implements NativeExtension {
    private ExtContext ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        WidgetStore.setListener(tap -> main.post(() -> emit(tap)));
        WidgetUpdater.publishPreviews(context.app());
    }

    private void emit(WidgetModel.Tap tap) {
        try {
            ctx.emit("tap", new JSONObject(tap.toJson()));
        } catch (JSONException e) {
            ctx.log("tap not emitted: " + e.getMessage());
        }
    }

    /** nxwidgets://tap?... from a widget (claimed in manifest.json). Any app can send this scheme, so
     * WidgetModel.parseLink checks every part; a link that does not parse is dropped. */
    @Override
    public boolean onIntent(Activity activity, Intent intent) {
        WidgetModel.Tap tap = WidgetModel.parseLink(intent.getDataString());
        if (tap == null) {
            ctx.log("dropped a widget link that does not parse");
        } else {
            WidgetStore.record(ctx.app(), tap);
        }
        return true;
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        Context app = ctx.app();
        Object t = call.params.get("template");
        String template = t instanceof String && WidgetModel.isTemplate((String) t) ? (String) t : null;
        try {
            switch (call.route) {
                case "set":
                    reply.reply(set(app, template, (String) call.params.get("data")));
                    return;
                case "get":
                    reply.reply(get(app, template));
                    return;
                case "clear":
                    if (template == null) {
                        reply.reply(ExtResult.error("bad_request", "template"));
                        return;
                    }
                    if (!WidgetStore.store(app, template, null)) {
                        reply.reply(ExtResult.error("storage_failed", "the widget data was not removed"));
                        return;
                    }
                    WidgetUpdater.update(app, template);
                    reply.reply(ExtResult.ok(new JSONObject().put("template", template).put("cleared", true)));
                    return;
                case "reload": {
                    JSONArray done = new JSONArray();
                    for (String x : template == null ? new String[]{WidgetModel.STAT, WidgetModel.LIST} : new String[]{template}) {
                        WidgetUpdater.update(app, x);
                        done.put(x);
                    }
                    reply.reply(ExtResult.ok(new JSONObject().put("reloaded", done)));
                    return;
                }
                case "installed":
                    reply.reply(ExtResult.ok(new JSONObject().put("widgets", installed(app))));
                    return;
                case "taps":
                    reply.reply(taps(app, call.params.get("ack")));
                    return;
                case "pin":
                    pin(app, template, call, reply);
                    return;
                default:
                    reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", "reply is not JSON"));
        }
    }

    private ExtResult set(Context app, String template, String text) throws JSONException {
        if (template == null) return ExtResult.error("bad_request", "template");
        Object data;
        try {
            data = WidgetModel.validate(template, text == null ? "" : text);
        } catch (WidgetModel.DataError e) {
            return ExtResult.error("bad_data", e.getMessage());
        }
        if (!WidgetStore.store(app, template, WidgetModel.canonical(data))) {
            return ExtResult.error("storage_failed", "the widget data was not saved");
        }
        WidgetUpdater.update(app, template);
        return ExtResult.ok(new JSONObject().put("template", template).put("stored", true));
    }

    private ExtResult get(Context app, String template) throws JSONException {
        if (template == null) return ExtResult.error("bad_request", "template");
        Object d = WidgetStore.load(app, template);
        Object data = d == null ? JSONObject.NULL : new JSONObject(WidgetModel.canonical(d));
        return ExtResult.ok(new JSONObject().put("template", template).put("data", data));
    }

    /** Each placed widget of this sample with its portrait size as the launcher reports it, in dp
     * (minimum width and maximum height, the AppWidgetManager convention). */
    private JSONArray installed(Context app) throws JSONException {
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        JSONArray out = new JSONArray();
        String[][] receivers = {{WidgetModel.STAT, StatWidgetReceiver.class.getName()},
                {WidgetModel.LIST, ListWidgetReceiver.class.getName()}};
        for (String[] r : receivers) {
            for (int id : m.getAppWidgetIds(new ComponentName(app, r[1]))) {
                Bundle o = m.getAppWidgetOptions(id);
                out.put(new JSONObject().put("template", r[0])
                        .put("width", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
                        .put("height", o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)));
            }
        }
        return out;
    }

    private ExtResult taps(Context app, Object ack) throws JSONException {
        // A typed int (0 to 2^53-1), checked by the bridge; absent means acknowledge nothing.
        long upto = ack instanceof Number ? ((Number) ack).longValue() : 0;
        WidgetModel.TapQueue q = upto > 0 ? WidgetStore.ack(app, upto) : WidgetStore.taps(app);
        if (q == null) return ExtResult.error("storage_failed", "the tap queue was not updated");
        JSONArray list = new JSONArray();
        for (WidgetModel.Tap tap : q.taps) list.put(new JSONObject(tap.toJson()));
        return ExtResult.ok(new JSONObject().put("taps", list).put("dropped", q.dropped).put("next", q.next));
    }

    /** Asks the launcher to place the widget. The answer says the request was shown, not that the
     * user placed it: read "installed" afterwards. */
    private void pin(Context app, String template, ExtCall call, ExtResult.Callback reply) {
        if (template == null) {
            reply.reply(ExtResult.error("bad_request", "template"));
            return;
        }
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        if (android.os.Build.VERSION.SDK_INT < 26 || !m.isRequestPinAppWidgetSupported()) {
            reply.reply(ExtResult.error("pin_unavailable", "this launcher does not let apps place widgets"));
            return;
        }
        WidgetUpdater.pin(app, template, ok -> main.post(() -> {
            if (!call.isActive()) return;
            try {
                reply.reply(ok ? ExtResult.ok(new JSONObject().put("template", template).put("requested", true))
                        : ExtResult.error("pin_unavailable", "the launcher refused the request"));
            } catch (JSONException e) {
                reply.reply(ExtResult.error("handler_error", "reply is not JSON"));
            }
        }));
    }
}
