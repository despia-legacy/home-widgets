package despia.ext.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.setWidgetPreviews
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// The two Glance widgets of the sample. Stat: a number with unit, trend, progress and up to two
// buttons. List: up to eight rows with check boxes and a header button. Both are resizable and lay
// out by size (SizeMode.Responsive); both use GlanceTheme, so they take the wallpaper's dynamic
// Material colors on Android 12 and later and the Material 3 baseline before. Data comes from
// WidgetStore; taps go back through WidgetStore to the page.

private val KeyTemplate = ActionParameters.Key<String>("nx.template")
private val KeyButton = ActionParameters.Key<String>("nx.button")
private val KeyItem = ActionParameters.Key<String>("nx.item")

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 250.dp)

/** The page's "#RRGGBB" tint, else the theme's primary color. */
@Composable
private fun tint(hex: String?): ColorProvider {
    if (hex == null || hex.length != 7) return GlanceTheme.colors.primary
    val v = hex.substring(1).toLongOrNull(16) ?: return GlanceTheme.colors.primary
    return ColorProvider(Color(0xFF000000L or v))
}

/** Opens the app on the widget deep link, which the extension reads back as an "open" tap. */
private fun openAction(context: Context, template: String, id: String, link: String?): Action {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WidgetModel.linkUrl(template, id, link)))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return actionStartActivity(intent)
}

private fun buttonAction(template: String, button: String): Action =
    actionRunCallback<ButtonCallback>(actionParametersOf(KeyTemplate to template, KeyButton to button))

@Composable
private fun EmptyContent(context: Context, template: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(vertical = 12.dp).clickable(openAction(context, template, "", null)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(context.getString(R.string.nx_widgets_empty_title),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        Text(context.getString(R.string.nx_widgets_empty_body),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
    }
}

// ---------------------------------------------------------------- stat

@Composable
private fun StatContent(context: Context, data: WidgetModel.Stat?) {
    Scaffold {
        if (data == null) EmptyContent(context, WidgetModel.STAT) else StatBody(context, data)
    }
}

@Composable
private fun StatBody(context: Context, data: WidgetModel.Stat) {
    val size = LocalSize.current
    val accent = tint(data.tint)
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(vertical = 12.dp)
            .clickable(openAction(context, WidgetModel.STAT, "", data.link)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.title, maxLines = 1,
                    style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                trendText(data.trend)?.let {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(it.first, style = TextStyle(color = it.second, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(verticalAlignment = Alignment.Bottom) {
                Text(data.value, maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (size.width < 150.dp) 28.sp else 34.sp,
                        fontWeight = FontWeight.Bold))
                data.unit?.let {
                    Spacer(GlanceModifier.width(4.dp))
                    Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                }
            }
            data.progress?.let {
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator(progress = it.toFloat(), modifier = GlanceModifier.fillMaxWidth(),
                    color = accent, backgroundColor = GlanceTheme.colors.surfaceVariant)
            }
            data.caption?.let {
                Spacer(GlanceModifier.height(4.dp))
                Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
        val actions = data.actions
        if (actions != null && actions.isNotEmpty() && size.width >= WIDE.width) {
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.width(120.dp)) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) Spacer(GlanceModifier.height(8.dp))
                    FilledButton(text = a.label, onClick = buttonAction(WidgetModel.STAT, a.id),
                        modifier = GlanceModifier.fillMaxWidth(), maxLines = 1,
                        colors = ButtonDefaults.buttonColors(backgroundColor = accent, contentColor = GlanceTheme.colors.onPrimary))
                }
            }
        }
    }
}

@Composable
private fun trendText(trend: String?): Pair<String, ColorProvider>? = when (trend) {
    "up" -> "↗" to ColorProvider(Color(0xFF34A853))
    "down" -> "↘" to GlanceTheme.colors.error
    "flat" -> "→" to GlanceTheme.colors.onSurfaceVariant
    else -> null
}

class StatWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetStore.stat(context)
        provideContent { GlanceTheme { StatContent(context, data) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { StatContent(context, Samples.stat()) } }
    }
}

class StatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatWidget()
}

// ---------------------------------------------------------------- list

@Composable
private fun ListContent(context: Context, data: WidgetModel.ListData?) {
    Scaffold {
        if (data == null) EmptyContent(context, WidgetModel.LIST) else ListBody(context, data)
    }
}

