import Foundation

// Shared by the app (the extension's routes) and the NXWidgets widget extension. Compiled into both,
// so it uses nothing newer than the app's own deployment target.
//
// What it holds:
// - the two templates a page fills (stat, list), their typed data and the one validator for it;
// - a strict JSON reader and the canonical writer (the same text on iOS and Android);
// - the widget deep link (built by the widget, read again by the app as untrusted input);
// - the tap queue and the App Group storage both processes share.
//
// Customise here: the deep-link scheme (also in manifest.json, claims.url_schemes), the limits, and
// new fields of a template (add them to the reader, the writer and the widget view together).

enum WidgetTemplate: String, CaseIterable {
    case stat, list

    /// The WidgetKit kind of the template's widget; `reload` uses it.
    var kind: String { self == .stat ? "NXStatWidget" : "NXListWidget" }
}

// MARK: - JSON

/// A parsed JSON value. Numbers are Doubles.
indirect enum WJSON: Equatable {
    case object([String: WJSON], [String])   // values, and keys in document order
    case array([WJSON])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
}

struct WidgetDataError: Error, Equatable {
    let path: String
    let reason: String
    var message: String { "\(path): \(reason)" }
}

enum WidgetLimits {
    static let maxBytes = 4096
    static let maxDepth = 8
    static let queueCap = 32
    static let maxLink = 512
}

/// RFC 8259 reader over Unicode scalars. Refuses duplicate keys, lone surrogates, raw control
/// characters in strings, nesting deeper than eight containers and trailing text.
struct WidgetJSONReader {
    private let s: [Unicode.Scalar]
    private var i = 0
    private static let bad = WidgetDataError(path: "$", reason: "bad json")

    init(_ text: String) { s = Array(text.unicodeScalars) }

    static func parse(_ text: String) throws -> WJSON {
        var r = WidgetJSONReader(text)
        let v = try r.value(depth: 1)
        r.skipSpace()
        if r.i != r.s.count { throw bad }
        return v
    }

    private func peek() -> Unicode.Scalar? { i < s.count ? s[i] : nil }

    private mutating func skipSpace() {
        while let c = peek(), c == " " || c == "\t" || c == "\n" || c == "\r" { i += 1 }
    }

    private mutating func literal(_ word: String) -> Bool {
        let w = Array(word.unicodeScalars)
        guard i + w.count <= s.count, Array(s[i..<(i + w.count)]) == w else { return false }
        i += w.count
        return true
    }

    private mutating func value(depth: Int) throws -> WJSON {
        skipSpace()
        guard let c = peek() else { throw Self.bad }
        if (c == "{" || c == "[") && depth > WidgetLimits.maxDepth { throw Self.bad }
        switch c {
        case "{":
            i += 1
            var out: [String: WJSON] = [:]
            var order: [String] = []
            skipSpace()
            if peek() == "}" { i += 1; return .object(out, order) }
            while true {
                skipSpace()
                guard peek() == "\"" else { throw Self.bad }
                let k = try string()
                skipSpace()
                guard peek() == ":" else { throw Self.bad }
                i += 1
                let v = try value(depth: depth + 1)
                if out[k] != nil { throw Self.bad }
                out[k] = v
                order.append(k)
                skipSpace()
                let e = peek()
                i += 1
                if e == "}" { return .object(out, order) }
                if e != "," { throw Self.bad }
            }
        case "[":
            i += 1
            var out: [WJSON] = []
            skipSpace()
            if peek() == "]" { i += 1; return .array(out) }
            while true {
                out.append(try value(depth: depth + 1))
                skipSpace()
                let e = peek()
                i += 1
                if e == "]" { return .array(out) }
                if e != "," { throw Self.bad }
            }
        case "\"":
            return .string(try string())
        default:
            if literal("true") { return .bool(true) }
            if literal("false") { return .bool(false) }
            if literal("null") { return .null }
            return .number(try number())
        }
    }

