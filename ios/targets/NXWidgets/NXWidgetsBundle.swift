import AppIntents
import SwiftUI
import WidgetKit

// The NXWidgets widget extension: two templates the page fills through the app.
// - Stat: a number with unit, trend, progress and up to two buttons. Small, medium and the three Lock
//   Screen families.
// - List: up to eight rows with check buttons and a header button. Medium, large, extra large (iPad)
//   and the rectangular Lock Screen family.
// Buttons run App Intents here, in the widget extension: they queue a tap in the App Group, the
// widget reloads, and the running app emits the tap. A tap anywhere else opens the app on the deep
// link. System fonts, materials and SF Symbols only, so the widget follows the system look, tinted
// and accented rendering included.

// MARK: - Timeline

struct WidgetEntry: TimelineEntry {
    let date: Date
    let data: WidgetData?
}

struct WidgetsProvider: TimelineProvider {
    let template: WidgetTemplate

    func placeholder(in context: Context) -> WidgetEntry {
        WidgetEntry(date: Date(), data: Samples.data(template))
    }

    func getSnapshot(in context: Context, completion: @escaping (WidgetEntry) -> Void) {
        // The gallery shows the sample until the page has sent data.
        let stored = WidgetShared.load(template)
        completion(WidgetEntry(date: Date(), data: stored ?? (context.isPreview ? Samples.data(template) : nil)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WidgetEntry>) -> Void) {
        // The app reloads this kind on every set or clear, and a button reloads it after its intent:
        // no scheduled refresh is needed, so none spends the reload budget.
        completion(Timeline(entries: [WidgetEntry(date: Date(), data: WidgetShared.load(template))], policy: .never))
    }
}

enum Samples {
    static func data(_ t: WidgetTemplate) -> WidgetData {
        switch t {
        case .stat:
            return .stat(StatData(title: "Revenue", value: "12.4k", unit: "USD", caption: "Today", progress: 0.62,
                                  trend: "up", tint: nil, link: nil,
                                  actions: [WidgetButton(id: "refresh", label: "Refresh")]))
        case .list:
            return .list(ListData(title: "Today", items: [
                ListItem(id: "a", title: "Review the draft", subtitle: "9:30", checked: true, link: nil),
                ListItem(id: "b", title: "Call the studio", subtitle: nil, checked: false, link: nil),
                ListItem(id: "c", title: "Book the venue", subtitle: "Friday", checked: false, link: nil),
            ], empty: nil, tint: nil, link: nil, action: WidgetButton(id: "add", label: "Add")))
        }
    }
}

// MARK: - App Intents (run in the widget extension)

/// A stat button or the list's header button.
struct WidgetActionIntent: AppIntent {
    static let title: LocalizedStringResource = "Widget button"
    static let isDiscoverable = false

    @Parameter(title: "Template") var template: String
    @Parameter(title: "Button") var button: String

    init() {}

    init(template: WidgetTemplate, button: String) {
        self.template = template.rawValue
        self.button = button
    }

    func perform() async throws -> some IntentResult {
        // Only what the stored data declares is recorded; the intent's parameters are not trusted.
        guard let t = WidgetTemplate(rawValue: template), declared(t, button) else { return .result() }
        if WidgetShared.record(WidgetTap(template: t.rawValue, kind: "action", id: button)) != nil {
            WidgetShared.postTap()
        }
        return .result()
    }

    private func declared(_ t: WidgetTemplate, _ id: String) -> Bool {
        switch WidgetShared.load(t) {
        case .stat(let s)?: return s.actions?.contains { $0.id == id } ?? false
        case .list(let l)?: return l.action?.id == id
        default: return false
        }
    }
}

/// A list item's check button: sets the stored state, so the widget shows it at once, and queues a
/// toggle tap for the page.
struct WidgetToggleIntent: AppIntent {
    static let title: LocalizedStringResource = "Check item"
    static let isDiscoverable = false

