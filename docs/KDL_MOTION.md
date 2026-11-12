# KDL motion: the E2 subset

Motion is fixed at component load; bindings, expressions and ordinary tokens are refused.
Roles are resolved by the active motion preset, including its speed and bounce adjustments;
`@motion.*` is a motion role, never a prop or colour token. Role existence is checked by the
runtime resolver, not the grammar.

```kdl
box transition="place size @motion.move, opacity 120ms" spring="stiff"
box transition="value 200ms ease-out"
box radius="full" transition="size radius spring(0.4, 0.8)"
box enter="fade-up 240ms" exit="fade 120ms"
text "12:34" roll=#true
text "42%" roll="@motion.text.roll" spring="stiff"
```

`transition` takes comma-separated clauses: one or more channels followed by an optional
curve. Channels are `place` (including translate-x/y), `size`, `opacity`, `scale`, `radius`;
`value` means all five E2 channels. A channel may occur only once, including through `value`.
An omitted channel jumps. `spring` alone does not animate undeclared channels.

For a declared channel, precedence is its clause's explicit curve, then the node's `spring`,
then `@motion.move` (`@motion.state` for opacity). Despite its name, `spring` is the node's
default curve and may refer to any motion role.

`radius` takes a length (a number in the default pt unit, or `(pt)`, `(dp)`, `(px)`)
or exactly `"full"`. Other words, numeric strings, booleans, null and other units
are refused with `wrong-type` at the value's source span. Other length attributes
still require lengths. Radius may bind to state, a prop/token or an expression;
a whole expression preserves either its length value or the string `full`, for example
`radius="{$pill ? 'full' : 8dp}"`.

For a box, `full` means half its currently drawn shorter side. The radius channel
uses the same precedence as size: explicit curve, node `spring`, then `@motion.move`.
With that channel declared, changes between lengths and `full` continue from the
displayed radius and speed using a residual that settles toward zero relative to
the new declaration. Without a radius curve the declaration applies immediately;
an established `full` follows drawn size on every frame. The displayed radius is
clamped to zero through half the drawn shorter side; invalid or empty geometry
has an empty shape. The box's painter, child clip and hit region share that radius.

`enter` and `exit` each take one or more effects followed by an optional curve:
`fade` (opacity), `grow` (size from zero along the parent's axis), `scale`, and `rise`
(a short offset). `fade-up` expands to `fade rise`. Effects combine, but cannot repeat,
including through `fade-up`. `scale` here is a presence effect; other channel names and
`value` belong to `transition`. Each presence declaration takes one clause; commas
are permitted only inside spring/bezier curves.

`decode` (E2.14d) is accepted only on `text`: a duration in ms (0 to 3000; 0, the default, is off) and `decode-glyphs`,
the pool scrambled characters are drawn from (default letters, digits and a few signs). When the text changes, every
character that changed scrambles through the pool, a new glyph each 50 ms, and settles on its new letter, one after
another from the start (the first at 35 % of the duration, the last at its end). Unchanged characters and spaces stay.
A change mid-way decodes from what is shown; a text leaving or out of sight lands on its new text; motion reduced
changes it at once. Its layout is the new text's throughout, so nothing around it moves. Both are literal only.

A presence clause may also take `delay(Nms)` (T-013): 0 to 1000 ms, once, literal. The node holds its start (an
arrival's `enter` values; a departure where it is) for that long, then moves on its curve. A node shown and hidden again
inside its arrival's delay never shows: a motion still waiting carries no speed. Motion reduced ignores the delay. Codes:
`motion-bad-delay`, `motion-duplicate-delay`. A bare `delay`, or a delay in `transition`, is still refused.

For presence, precedence is its own curve, then the node's `spring`, then
`@motion.enter` or `@motion.exit`. Without the corresponding declaration there is
no presence animation. Presence accepts every curve form below, including a duration
alone (ease-out). Presence declarations are literal only, like `transition`.

`roll` is accepted only on `text`: `#true` rolls with the node's `spring`, or
`@motion.text.roll` when there is no node default. A literal curve string uses its own
curve even when the node has `spring`. An explicit `roll="@motion.text.roll"` also
keeps that role. Absent `roll` or `roll=#false` means no rolling, even with `spring`.
Roll accepts every curve form below and is data only: bindings and expressions are
refused, with the same 4096-character bound. Direction words (`up`, `down`) and
per-character `stagger` inside a roll value are not yet supported. Curve role names
such as `@motion.up` and `spring(down)` remain valid names.

Curves:

- `@motion.<role>`: full preset role name.
- `200ms`: ease-out. Durations are finite numbers from 1 through 5000 ms.
- `200ms ease-out|ease-in-out|ease-in|linear`: CSS controls respectively
  `(0,0,0.58,1)`, `(0.42,0,0.58,1)`, `(0.42,0,1,1)`, `(0,0,1,1)`.
- `cubic-bezier(a,b,c,d) 220ms`: finite controls, x controls a and c in [0,1].
- `spring(stiff)`: role `motion.spring.stiff`.
- `spring(0.4, 0.8)`: finite response in (0,10] seconds and damping in (0,2].

`spring="stiff"`, `spring="motion.spring.stiff"`, and `spring="@motion.spring.stiff"`
name the same role. Other curve forms above also work as node defaults.

Bindings retain the existing `not-bindable` code. Other errors have source spans and stable `motion-*` codes: `motion-literal`, `motion-limit`,
`motion-empty-clause`, `motion-unknown-channel`, `motion-unknown-easing`,
`motion-duplicate-channel`, `motion-bad-duration`, `motion-bad-spring`, `motion-bad-bezier`,
`motion-bad-curve`, `motion-no-effect`, `motion-unknown-effect`,
`motion-duplicate-effect`, `motion-presence-channel`, `motion-presence-comma`,
`motion-roll-not-text`.
Unknown channels, effects and easings suggest a nearby spelling.
Declarations are bounded to 4096 characters. Plain single-line quoted values have exact
inside-value spans; raw, escaped or multiline values conservatively use the value span.

Presence slide and
blur, keyframes, stagger, progress=, rotation, per-property durations outside
clauses, stiffness/damping/mass and duration/bounce spring forms are not yet supported:
`motion-not-supported`. This subset does not replace the spec's later surface tracks.