    private func isDigit(_ c: Unicode.Scalar?) -> Bool {
        guard let c = c else { return false }
        return c >= "0" && c <= "9"
    }

    private mutating func number() throws -> Double {
        let start = i
        if peek() == "-" { i += 1 }
        if peek() == "0" {
            i += 1
        } else if isDigit(peek()) {
            while isDigit(peek()) { i += 1 }
        } else {
            throw Self.bad
        }
        if peek() == "." {
            i += 1
            guard isDigit(peek()) else { throw Self.bad }
            while isDigit(peek()) { i += 1 }
        }
        if peek() == "e" || peek() == "E" {
            i += 1
            if peek() == "+" || peek() == "-" { i += 1 }
            guard isDigit(peek()) else { throw Self.bad }
            while isDigit(peek()) { i += 1 }
        }
        var text = ""
        text.unicodeScalars.append(contentsOf: s[start..<i])
        // Double("1e999") is +inf; the schema then answers "out of range".
        guard let d = Double(text) else { throw Self.bad }
        return d
    }

    private mutating func hex4() throws -> UInt32 {
        guard i + 4 <= s.count else { throw Self.bad }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let c = s[i]
            let d: UInt32
            switch c {
            case "0"..."9": d = c.value - 48
            case "a"..."f": d = c.value - 87
            case "A"..."F": d = c.value - 55
            default: throw Self.bad
            }
            v = v * 16 + d
            i += 1
        }
        return v
    }

    private mutating func string() throws -> String {
        i += 1
        var out = String.UnicodeScalarView()
        while true {
            guard let c = peek(), c.value >= 0x20 else { throw Self.bad }
            i += 1
            if c == "\"" { return String(out) }
            if c != "\\" { out.append(c); continue }
            guard let e = peek() else { throw Self.bad }
            i += 1
            switch e {
            case "\"": out.append("\"")
            case "\\": out.append("\\")
            case "/": out.append("/")
            case "b": out.append(Unicode.Scalar(0x08))
            case "f": out.append(Unicode.Scalar(0x0C))
            case "n": out.append("\n")
            case "r": out.append("\r")
            case "t": out.append("\t")
            case "u":
                var u = try hex4()
                if (0xD800...0xDBFF).contains(u) {
                    guard i + 2 <= s.count, s[i] == "\\", s[i + 1] == "u" else { throw Self.bad }
                    i += 2
                    let lo = try hex4()
                    guard (0xDC00...0xDFFF).contains(lo) else { throw Self.bad }
                    u = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00)
                } else if (0xDC00...0xDFFF).contains(u) {
                    throw Self.bad
                }
                guard let scalar = Unicode.Scalar(u) else { throw Self.bad }
                out.append(scalar)
            default:
                throw Self.bad
            }
        }
    }
}

/// The canonical text: keys sorted, no whitespace, minimal escapes, progress to four places.
enum WidgetJSONWriter {
    static func write(_ v: WJSON) -> String {
        switch v {
        case .object(let o, _):
            return "{" + o.keys.sorted().map { escape($0) + ":" + write(o[$0]!) }.joined(separator: ",") + "}"
        case .array(let a):
            return "[" + a.map(write).joined(separator: ",") + "]"
        case .string(let s): return escape(s)
        case .number(let d): return progressText(d)
        case .bool(let b): return b ? "true" : "false"
        case .null: return "null"
        }
    }

    static func progressText(_ d: Double) -> String {
        let n = Int((d * 10000 + 0.5).rounded(.down))
        if n >= 10000 { return "1" }
        if n <= 0 { return "0" }
        var t = String(n)
        while t.count < 4 { t = "0" + t }
        while t.hasSuffix("0") { t.removeLast() }
        return "0." + t
    }

    static func escape(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            default:
                if c.value < 0x20 {
                    let h = String(c.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - h.count) + h
                } else {
                    out.unicodeScalars.append(c)
                }
            }
        }
        return out + "\""
    }
}

// MARK: - Typed template data

struct WidgetButton: Equatable {
    var id: String
    var label: String
}