    @Parameter(title: "Item") var item: String
    @Parameter(title: "Checked") var checked: Bool

    init() {}

    init(item: String, checked: Bool) {
        self.item = item
        self.checked = checked
    }

    func perform() async throws -> some IntentResult {
        guard WidgetShared.toggle(item: item, checked: checked) else { return .result() }
        var tap = WidgetTap(template: WidgetTemplate.list.rawValue, kind: "toggle", id: item)
        tap.checked = checked
        if WidgetShared.record(tap) != nil { WidgetShared.postTap() }
        return .result()
    }
}

// MARK: - Shared view parts

extension Color {
    /// "#RRGGBB" from the data, else the app's accent color.
    static func widgetTint(_ hex: String?) -> Color {
        guard let hex = hex, hex.count == 7, let v = UInt32(hex.dropFirst(), radix: 16) else { return .accentColor }
        return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

func deepLink(_ t: WidgetTemplate, id: String = "", link: String?) -> URL? {
    URL(string: WidgetLink.url(template: t, id: id, link: link))
}

struct EmptyState: View {
    let template: WidgetTemplate

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: template == .stat ? "chart.bar" : "checklist")
                .font(.title3)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Text("No data yet").font(.headline)
            Text("Open the app to fill this widget.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

// MARK: - Stat

struct StatView: View {
    @Environment(\.widgetFamily) private var family
    let data: StatData

    private var trendSymbol: String? {
        switch data.trend {
        case "up": return "arrow.up.right"
        case "down": return "arrow.down.right"
        case "flat": return "arrow.right"
        default: return nil
        }
    }

    private var trendColor: Color {
        switch data.trend {
        case "up": return .green
        case "down": return .red
        default: return .secondary
        }
    }

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        case .accessoryInline: inline
        case .systemMedium: medium
        default: small
        }
    }

    private var valueLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            Text(data.value)
                .font(.system(.largeTitle, design: .rounded, weight: .semibold))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .contentTransition(.numericText())
                .widgetAccentable()
            if let unit = data.unit {
                Text(unit).font(.subheadline.weight(.medium)).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 4) {
            Text(data.title).font(.subheadline.weight(.semibold)).foregroundStyle(Color.widgetTint(data.tint)).lineLimit(1)
            Spacer(minLength: 0)
            if let s = trendSymbol {
                Image(systemName: s).font(.caption.weight(.bold)).foregroundStyle(trendColor)
            }
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            header
            Spacer(minLength: 0)
            valueLine
            if let p = data.progress {
                ProgressView(value: p).tint(Color.widgetTint(data.tint))
            }
            if let c = data.caption {
                Text(c).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var medium: some View {
        HStack(spacing: 12) {
            small
            if let actions = data.actions, !actions.isEmpty {
                VStack(spacing: 8) {
                    ForEach(actions, id: \.id) { a in
                        Button(intent: WidgetActionIntent(template: .stat, button: a.id)) {
                            Text(a.label).font(.subheadline.weight(.semibold)).lineLimit(1).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(Color.widgetTint(data.tint))
                    }
                }
                .frame(maxWidth: 130)
            } else if let p = data.progress {
                Gauge(value: p) { EmptyView() } currentValueLabel: {
                    Text("\(Int((p * 100).rounded()))%").font(.caption.weight(.semibold))
                }
                .gaugeStyle(.accessoryCircularCapacity)
                .tint(Color.widgetTint(data.tint))
                .scaleEffect(1.4)
                .frame(width: 90)
            }
        }
    }

    private var circular: some View {
        Group {
            if let p = data.progress {
                Gauge(value: p) { Text(data.title) } currentValueLabel: { Text(data.value).minimumScaleFactor(0.5) }
                    .gaugeStyle(.accessoryCircularCapacity)
            } else {
                ZStack {
                    AccessoryWidgetBackground()
                    Text(data.value).font(.headline).minimumScaleFactor(0.4).padding(4)
                }
            }
        }
        .widgetAccentable()
    }

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(data.title).font(.headline).widgetAccentable().lineLimit(1)
            Text(data.unit.map { "\(data.value) \($0)" } ?? data.value).lineLimit(1)
            if let p = data.progress {
                ProgressView(value: p)
            } else if let c = data.caption {
                Text(c).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var inline: some View {
        if let s = trendSymbol {
            return Text("\(Image(systemName: s)) \(data.title) \(data.value)")
        }
        return Text("\(data.title) \(data.value)")
    }
}

struct NXStatWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetTemplate.stat.kind, provider: WidgetsProvider(template: .stat)) { entry in
            Group {
                if case .stat(let s)? = entry.data {
                    StatView(data: s).widgetURL(deepLink(.stat, link: s.link))
                } else {
                    EmptyState(template: .stat).widgetURL(deepLink(.stat, link: nil))
                }
            }
            .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Stat")
        .description("A number with its trend and progress.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

// MARK: - List

struct ListWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let data: ListData

    private var rows: Int {
        switch family {
        case .systemMedium: return 3
        case .systemLarge: return 7
        case .systemExtraLarge: return 8
        default: return 2
        }
    }

    var body: some View {
        if family == .accessoryRectangular {
            VStack(alignment: .leading, spacing: 1) {
                Text(data.title).font(.headline).widgetAccentable().lineLimit(1)
                ForEach(Array(data.items.prefix(2)), id: \.id) { item in
                    Label(item.title, systemImage: item.checked == true ? "checkmark.circle.fill" : "circle")
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(data.title).font(.headline).foregroundStyle(Color.widgetTint(data.tint)).lineLimit(1)
                    Spacer(minLength: 0)
                    if let a = data.action {
                        Button(intent: WidgetActionIntent(template: .list, button: a.id)) {
                            Label(a.label, systemImage: "plus").labelStyle(.iconOnly).font(.body.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.circle)
                        .tint(Color.widgetTint(data.tint))
                        .accessibilityLabel(a.label)
                    }
                }
                if data.items.isEmpty {
                    Spacer(minLength: 0)
                    Text(data.empty ?? "Nothing here").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                } else {
                    ForEach(Array(data.items.prefix(rows)), id: \.id) { item in
                        row(item)
                        if item.id != data.items.prefix(rows).last?.id { Divider() }
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func row(_ item: ListItem) -> some View {
        HStack(spacing: 8) {
            if let checked = item.checked {
                Button(intent: WidgetToggleIntent(item: item.id, checked: !checked)) {
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(checked ? Color.widgetTint(data.tint) : .secondary)
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(checked ? "Uncheck \(item.title)" : "Check \(item.title)")
            }
            if let url = deepLink(.list, id: item.id, link: item.link ?? data.link) {
                Link(destination: url) { rowText(item) }
            } else {
                rowText(item)
            }
        }
    }

    private func rowText(_ item: ListItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(item.title)
                .font(.subheadline)
                .strikethrough(item.checked == true)
                .foregroundStyle(item.checked == true ? .secondary : .primary)
                .lineLimit(1)
            if let s = item.subtitle {
                Text(s).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct NXListWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetTemplate.list.kind, provider: WidgetsProvider(template: .list)) { entry in
            Group {
                if case .list(let l)? = entry.data {
                    ListWidgetView(data: l).widgetURL(deepLink(.list, link: l.link))
                } else {
                    EmptyState(template: .list).widgetURL(deepLink(.list, link: nil))
                }
            }
            .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("List")
        .description("A short list with check buttons.")
        .supportedFamilies([.systemMedium, .systemLarge, .systemExtraLarge, .accessoryRectangular])
    }
}

@main
struct NXWidgetsBundle: WidgetBundle {
    var body: some Widget {
        NXStatWidget()
        NXListWidget()
    }
}
