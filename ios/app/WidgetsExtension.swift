import Foundation
import WidgetKit

/// Home-screen and Lock Screen widgets the page fills: the routes that set, read and clear each
/// template's data and reload its timelines, and the widget taps as the `tap` event.
///
/// Where taps come from:
/// - a deep link (widgetURL or Link in the widget) opens the app with nxwidgets://tap?...; iOS hands it
///   to `openURL` (the scheme is claimed in manifest.json);
/// - a button or check box runs an App Intent inside the widget extension, which queues the tap in the
///   App Group and posts a Darwin notification; the running app drains the queue.
/// Every tap is queued first and then emitted, so a page that loads later still reads it with `taps`.
///
/// Only this sample's own kinds are reloaded, never all timelines: the app's built-in image widget
/// and the Live Activity widget keep their own reload budget.
///
/// Main actor, as NativeExtension requires.
@MainActor
final class WidgetsExtension: NativeExtension {
    /// The instance the Darwin notification callback reaches (a C callback cannot capture).
    static weak var active: WidgetsExtension?

    private var ctx: NativeExtContext!
    private var lastEmitted = 0

    required init() {}

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        WidgetsExtension.active = self
        // Taps queued before this launch are read by the page with `taps`; events cover what follows.
        lastEmitted = WidgetShared.taps().taps.last?.seq ?? 0
        let callback: CFNotificationCallback = { _, _, _, _, _ in
            Task { @MainActor in WidgetsExtension.active?.drain() }
        }
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, callback,
                                        WidgetShared.tapNotification as CFString, nil, .deliverImmediately)
    }

    func sceneDidChange(_ phase: NativeExtScenePhase) {
        // A Darwin notification is not delivered while the app is suspended: catch up on return.
        if phase == .didBecomeActive { drain() }
    }

    func openURL(_ url: URL) -> Bool {
        // Ours by scheme. A link that does not parse is dropped here, so no other code reads it.
        guard let tap = WidgetLink.parse(url.absoluteString) else {
            ctx?.log("dropped a widget link that does not parse")
            return true
        }
        if WidgetShared.record(tap) != nil { drain() }
        return true
    }

    /// Emits every queued tap newer than the last one emitted.
    func drain() {
        guard let ctx = ctx else { return }
        for t in WidgetShared.taps().taps where t.seq > lastEmitted {
            ctx.emit("tap", t.dictionary)
            lastEmitted = t.seq
        }
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        let template = (call.params["template"] as? String).flatMap(WidgetTemplate.init(rawValue:))
        switch call.route {
        case "set": return set(template, call.params["data"] as? String ?? "")
        case "get": return get(template)
        case "clear": return clear(template)
        case "reload": return reload(template)
        case "installed": return await installed(call)
        case "taps":
            // A typed int (0 to 2^53-1), checked by the bridge; absent means acknowledge nothing.
            return taps(call.params["ack"] as? Int ?? 0)
        case "pin":
            // Apple offers no API that adds a widget for the user; they add it from the Home Screen
            // or Lock Screen widget gallery, where both templates are listed.
            return .error(code: "platform_limit", message: "iOS has no API to add a widget; add it from the widget gallery")
        default: return .error(code: "not_registered", message: call.route)
        }
    }

    private func set(_ t: WidgetTemplate?, _ text: String) -> NativeExtResult {
        guard let t = t else { return .error(code: "bad_request", message: "template") }
        let data: WidgetData
        do {
            data = try WidgetValidator.validate(template: t.rawValue, text: text)
        } catch let e as WidgetDataError {
            return .error(code: "bad_data", message: e.message)
        } catch {
            return .error(code: "bad_data", message: "$: bad json")
        }
        guard WidgetShared.directory() != nil else { return groupUnavailable() }
        guard WidgetShared.store(t, data) else { return .error(code: "storage_failed", message: "the widget data was not saved") }
        WidgetCenter.shared.reloadTimelines(ofKind: t.kind)
        return .ok(["template": t.rawValue, "stored": true])
    }

    private func get(_ t: WidgetTemplate?) -> NativeExtResult {
        guard let t = t else { return .error(code: "bad_request", message: "template") }
        guard WidgetShared.directory() != nil else { return groupUnavailable() }
        guard let d = WidgetShared.load(t),
              let obj = try? JSONSerialization.jsonObject(with: Data(WidgetValidator.canonical(d).utf8)) else {
            return .ok(["template": t.rawValue, "data": NSNull()])
        }
        return .ok(["template": t.rawValue, "data": obj])
    }

    private func clear(_ t: WidgetTemplate?) -> NativeExtResult {
        guard let t = t else { return .error(code: "bad_request", message: "template") }
        guard WidgetShared.directory() != nil else { return groupUnavailable() }
        guard WidgetShared.store(t, nil) else { return .error(code: "storage_failed", message: "the widget data was not removed") }
        WidgetCenter.shared.reloadTimelines(ofKind: t.kind)
        return .ok(["template": t.rawValue, "cleared": true])
    }

    private func reload(_ t: WidgetTemplate?) -> NativeExtResult {
        let kinds = t.map { [$0] } ?? WidgetTemplate.allCases
        for k in kinds { WidgetCenter.shared.reloadTimelines(ofKind: k.kind) }
        return .ok(["reloaded": kinds.map(\.rawValue)])
    }

    private func installed(_ call: NativeExtCall) async -> NativeExtResult {
        let configs: [WidgetInfo]? = await withCheckedContinuation { cont in
            WidgetCenter.shared.getCurrentConfigurations { result in
                cont.resume(returning: try? result.get())
            }
        }
        guard call.isActive else { return .error(code: "timeout", message: "installed timed out") }
        guard let configs = configs else { return .error(code: "unavailable", message: "WidgetKit did not list the widgets") }
        let ours = configs.compactMap { info -> [String: Any]? in
            guard let t = WidgetTemplate.allCases.first(where: { $0.kind == info.kind }) else { return nil }
            return ["template": t.rawValue, "family": Self.familyName(info.family)]
        }
        return .ok(["widgets": ours])
    }

    private func taps(_ ack: Int) -> NativeExtResult {
        guard WidgetShared.directory() != nil else { return groupUnavailable() }
        guard let q = ack > 0 ? WidgetShared.ack(ack) : WidgetShared.taps() else {
            return .error(code: "storage_failed", message: "the tap queue was not updated")
        }
        return .ok(["taps": q.taps.map(\.dictionary), "dropped": q.dropped, "next": q.next])
    }

    private func groupUnavailable() -> NativeExtResult {
        .error(code: "group_unavailable",
               message: "the App Group is missing: add it to the app and to the NXWidgets extension in the developer portal")
    }

    static func familyName(_ f: WidgetFamily) -> String {
        switch f {
        case .systemSmall: return "systemSmall"
        case .systemMedium: return "systemMedium"
        case .systemLarge: return "systemLarge"
        case .systemExtraLarge: return "systemExtraLarge"
        case .accessoryCircular: return "accessoryCircular"
        case .accessoryRectangular: return "accessoryRectangular"
        case .accessoryInline: return "accessoryInline"
        default: return "other"
        }
    }
}