struct StatData: Equatable {
    var title: String
    var value: String
    var unit: String?
    var caption: String?
    var progress: Double?
    var trend: String?
    var tint: String?
    var link: String?
    var actions: [WidgetButton]?
}

struct ListItem: Equatable {
    var id: String
    var title: String
    var subtitle: String?
    var checked: Bool?
    var link: String?
}

struct ListData: Equatable {
    var title: String
    var items: [ListItem]
    var empty: String?
    var tint: String?
    var link: String?
    var action: WidgetButton?
}

enum WidgetData: Equatable {
    case stat(StatData)
    case list(ListData)
}

/// The one validator. The first error in a fixed order is reported: an object's unknown keys first
/// (the smallest), then its declared fields in declared order; arrays item by item.
enum WidgetValidator {
    static let statKeys = ["title", "value", "unit", "caption", "progress", "trend", "tint", "link", "actions"]
    static let listKeys = ["title", "items", "empty", "tint", "link", "action"]
    static let itemKeys = ["id", "title", "subtitle", "checked", "link"]

    static func validate(template: String, text: String) throws -> WidgetData {
        guard let t = WidgetTemplate(rawValue: template) else { throw WidgetDataError(path: "$", reason: "bad template") }
        if text.utf8.count > WidgetLimits.maxBytes { throw WidgetDataError(path: "$", reason: "too large") }
        let v = try WidgetJSONReader.parse(text)
        return t == .stat ? .stat(try stat(v)) : .list(try list(v))
    }

    /// Canonical text of validated data.
    static func canonical(_ d: WidgetData) -> String { WidgetJSONWriter.write(json(d)) }

    static func join(_ path: String, _ key: String) -> String { path == "$" ? key : "\(path).\(key)" }

    static func object(_ v: WJSON, _ path: String, _ allowed: [String]) throws -> [String: WJSON] {
        guard case .object(let o, _) = v else { throw WidgetDataError(path: path, reason: "not an object") }
        if let k = o.keys.filter({ !allowed.contains($0) }).sorted().first {
            throw WidgetDataError(path: join(path, k), reason: "unknown key")
        }
        return o
    }

