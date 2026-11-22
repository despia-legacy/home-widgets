# Home Widgets

Source preview for Despia V3. The runtime update and automatic discovery rollout are awaiting release. Rebuild and install the native app to include these files; uploading them does not change an installed app.

Native stat and list home screen widgets your page fills with JSON.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[Extension documentation](https://setup.despia.com/experiments/samples/home-widgets), Android compatibility work is in progress.

[V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `widgets.policy.json`: the build verification policy. It records declarations used by signed build verification. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- An Apple Developer account, to create an App Group and a widget App ID for iOS.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/widgets/` in your web app. The folder name is the extension id, so pages call `extension://widgets/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"widgets"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Rebuild and install the native app with these extension files. Any change to the manifest or native source requires another native build. It cannot be applied over the air.

Signed build verification binds source hashes, dependency pins, app identity and final artifacts. It is separate from human code review and does not require a per-app enrollment switch.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Call the published SDK directly and watch the exact answer variable declared in `manifest.json`. The SDK resolves to an object keyed by that variable; the native envelope is inside it. Check both a missing reply and `ok: false` before using `data`. Native errors resolve through the SDK; they do not reject its promise. A single watched variable becomes `undefined` after 30 seconds without a reply.

Await calls that use the same answer variable sequentially. The SDK's command queue does not correlate concurrent responses. Encode every query value with `encodeURIComponent`, including JSON text. Spaces become `%20`; a literal plus becomes `%2B`. Do not use unmodified `URLSearchParams` form encoding, which uses plus for spaces.

Importing the SDK is safe during server rendering. Run native calls only in browser click handlers or mounted client hooks, including client components in Next.js. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features. Optional event handlers use the declared callback on `globalThis`, not an SDK subscription API. Save and restore any callback your app already owns when a component mounts and unmounts.

## Calling it

```javascript
import despia from 'despia-native';

const answerReply = await despia('extension://widgets/installed', ['widgetsInstalled']);
const answer = answerReply.widgetsInstalled;
if (answer === undefined) throw new Error('No native reply within 30 seconds');
if (!answer.ok) throw new Error(answer.code + ': ' + answer.error);

console.log(answer.data);
```

Calls use `extension://widgets/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension adds two native home-screen widgets that your page fills with typed JSON. Each is a real WidgetKit widget on iOS and a Jetpack Glance widget on Android.

- **stat:** a title, a value with an optional unit, a caption, a trend (`up`, `down` or `flat`), a progress value from 0 to 1 (a gauge or a bar) and up to two buttons.
- **list:** a title and up to 8 items (a title, a subtitle, a check box and a deep link each), an empty text and one header button.

Both take an optional `tint` (`#RRGGBB`) and `link` (an app path such as `/stats/today`). Taps on buttons, check boxes and links come back to your page as an event.

---

## Values to set before you build

Despia takes app identity from the existing build configuration. If your app has other extensions, list `"widgets"` beside them.

---

## Add the App Group on iOS

In the Apple Developer portal:

1. **Create the App Group.** Create the App Group `group.<your bundle id>.nx.widgets`.
2. **Enable it on your app.** On the App ID `<your bundle id>`, enable **App Groups** and select that group.
3. **Create the widget App ID.** Create the App ID `<your bundle id>.NXWidgets` (the widget extension), enable **App Groups** and select the same group.
4. **Regenerate the profiles.** Regenerate the provisioning profiles of both App IDs.

The build writes the `com.apple.security.application-groups` entitlement with that group into both bundles. Without it, every route answers `group_unavailable`. Android needs no extra step and no permission.

---

## Using it from your web app

```javascript
import despia from 'despia-native';

const stat = { title: 'Revenue', value: '$12.4k', unit: 'USD', caption: 'Today', progress: 0.42, trend: 'up',
  link: '/stats/revenue', actions: [{ id: 'refresh', label: 'Refresh' }] };

const storedReply = await despia(`extension://widgets/set?template=${encodeURIComponent('stat')}&data=${encodeURIComponent(JSON.stringify(stat))}`, ['widgetsSet']);
const stored = storedReply.widgetsSet;
if (stored === undefined) throw new Error('No native reply within 30 seconds');
if (!stored.ok) throw new Error(stored.code + ': ' + stored.error);

const list = { title: 'Today', link: '/todo', action: { id: 'add', label: 'Add' },
  items: [{ id: 'a1', title: 'Buy milk', checked: false, link: '/todo/a1' }] };

const listedReply = await despia(`extension://widgets/set?template=${encodeURIComponent('list')}&data=${encodeURIComponent(JSON.stringify(list))}`, ['widgetsSet']);
const listed = listedReply.widgetsSet;
if (listed === undefined) throw new Error('No native reply within 30 seconds');
if (!listed.ok) throw new Error(listed.code + ': ' + listed.error);

const pendingReply = await despia('extension://widgets/taps', ['widgetsTaps']);
const pending = pendingReply.widgetsTaps;
if (pending === undefined) throw new Error('No native reply within 30 seconds');
if (!pending.ok) throw new Error(pending.code + ': ' + pending.error);

for (const tap of pending.data.taps) console.log(tap);
if (pending.data.taps.length) {
const acknowledgedReply = await despia(`extension://widgets/taps?ack=${encodeURIComponent(pending.data.taps.at(-1).seq)}`, ['widgetsTaps']);
const acknowledged = acknowledgedReply.widgetsTaps;
if (acknowledged === undefined) throw new Error('No native reply within 30 seconds');
if (!acknowledged.ok) throw new Error(acknowledged.code + ': ' + acknowledged.error);
}
const previousWidgetTap = globalThis.onWidgetTap;
const handleWidgetTap = tap => {
  try {
    if (typeof previousWidgetTap === 'function') previousWidgetTap(tap);
  } finally {
    console.log(tap)
  }
};
globalThis.onWidgetTap = handleWidgetTap;
const cleanupWidgetTap = () => {
  if (globalThis.onWidgetTap !== handleWidgetTap) return;
  if (previousWidgetTap === undefined) delete globalThis.onWidgetTap;
  else globalThis.onWidgetTap = previousWidgetTap;
};
// Call cleanupWidgetTap() when this component unmounts.

// Remove or restore your event callback on unmount. Re-read taps after a page reload.
```

On Android, `extension://widgets/pin?template=stat` asks the launcher to place the widget. On iOS the person adds it from the widget gallery, and `pin` answers `platform_limit`.

### Routes

| Route       | Parameters                                                                                    | Answer `data` | Answer variable | Callback |
| ----------- | --------------------------------------------------------------------------------------------- | ------------- | --- | --- |
| `set`       | `template` (`stat` or `list`, required), `data` (JSON text, required, at most 4096 UTF-8 bytes) | `template`, `stored: true`. A bad `data` answers `bad_data` with the path and the reason | `widgetsSet` | `onWidgetsSet` |
| `get`       | `template` (required)                                                                         | `template`, `data` (the canonical form, or `null` when empty) | `widgetsGet` | `onWidgetsGet` |
| `clear`     | `template` (required)                                                                         | `template`, `cleared: true` | `widgetsClear` | `onWidgetsClear` |
| `reload`    | `template` (optional)                                                                         | `reloaded`. Reloads only this extension's widgets | `widgetsReload` | `onWidgetsReload` |
| `installed` | none                                                                                          | `widgets`: on iOS each widget's family, on Android its size in dp | `widgetsInstalled` | `onWidgetsInstalled` |
| `taps`      | `ack` (optional, the last sequence number you read)                                           | `taps`, `dropped`, `next`: the queued taps after removing those up to `ack` | `widgetsTaps` | `onWidgetsTaps` |
| `pin`       | `template` (required)                                                                         | Android: asks the launcher to place the widget (`requested: true` means the request was shown), or answers `pin_unavailable` where the launcher cannot. iOS: `platform_limit` | `widgetsPin` | `onWidgetsPin` |

### The data

- **stat:** `title` (required, up to 40 characters), `value` (required, up to 16), `unit` (up to 12), `caption` (up to 60), `progress` (0 to 1), `trend`, `tint`, `link` and `actions` (up to 2 buttons, each `id` and `label` of up to 20 characters).
- **list:** `title` (required, up to 40), `items` (required, up to 8, each `id` and `title` of up to 60 characters, plus `subtitle`, `checked` and `link`), `empty` (up to 60), `tint`, `link` and `action` (one button).

The rules are strict. Duplicate keys, raw control characters, trailing text and nesting deeper than 8 levels are refused, and so is any key not listed above. Lengths count Unicode code points, and the 4096 limit counts UTF-8 bytes. A `link` is an app path that starts with one `/`. Keys are stored in sorted order, and `progress` is written to 4 decimal places.

Widget links are custom-scheme URLs (`nxwidgets://tap?...`). Any app can open a custom scheme, so the app parses every part again and drops a link that does not parse.

### The tap event

`tap` calls `onWidgetTap` (scope `document`) with `seq`, `template`, `kind`, `id` and `at`, plus `link` for an open and `checked` for a toggle. `kind` is `open` (a deep link), `action` (a button) or `toggle` (a check box). A button tap is recorded only when the stored data declares that button, and a check box tap only for an item that has `checked`.

Every tap is stored first, then emitted, so a page that loads after the tap still reads it with `taps`. The queue keeps the newest 32 taps and counts the rest in `dropped`.

### Error codes

Besides the standard codes: `bad_data`, `group_unavailable` (iOS: the App Group is not set up), `pin_unavailable` (Android) and `platform_limit` (`pin` on iOS).

---

## How each platform draws it

### iOS

- **Widget families.** stat: `systemSmall`, `systemMedium`, `accessoryCircular` (a gauge), `accessoryRectangular` and `accessoryInline`. list: `systemMedium`, `systemLarge`, `systemExtraLarge` and `accessoryRectangular`.
- **Buttons and check boxes** are App Intents that run in the widget extension. A running app emits the tap at once, and a suspended app catches up when its scene becomes active.
- **Reloads.** Timelines never refresh on their own: the app reloads its own two widgets on every `set` and `clear`, and never reloads all timelines, so other widgets keep their own reload budget.
- **Deployment target.** The widget extension needs iOS 17.0.

### Android

- **Jetpack Glance 1.2.0.** Two widgets that resize both ways, with small, medium and large layouts, built from Material 3 Glance components.
- **Pinning.** `pin` works where the launcher supports pinning (Android 8 and later).
- **Widget previews** for the picker are generated on Android 15 and later.
- **Updates.** The widgets update only on `set`, `clear`, `reload` and a tap.
- **Storage.** One private preferences file, written under one lock.
- **Exported components.** The two widget receivers (each exported only with the standard app widget update filter) and Glance's remote views service, listed in the build verification policy.

---

## Platform limits

- **iOS.** There is no API that adds a widget for the person, so they add it from the widget gallery. WidgetKit reloads on its own budget.
- **Android.** Pinning needs a launcher that supports it.
- **Both.** The data is at most 4096 UTF-8 bytes per widget, with at most 8 list items.

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE), version 1.4.
This template is proprietary and source available.

You may inspect, fork, customize, build and run it for noncommercial development,
evaluation and testing without a paid Despia license, including work on an app
you plan to release commercially later.

Production or commercial use requires a valid Despia project and an applicable
Despia license for that application and operating system. A Solo Pack covers
its licensed OS, iOS or Android. A Hybrid Pack covers both. Your applicable
license includes all provided V3 templates you have lawful access to and any
number of your own extensions, with no separate extension purchase or
per-extension fee.

Keep the license and applicable notices. Third-party material keeps its own
terms. Experimental V3 extensions do not include a commitment to maintenance
or a V4 port. See [LICENSE](LICENSE) for the full terms and [Despia](https://despia.com)
for licensing.
