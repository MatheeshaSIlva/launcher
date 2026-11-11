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
cutout's view-local centre only when strictly between 20% and 80% of `status-height`;
otherwise it is 52% of that height. Boxes use their geometric middle; text and rows retain
their centre-line semantics. Coordinates are converted using the host's resting screen
origin, excluding translations and scales. Geometry changes place the root again and
retarget a declared `transition="place ..."`; reduced motion lands immediately.

This component anchor is separate from surface summon zones, which come in E3.
Other refusal codes are `place-root-only`, `not-bindable` and `wrong-type`.