    static func text(_ o: [String: WJSON], _ key: String, _ path: String, _ lo: Int, _ hi: Int, required: Bool) throws -> String? {
        guard let v = o[key] else {
            if required { throw WidgetDataError(path: path, reason: "missing") }
            return nil
        }
        guard case .string(let s) = v else { throw WidgetDataError(path: path, reason: "not a string") }
        if s.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }) {
            throw WidgetDataError(path: path, reason: "control character")
        }
        let n = s.unicodeScalars.count
        if n < lo { throw WidgetDataError(path: path, reason: "empty") }
        if n > hi { throw WidgetDataError(path: path, reason: "too long") }
        return s
    }

    static func isID(_ s: String) -> Bool {
        let n = s.unicodeScalars.count
        return n >= 1 && n <= 32 && s.unicodeScalars.allSatisfy {
            ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9") || $0 == "_" || $0 == "-"
        }
    }

    static func id(_ o: [String: WJSON], _ key: String, _ path: String) throws -> String {
        let s = try text(o, key, path, 1, 32, required: true)!
        if !isID(s) { throw WidgetDataError(path: path, reason: "bad id") }
        return s
    }

    /// A path inside the page's own app: starts with one "/", printable ASCII, no backslash.
    static func isLink(_ s: String) -> Bool {
        let u = Array(s.unicodeScalars)
        guard u.count >= 1, u.count <= WidgetLimits.maxLink, u[0] == "/" else { return false }
        if u.count > 1 && u[1] == "/" { return false }
        return u.allSatisfy { $0.value >= 0x21 && $0.value <= 0x7E && $0 != "\\" }
    }

    static func link(_ o: [String: WJSON], _ key: String, _ path: String) throws -> String? {
        guard let s = try text(o, key, path, 1, WidgetLimits.maxLink, required: false) else { return nil }
        if !isLink(s) { throw WidgetDataError(path: path, reason: "bad link") }
        return s
    }

    static func color(_ o: [String: WJSON], _ key: String, _ path: String) throws -> String? {
        guard let s = try text(o, key, path, 1, 7, required: false) else { return nil }
        let u = Array(s.unicodeScalars)
        let ok = u.count == 7 && u[0] == "#" && u[1...].allSatisfy {
            ($0 >= "0" && $0 <= "9") || ($0 >= "a" && $0 <= "f") || ($0 >= "A" && $0 <= "F")
        }
        if !ok { throw WidgetDataError(path: path, reason: "bad color") }
        return s
    }

    static func array(_ o: [String: WJSON], _ key: String, _ path: String, _ hi: Int, required: Bool) throws -> [WJSON]? {
        guard let v = o[key] else {
            if required { throw WidgetDataError(path: path, reason: "missing") }
            return nil
        }
        guard case .array(let a) = v else { throw WidgetDataError(path: path, reason: "not an array") }
        if a.count > hi { throw WidgetDataError(path: path, reason: "too many") }
        return a
    }

    static func button(_ v: WJSON, _ path: String) throws -> WidgetButton {
        let o = try object(v, path, ["id", "label"])
        return WidgetButton(id: try id(o, "id", join(path, "id")),
                            label: try text(o, "label", join(path, "label"), 1, 20, required: true)!)
    }

    static func stat(_ v: WJSON) throws -> StatData {
        let o = try object(v, "$", statKeys)
        var d = StatData(title: try text(o, "title", "title", 1, 40, required: true)!,
                         value: try text(o, "value", "value", 1, 16, required: true)!)
        d.unit = try text(o, "unit", "unit", 1, 12, required: false)
        d.caption = try text(o, "caption", "caption", 1, 60, required: false)
        if let p = o["progress"] {
            guard case .number(let n) = p else { throw WidgetDataError(path: "progress", reason: "not a number") }
            guard n >= 0 && n <= 1 else { throw WidgetDataError(path: "progress", reason: "out of range") }
            d.progress = n
        }
        if o["trend"] != nil {
            let t = try text(o, "trend", "trend", 1, 16, required: true)!
            guard ["up", "down", "flat"].contains(t) else { throw WidgetDataError(path: "trend", reason: "bad value") }
            d.trend = t
        }
        d.tint = try color(o, "tint", "tint")
        d.link = try link(o, "link", "link")
        if let acts = try array(o, "actions", "actions", 2, required: false) {
            var seen = Set<String>()
            d.actions = []
            for (i, a) in acts.enumerated() {
                let b = try button(a, "actions[\(i)]")
                if seen.contains(b.id) { throw WidgetDataError(path: "actions[\(i)].id", reason: "duplicate id") }
                seen.insert(b.id)
                d.actions?.append(b)
            }
        }
        return d
    }

    static func list(_ v: WJSON) throws -> ListData {
        let o = try object(v, "$", listKeys)
        let title = try text(o, "title", "title", 1, 40, required: true)!
        let raw = try array(o, "items", "items", 8, required: true)!
        var seen = Set<String>()
        var items: [ListItem] = []
        for (i, x) in raw.enumerated() {
            let p = "items[\(i)]"
            let it = try object(x, p, itemKeys)
            var item = ListItem(id: try id(it, "id", "\(p).id"), title: try text(it, "title", "\(p).title", 1, 60, required: true)!)
            if seen.contains(item.id) { throw WidgetDataError(path: "\(p).id", reason: "duplicate id") }
            seen.insert(item.id)
            item.subtitle = try text(it, "subtitle", "\(p).subtitle", 1, 60, required: false)
            if let c = it["checked"] {
                guard case .bool(let b) = c else { throw WidgetDataError(path: "\(p).checked", reason: "not a bool") }
                item.checked = b
            }
            item.link = try link(it, "link", "\(p).link")
            items.append(item)
        }
        var d = ListData(title: title, items: items)
        d.empty = try text(o, "empty", "empty", 1, 60, required: false)
        d.tint = try color(o, "tint", "tint")
        d.link = try link(o, "link", "link")
        if let a = o["action"] { d.action = try button(a, "action") }
        return d
    }

    // Typed data back to JSON (for the canonical text and `get`).
    static func json(_ d: WidgetData) -> WJSON {
        func obj(_ pairs: [(String, WJSON?)]) -> WJSON {
            var o: [String: WJSON] = [:]
            for (k, v) in pairs { if let v = v { o[k] = v } }
            return .object(o, o.keys.sorted())
        }
        func btn(_ b: WidgetButton) -> WJSON { obj([("id", .string(b.id)), ("label", .string(b.label))]) }
        func str(_ s: String?) -> WJSON? { s.map { .string($0) } }
        switch d {
        case .stat(let s):
            return obj([("title", .string(s.title)), ("value", .string(s.value)), ("unit", str(s.unit)),
                        ("caption", str(s.caption)), ("progress", s.progress.map { .number($0) }), ("trend", str(s.trend)),
                        ("tint", str(s.tint)), ("link", str(s.link)), ("actions", s.actions.map { .array($0.map(btn)) })])
        case .list(let l):
            let items: [WJSON] = l.items.map {
                obj([("id", .string($0.id)), ("title", .string($0.title)), ("subtitle", str($0.subtitle)),
                     ("checked", $0.checked.map { .bool($0) }), ("link", str($0.link))])
            }
            return obj([("title", .string(l.title)), ("items", .array(items)), ("empty", str(l.empty)),
                        ("tint", str(l.tint)), ("link", str(l.link)), ("action", l.action.map(btn))])
        }
    }

    /// List data with one item's checked state set; nil when the item is unknown or not checkable.
    static func toggle(_ l: ListData, item: String, checked: Bool) -> ListData? {
        guard let i = l.items.firstIndex(where: { $0.id == item }), l.items[i].checked != nil else { return nil }
        var out = l
        out.items[i].checked = checked
        return out
    }
}

