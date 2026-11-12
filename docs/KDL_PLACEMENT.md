# KDL root placement

`place` is a literal root attribute, either `"window"` (the default) or `"cutout"`.
It is accepted on the view root and fallback root only. Children and variant overrides
cannot declare it; state, prop, token and expression bindings are refused with source spans.

```kdl
component "island" {
  uses { source device }
  view { box place="cutout" width=100 height=30 }
  fallback { box place="window" width=100 height=30 }
}
```

`window` keeps the existing left edge and the host's centre line. `cutout` requires
`uses { source device }`, because placement reads display geometry even without an expression.
Without it validation reports `place-needs-device` and the required declaration.

Cutout placement centres the root horizontally on a top-edge cutout (even outside the
status band), or on the window's centre when none exists. Its centre line follows the
cutout's centre on the screen only when that lies strictly between 20% and 80% of
`status-height` (the band is the screen's); otherwise it is 52% of that height. Boxes use their geometric middle; text and rows retain
their centre-line semantics. Coordinates are converted using the host's resting screen
origin, excluding translations and scales. Geometry changes place the root again and
retarget a declared `transition="place ..."`; reduced motion lands immediately. The host
moving on screen (its layout, an ancestor's, a scroll) is no motion: the root stays on its
anchor, and a motion already running goes on. Only the view's root is placed: a fallback
root's `place` takes effect when fallback rendering exists.

`place-gap` (T-012) is a literal length (`(dp)8`, `(pt)`, `(px)` or a number in pt) on a root with
`place="cutout"`: the root's top never comes nearer the screen's top edge than that. A camera high on the screen
then leaves the root lower than centred on it (its centre below the camera's); a camera low enough changes nothing.
The gap is measured from the screen, not the host (a host lower on the screen still keeps it from the screen's top).
Without a cutout the root's place comes from the fallback line as before, with the same floor. Without `place-gap`
D-102's rule is unchanged (a root may stand partly above the screen). It is refused on a child or variant override
(`place-root-only`), on a root not placed on the camera (`place-gap-needs-cutout`) and as a binding (`not-bindable`).

```kdl
box place="cutout" place-gap=(dp)8 width=(dp)132 height=(dp)32 radius="full"
```

This component anchor is separate from surface summon zones, which come in E3.
Other refusal codes are `place-root-only`, `not-bindable` and `wrong-type`.
