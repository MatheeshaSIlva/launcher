| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | regions over tolerance |
|---|---|---|---|---|---|---|---|
| quick | light | 195 / 288 | 6.939 | 4.83 | 4.91 % | col 1, row 12, box 90,1200,90,100, mean 46.201 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| quick | dark | 288 / 288 | 12.003 | 5.84 | 4.91 % | col 3, row 6, box 270,600,90,100, mean 49.845 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| expanded | light | 266 / 281 | 5.783 | 2.55 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| expanded | dark | 273 / 281 | 10.8 | 8.24 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | light | 268 / 281 | 5.789 | 2.53 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | dark | 273 / 281 | 10.827 | 8.23 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | light | 266 / 281 | 5.783 | 2.55 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 72.732 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | dark | 273 / 281 | 10.8 | 8.24 | 3.01 % | col 1, row 1, box 90,100,90,100, mean 73.466 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |

### quick, light

Report: light/quick/report.json; worst cells: light/quick/worst.png

Tone: 68 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.018 to 6.0).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,984,1000,392 | 61064 | 16.45 | backgroundNormal | none |
| 40,1416,1000,120 | 16286 | 8.55 | dismiss_text 'Clear all' | footer |
| 544,8,496,224 | 9400 | 13.97 | tile_expandable containing 'Bluetooth.', 'Bluetooth' | header, tile-2-bluetooth |
| 224,328,208,80 | 5434 | 42.59 | tile_expandable containing 'Mobile data', 'No service' | tile-3-mobile-data |
| 40,16,376,40 | 5074 | 57.68 | shade_header_root containing '10:00', 'Mon, Nov 2' | header |
| 232,728,336,32 | 5046 | 64.37 | text 'One test notification' | first-notification-card |
| 224,120,176,72 | 3306 | 34.13 | tile_expandable containing 'Wi-Fi', 'AndroidWifi' | tile-1-wi-fi |
| 392,2360,296,16 | 2828 | 85.52 | (nothing in stock's tree) | none |
| 232,592,224,32 | 2398 | 45.58 | notification_top_line containing 'Launcher', '\u2022', ... | first-notification-card |
| 80,584,112,112 | 2316 | 26.02 | icon | first-notification-card |
| 80,896,104,48 | 1738 | 37.12 | header_label 'Silent' | none |
| 736,352,120,32 | 1370 | 40.76 | 'Modes' | tile-4-modes |
| 112,808,856,8 | 850 | 4.96 | expandableNotificationRow | first-notification-card |
| 96,128,80,64 | 574 | 11.53 | qs_tile_icon | tile-1-wi-fi |

5 more not listed (the 14 largest are shown).

### quick, dark

Report: dark/quick/report.json; worst cells: dark/quick/worst.png

Tone: 130 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.76 to 11.15).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,984,1000,392 | 62003 | 17.49 | backgroundNormal | none |
| 40,1416,1000,120 | 16534 | 11.04 | dismiss_text 'Clear all' | footer |
| 488,8,552,240 | 9611 | 13.97 | tile_expandable containing 'Bluetooth.', 'Bluetooth' | header, tile-1-wi-fi, tile-2-bluetooth |
| 696,1608,208,80 | 8002 | 12.38 | (nothing in stock's tree) | none |
| 704,2184,136,96 | 7038 | 12.52 | (nothing in stock's tree) | none |
| 232,2200,136,96 | 6266 | 12.42 | (nothing in stock's tree) | none |
| 224,328,208,80 | 5578 | 49.03 | tile_expandable containing 'Mobile data', 'No service' | tile-3-mobile-data |
| 40,16,376,40 | 5202 | 61.64 | shade_header_root containing '10:00', 'Mon, Nov 2' | header |
| 232,728,336,32 | 5144 | 72.97 | text 'One test notification' | first-notification-card |
| 224,120,176,72 | 3386 | 38.35 | tile_expandable containing 'Wi-Fi', 'AndroidWifi' | tile-1-wi-fi |
| 392,2360,296,16 | 2828 | 128.27 | (nothing in stock's tree) | none |
| 480,288,376,176 | 2700 | 6.53 | tile_expandable containing 'Modes.', 'Modes' | tile-3-mobile-data, tile-4-modes |
| 232,592,224,32 | 2517 | 51.27 | notification_top_line containing 'Launcher', '\u2022', ... | first-notification-card |
| 80,584,112,112 | 2216 | 15.42 | icon | first-notification-card |

9 more not listed (the 14 largest are shown).

### expanded, light

Report: light/expanded/report.json; worst cells: light/expanded/worst.png

Tone: 145 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 24680 | 5.97 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,112,248,80 | 7431 | 70.42 | '10:00' | header |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,416,40 | 3972 | 31.68 | (nothing in stock's tree) | none |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 40,264,192,40 | 2629 | 54.45 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |
| 848,128,184,40 | 2388 | 49.89 | mobile_carrier_text 'No service' | header |
| 680,2208,360,112 | 2378 | 5.82 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |

7 more not listed (the 14 largest are shown).

### expanded, dark

Report: dark/expanded/report.json; worst cells: dark/expanded/worst.png

Tone: 92 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 60586 | 8.9 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 680,2168,360,152 | 9315 | 9.72 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,112,248,80 | 7485 | 70.45 | '10:00' | header |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,416,40 | 4455 | 44.49 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 40,264,192,40 | 2621 | 57.66 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

10 more not listed (the 14 largest are shown).

### tile-off, light

Report: light/tile-off/report.json; worst cells: light/tile-off/worst.png

Tone: 145 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 24271 | 6.0 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,112,248,80 | 7431 | 70.42 | '10:00' | header |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,416,40 | 3972 | 31.68 | (nothing in stock's tree) | none |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 40,264,192,40 | 2629 | 54.45 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |
| 848,128,184,40 | 2388 | 49.89 | mobile_carrier_text 'No service' | header |
| 680,2208,360,112 | 2378 | 5.82 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |

8 more not listed (the 14 largest are shown).

### tile-off, dark

Report: dark/tile-off/report.json; worst cells: dark/tile-off/worst.png

Tone: 92 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 60149 | 9.01 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 680,2168,360,152 | 9315 | 9.72 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,112,248,80 | 7485 | 70.45 | '10:00' | header |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,416,40 | 4455 | 44.49 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 40,264,192,40 | 2621 | 57.66 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

10 more not listed (the 14 largest are shown).

### tile-on, light

Report: light/tile-on/report.json; worst cells: light/tile-on/worst.png

Tone: 145 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 24680 | 5.97 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,112,248,80 | 7431 | 70.42 | '10:00' | header |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,416,40 | 3972 | 31.68 | (nothing in stock's tree) | none |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 40,264,192,40 | 2629 | 54.45 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |
| 848,128,184,40 | 2388 | 49.89 | mobile_carrier_text 'No service' | header |
| 680,2208,360,112 | 2378 | 5.82 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |

7 more not listed (the 14 largest are shown).

### tile-on, dark

Report: dark/tile-on/report.json; worst cells: dark/tile-on/worst.png

Tone: 92 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 60586 | 8.9 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 680,2168,360,152 | 9315 | 9.72 | qs_footer_actions containing 'Signed in as Owner', 'Open settings.', ... | footer |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,112,248,80 | 7485 | 70.45 | '10:00' | header |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,416,40 | 4455 | 44.49 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 40,264,192,40 | 2621 | 57.66 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

10 more not listed (the 14 largest are shown).