// MARK: - Deep link

/// The URL a widget opens. The scheme is the one manifest.json claims (claims.url_schemes); rename it
/// in both places. Any app can open a custom scheme, so the app reads every part again.
enum WidgetLink {
    static let scheme = "nxwidgets"
    private static let unreserved = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/".unicodeScalars)

    static func encode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            let c = Unicode.Scalar(b)
            if b < 0x80 && unreserved.contains(c) {
                out.unicodeScalars.append(c)
            } else {
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    static func decode(_ s: String) -> String? {
        var bytes: [UInt8] = []
        let u = Array(s.unicodeScalars)
        var i = 0
        func hex(_ c: Unicode.Scalar) -> UInt8? {
            switch c {
            case "0"..."9": return UInt8(c.value - 48)
            case "a"..."f": return UInt8(c.value - 87)
            case "A"..."F": return UInt8(c.value - 55)
            default: return nil
            }
        }
        while i < u.count {
            let c = u[i]
            if c == "%" {
                guard i + 2 < u.count, let h = hex(u[i + 1]), let l = hex(u[i + 2]) else { return nil }
                bytes.append(h * 16 + l)
                i += 3
            } else if c.value < 0x21 || c.value > 0x7E {
                return nil
            } else {
                bytes.append(UInt8(c.value))
                i += 1
            }
        }
        return String(bytes: bytes, encoding: .utf8)
    }

    static func url(template: WidgetTemplate, id: String, link: String?) -> String {
        var q = "t=\(template.rawValue)&k=open"
        if !id.isEmpty { q += "&id=" + encode(id) }
        if let link = link, !link.isEmpty { q += "&l=" + encode(link) }
        return "\(scheme)://tap?\(q)"
    }

    /// The open tap a deep link stands for, or nil.
    static func parse(_ url: String) -> WidgetTap? {
        let head = "\(scheme)://tap?"
        guard url.unicodeScalars.count <= 2048, url.unicodeScalars.count >= head.unicodeScalars.count,
              String(String.UnicodeScalarView(url.unicodeScalars.prefix(head.unicodeScalars.count))).lowercased() == head else { return nil }
        let query = String(String.UnicodeScalarView(url.unicodeScalars.dropFirst(head.unicodeScalars.count)))
        var fields: [String: String] = [:]
        for part in query.split(separator: "&", omittingEmptySubsequences: false) {
            guard let eq = part.firstIndex(of: "=") else { return nil }
            let k = String(part[..<eq])
            let raw = String(part[part.index(after: eq)...])
            guard ["t", "k", "id", "l"].contains(k), fields[k] == nil, let v = decode(raw) else { return nil }
            fields[k] = v
        }
        guard let t = fields["t"], WidgetTemplate(rawValue: t) != nil, fields["k"] == "open" else { return nil }
        var tap = WidgetTap(template: t, kind: "open", id: "")
        if let id = fields["id"] {
            guard WidgetValidator.isID(id) else { return nil }
            tap.id = id
        }
        if let l = fields["l"] {
            guard WidgetValidator.isLink(l) else { return nil }
            tap.link = l
        }
        return tap
    }
}

// MARK: - Taps

/// One widget tap: "open" (a deep link), "action" (a stat button or the list's header button) or
/// "toggle" (a list item's check button).
struct WidgetTap: Equatable {
    var seq: Int = 0
    var template: String
    var kind: String
    var id: String
    var link: String?
    var checked: Bool?
    var at: Int64 = 0