@Composable
private fun ListBody(context: Context, data: WidgetModel.ListData) {
    val accent = tint(data.tint)
    Column(modifier = GlanceModifier.fillMaxSize().padding(top = 12.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(openAction(context, WidgetModel.LIST, "", data.link)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(data.title, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            data.action?.let { a ->
                FilledButton(text = a.label, onClick = buttonAction(WidgetModel.LIST, a.id), maxLines = 1,
                    colors = ButtonDefaults.buttonColors(backgroundColor = accent, contentColor = GlanceTheme.colors.onPrimary))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (data.items.isEmpty()) {
            Text(data.empty ?: context.getString(R.string.nx_widgets_list_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(data.items, itemId = { it.id.hashCode().toLong() }) { item -> ItemRow(context, data, item) }
            }
        }
    }
}

@Composable
private fun ItemRow(context: Context, data: WidgetModel.ListData, item: WidgetModel.Item) {
    val checked = item.checked
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) {
            CheckBox(checked = checked,
                onCheckedChange = actionRunCallback<ToggleCallback>(actionParametersOf(KeyItem to item.id)))
        }
        Column(modifier = GlanceModifier.defaultWeight().clickable(openAction(context, WidgetModel.LIST, item.id, item.link ?: data.link))) {
            Text(item.title, maxLines = 1, style = TextStyle(
                color = if (checked == true) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                fontSize = 14.sp, textDecoration = if (checked == true) TextDecoration.LineThrough else TextDecoration.None))
            item.subtitle?.let {
                Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }
}

class ListWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetStore.list(context)
        provideContent { GlanceTheme { ListContent(context, data) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { ListContent(context, Samples.list()) } }
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}

// ---------------------------------------------------------------- taps

/** A stat button or the list's header button. Recorded only when the stored data declares it. */
class ButtonCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val template = parameters[KeyTemplate] ?: return
        val button = parameters[KeyButton] ?: return
        if (WidgetModel.isTemplate(template)) WidgetStore.recordAction(context, template, button)
    }
}

/** A list item's check box: sets the stored state, queues the toggle tap, redraws the list. */
class ToggleCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val item = parameters[KeyItem] ?: return
        val checked = parameters[ToggleableStateKey] ?: return
        WidgetStore.toggle(context, item, checked)
        ListWidget().updateAll(context)
    }
}

// ---------------------------------------------------------------- called from Java

object Samples {
    @JvmStatic
    fun stat(): WidgetModel.Stat = WidgetModel.validate(WidgetModel.STAT,
        """{"title":"Revenue","value":"12.4k","unit":"USD","caption":"Today","progress":0.62,"trend":"up","actions":[{"id":"refresh","label":"Refresh"}]}""") as WidgetModel.Stat

    @JvmStatic
    fun list(): WidgetModel.ListData = WidgetModel.validate(WidgetModel.LIST,
        """{"title":"Today","items":[{"id":"a","title":"Review the draft","subtitle":"9:30","checked":true},{"id":"b","title":"Call the studio","checked":false},{"id":"c","title":"Book the venue","subtitle":"Friday","checked":false}],"action":{"id":"add","label":"Add"}}""") as WidgetModel.ListData
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val PREVIEWS_KEY = "previews.version"

    /** Redraws every placed widget of a template from the stored data. */
    @JvmStatic
    fun update(context: Context, template: String) {
        val app = context.applicationContext
        scope.launch {
            when (template) {
                WidgetModel.STAT -> StatWidget().updateAll(app)
                WidgetModel.LIST -> ListWidget().updateAll(app)
            }
        }
    }

    /** Asks the launcher to place the template's widget; `done` gets whether the request was shown. */
    @JvmStatic
    fun pin(context: Context, template: String, done: WidgetStore.Done) {
        val app = context.applicationContext
        scope.launch {
            val sent = runCatching {
                val m = GlanceAppWidgetManager(app)
                if (template == WidgetModel.STAT) m.requestPinGlanceAppWidget(StatWidgetReceiver::class.java, preview = StatWidget())
                else m.requestPinGlanceAppWidget(ListWidgetReceiver::class.java, preview = ListWidget())
            }.getOrDefault(false)
            done.done(sent)
        }
    }

    /** Android 15 and later show generated previews in the widget picker. Published once per app
     * version: the system rate-limits the call. */
    @JvmStatic
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val app = context.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
        val prefs = app.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(PREVIEWS_KEY, -1L) == version) return
        scope.launch {
            runCatching {
                val m = GlanceAppWidgetManager(app)
                val a = m.setWidgetPreviews<StatWidgetReceiver>()
                val b = m.setWidgetPreviews<ListWidgetReceiver>()
                if (a == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS &&
                    b == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
                    prefs.edit().putLong(PREVIEWS_KEY, version).apply()
                }
            }
        }
    }
}
