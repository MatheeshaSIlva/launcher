# KDL motion: the E2 subset

Motion is fixed at component load; bindings, expressions and ordinary tokens are refused.
Roles are resolved by the active motion preset, including its speed and bounce adjustments;
`@motion.*` is a motion role, never a prop or colour token. Role existence is checked by the
runtime resolver, not the grammar.

```kdl
box transition="place size @motion.move, opacity 120ms" spring="stiff"
box transition="value 200ms ease-out"
```

`transition` takes comma-separated clauses: one or more channels followed by an optional
curve. Channels are `place` (including translate-x/y), `size`, `opacity`, `scale`;
`value` means all four E2 channels. A channel may occur only once, including through `value`.
An omitted channel jumps. `spring` alone does not animate undeclared channels.

For a declared channel, precedence is its clause's explicit curve, then the node's `spring`,
then `@motion.move` (`@motion.state` for opacity). Despite its name, `spring` is the node's
default curve and may refer to any motion role.

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
`motion-bad-curve`. Unknown channels and easings suggest a nearby spelling.
Declarations are bounded to 4096 characters. Plain single-line quoted values have exact
inside-value spans; raw, escaped or multiline values conservatively use the value span.

Later slices add enter/exit presence (13a-2) and rolling text (13a-3); their existing schema
is unchanged here. Keyframes, stagger, progress=, rotation, per-property durations outside
clauses, stiffness/damping/mass and duration/bounce spring forms are not yet supported:
`motion-not-supported`. This subset does not replace the spec's later surface tracks.