    /// The event and route form.
    var dictionary: [String: Any] {
        var d: [String: Any] = ["seq": seq, "template": template, "kind": kind, "id": id, "at": at]
        if let l = link { d["link"] = l }
        if let c = checked { d["checked"] = c }
        return d
    }
}

/// Taps not yet acknowledged by the page, capped (oldest dropped and counted).
struct WidgetTapQueue: Equatable {
    var next = 1
    var dropped = 0
    var taps: [WidgetTap] = []

    mutating func push(_ tap: WidgetTap) -> WidgetTap {
        var t = tap
        t.seq = next
        next += 1
        taps.append(t)
        while taps.count > WidgetLimits.queueCap {
            taps.removeFirst()
            dropped += 1
        }
        return t
    }

    mutating func ack(_ upto: Int) {
        taps.removeAll { $0.seq <= upto }
    }

    // Stored as JSON in the App Group. Integers only, so they never meet the progress formatter.
    func encoded() -> Data {
        let list = taps.map { t -> [String: Any] in t.dictionary }
        let obj: [String: Any] = ["next": next, "dropped": dropped, "taps": list]
        return (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
    }

    static func decoded(_ data: Data?) -> WidgetTapQueue {
        guard let data = data, let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return WidgetTapQueue() }
        var q = WidgetTapQueue()
        q.next = max(1, obj["next"] as? Int ?? 1)
        q.dropped = max(0, obj["dropped"] as? Int ?? 0)
        for t in obj["taps"] as? [[String: Any]] ?? [] {
            guard let seq = t["seq"] as? Int, let tpl = t["template"] as? String, let kind = t["kind"] as? String,
                  let id = t["id"] as? String else { continue }
            q.taps.append(WidgetTap(seq: seq, template: tpl, kind: kind, id: id, link: t["link"] as? String,
                                    checked: t["checked"] as? Bool, at: (t["at"] as? NSNumber)?.int64Value ?? 0))
        }
        q.taps = Array(q.taps.filter { $0.seq < q.next }.suffix(WidgetLimits.queueCap))
        return q
    }
}

// MARK: - Shared storage (App Group)

/// Files in the App Group container the build provisions for the logical name "widgets"
/// (group.<bundle id>.nx.widgets, published to both bundles as DESPIA_NX_APP_GROUPS). Every read-modify-write
/// goes through NSFileCoordinator, so the app and the widget extension never lose each other's write.
enum WidgetShared {
    static let logicalGroup = "widgets"

