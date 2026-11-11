# KDL sources: device

Declare `uses { source device }` to read `device` in expressions or use cutout placement.
Its capability is `none`: no permission prompt. The source gives current display geometry
first, then changes, without consecutive repeats; cancelling collection removes its listener.

| Member | Value |
| --- | --- |
| `width`, `height` | Display logical dimensions, px |
| `density` | Logical density, unitless |
| `rotation` | 0, 90, 180 or 270 degrees, unitless |
| `status-height` | Status bar height ignoring visibility, px |
| `safe-left`, `safe-top`, `safe-right`, `safe-bottom` | Display cutout safe insets, px; zero when there is no display cutout |
| `cutout` | Boolean: a top-edge cutout exists, regardless of placement eligibility |
| `cutout-rect-left`, `cutout-rect-top`, `cutout-rect-right`, `cutout-rect-bottom` | Top-edge cutout bounding rectangle in screen px, or null |
| `cutout-centre-x`, `cutout-centre-y` | That rectangle's centre in screen px, or null |
| `model` | Public `Build.MODEL` string; no device identifiers |

Unknown members are null. **Fold state and dpi are later**, not implemented members.
Safe insets describe all display cutouts; a side cutout may have nonzero safe insets while
`cutout` is false and the top-edge rectangle members are null.

The Android adapter follows the attached view's display. Dimensions use that display's
maximum window metrics on API 30+, real size on API 29; the cutout uses the same display
logical space. Status height ignores bar visibility (stable inset on API 29).
Display changes, configuration and window inset changes refresh the adapter; display
removal clears the top cutout until reattachment. These Android duties belong to the host;
`DeviceSource` accepts an injected `DisplayInfo` and has no Android dependency.
