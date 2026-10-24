# Panels

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A panel is a file of its own that a swipe on home can open. On this phone build the app drawer is one: `drawer.owl`,
which swiping up shows above the prompt bar, as search and every other way to the drawer do; edit it, or read it to
see how a drawer is put together. Settings › gestures can set a swipe to "owl panel" instead: swiping up then shows
the panel picked in the drawer's place, and swiping down brings it from the top. The launcher ships one more,
`controls.owl`, for swiping down: the phone's switches, the volume, what's playing, the next alarm and the
notifications. Which panel is picked in settings › gestures, or in style.toml as the panel's file name (see
`defaults.toml`); a name counts only while its swipe is set to "owl panel", which only settings › gestures sets:

```toml
[gestures]
swipe-up = "my-drawer.owl"     # drawer.owl by default
swipe-down = "controls.owl"    # "none" nothing
```

The drawer is `drawer.owl`, else `.stock-drawer.owl`: the drawer as this launcher ships it, which it keeps that way.
So a drawer that's removed or stops compiling never locks anyone out of their apps (with "owl panel" for swiping up,
the panel picked comes first; a panel picked for swiping down that's broken opens nothing). Home's banner says which,
and settings list it with "(has errors)". Compare `drawer.owl` with `.stock-drawer.owl` to see what changed in the
stock drawer since.

The phone build's drawer reads three more names, as it reads `query`: `picked`, the keys of the apps picked to act
on together, in the order picked, null while none are (`appAction("select", a.key)` picks or unpicks one,
`launcher("cancel")` stops, and closing the drawer does too); `showHidden`, whether hidden apps are listed
(`launcher("hidden")`); and `dragging`, the key of the app being carried onto home with `dragApp`, null while none is,
so its long-press menu can give way.

Behind a panel the launcher draws the theme's background, as opaque as the panel's `opacity` says: from 0, home shows
through, to 1, solid; without one 0.97, so home barely shows (owl 13). `controls.owl` has 0.97. Leave
a panel's outer node without a `background` of its own, or it covers home whatever `opacity` is; a background on the
boxes inside it is fine.

On this phone build the drawer's backdrop follows style.toml's `drawer.opacity` (Style › drawer › background)
instead of `drawer.owl`'s `opacity`, so the drawer's stays the user's to set without editing the file.

```owl
panel "my drawer" {
  meta { owl: 11 }
  source d = android.drawer
  let shown = query == "" ? d.apps : rank(d.apps, query)
  // Enter in the prompt bar: open the best match when it names an app well, as the bar does; a sentence asks the agent.
  onSubmit: {
    if len(shown) > 0 && strongMatch(shown[0], value) {
      open(shown[0].key)
    } else if trim(value) != "" {
      ask(value)
    }
  }
  Grid {
    lazy: true
    weight: 1
    columns: 5
    for a in shown key a.key {
      Column { align: center; onTap: open(a.key); Image { src: a.icon; size: 52 }; Text { text: a.name; style: small } }
    }
  }
}
```

The launcher keeps what's around a panel: the backdrop, the panel following the finger as it opens, closing it by
pulling down past the top of its list (pushing up past the end, for one from the top), a tap on its empty space or
Back. The drawer sits above the prompt bar, which stays with its microphone and agent menu: `query` is the bar's text
as it's typed, and `onSubmit` runs on Enter with it as `value`; without `onSubmit`, Enter asks the agent. Only a
panel opened by swiping up has the bar: in one from the top, `query` stays "" and `onSubmit`, `enter` and `onKey` never run. `enter` is what the bar shows it does, like
`enter: opens ? "▸ open" : "⏎ ask"` ("" shows nothing). With a keyboard attached, the bar's Up, Down, Tab and
Shift+Tab go to `onKey` with `value` set to `up`, `down`, `tab` or `shift-tab`, so a panel can move its own
highlight (drawer.owl keeps a `pick`). Up and Down come only while what's typed is one line; in more, they move
between its lines. Without `onKey`, the keys stay with the bar and do what they do in any text field. `open(...)` and `appAction(...)` leave the
panel as opening an app from the drawer does, and `close()` slides it away. Besides `enter` and
`opacity`, a panel has no properties, title or icon; like an app's, its nodes are the screen's. Give a long list `lazy: true`.