    static func groupIdentifier() -> String? {
        (Bundle.main.object(forInfoDictionaryKey: "DESPIA_NX_APP_GROUPS") as? [String: String])?[logicalGroup]
    }

    static func directory() -> URL? {
        guard let id = groupIdentifier(),
              let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) else { return nil }
        let dir = base.appendingPathComponent("nxwidgets", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// The Darwin notification the widget posts after it queued a tap; the running app drains the queue.
    static var tapNotification: String { (groupIdentifier() ?? "nxwidgets") + ".tap" }

    static func dataURL(_ t: WidgetTemplate) -> URL? { directory()?.appendingPathComponent("\(t.rawValue).json") }
    static func tapsURL() -> URL? { directory()?.appendingPathComponent("taps.json") }

    /// Coordinated read-modify-write. `body` gets the current bytes (nil when absent) and returns the
    /// new bytes (nil to delete) and a result. False when the group or the file cannot be used.
    static func update<R>(_ url: URL, _ body: (Data?) -> (Data?, R)) -> R? {
        var result: R?
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: .forMerging, error: &coordError) { u in
            let current = try? Data(contentsOf: u)
            let (next, r) = body(current)
            do {
                if let next = next {
                    try next.write(to: u, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                } else if current != nil {
                    try FileManager.default.removeItem(at: u)
                }
                result = r
            } catch {
                result = nil
            }
        }
        return coordError == nil ? result : nil
    }

    static func read(_ url: URL) -> Data? {
        var out: Data?
        var coordError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordError) { u in
            out = try? Data(contentsOf: u)
        }
        return out
    }

    /// The stored, validated data of a template, or nil. Stored text is validated again on read: a
    /// file that does not validate shows the empty state, never half a widget.
    static func load(_ t: WidgetTemplate) -> WidgetData? {
        guard let url = dataURL(t), let data = read(url), let text = String(data: data, encoding: .utf8) else { return nil }
        return try? WidgetValidator.validate(template: t.rawValue, text: text)
    }

    @discardableResult
    static func store(_ t: WidgetTemplate, _ d: WidgetData?) -> Bool {
        guard let url = dataURL(t) else { return false }
        let bytes = d.map { Data(WidgetValidator.canonical($0).utf8) }
        return update(url) { _ in (bytes, true) } ?? false
    }

    /// Queues a tap; returns it with its sequence number, or nil when storage failed.
    static func record(_ tap: WidgetTap) -> WidgetTap? {
        guard let url = tapsURL() else { return nil }
        var t = tap
        t.at = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        return update(url) { cur -> (Data?, WidgetTap?) in
            var q = WidgetTapQueue.decoded(cur)
            let stored = q.push(t)
            return (q.encoded(), stored)
        } ?? nil
    }

    static func taps() -> WidgetTapQueue {
        guard let url = tapsURL() else { return WidgetTapQueue() }
        return WidgetTapQueue.decoded(read(url))
    }

    static func ack(_ upto: Int) -> WidgetTapQueue? {
        guard let url = tapsURL() else { return nil }
        return update(url) { cur -> (Data?, WidgetTapQueue) in
            var q = WidgetTapQueue.decoded(cur)
            q.ack(upto)
            return (q.encoded(), q)
        }
    }

    /// Sets one list item's checked state in the stored list (the widget's check button).
    static func toggle(item: String, checked: Bool) -> Bool {
        guard let url = dataURL(.list) else { return false }
        return update(url) { cur -> (Data?, Bool) in
            guard let cur = cur, let text = String(data: cur, encoding: .utf8),
                  case .list(let l)? = try? WidgetValidator.validate(template: "list", text: text),
                  let next = WidgetValidator.toggle(l, item: item, checked: checked) else { return (cur, false) }
            return (Data(WidgetValidator.canonical(.list(next)).utf8), true)
        } ?? false
    }

    static func postTap() {
        let name = CFNotificationName(tapNotification as CFString)
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), name, nil, nil, true)
    }
}
