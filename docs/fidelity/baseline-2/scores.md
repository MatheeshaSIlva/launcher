| state | theme | cells flagged / total | overall mean | % pixels differing | mask share | worst cell | regions over tolerance |
|---|---|---|---|---|---|---|---|
| quick | light | 173 / 288 | 5.082 | 2.95 | 3.64 % | col 3, row 6, box 270,600,90,100, mean 42.194 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| quick | dark | 287 / 288 | 10.598 | 3.92 | 3.64 % | col 3, row 6, box 270,600,90,100, mean 47.009 | first-notification-card, footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes |
| expanded | light | 273 / 288 | 5.259 | 2.35 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| expanded | dark | 280 / 288 | 10.175 | 7.90 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | light | 275 / 288 | 5.265 | 2.34 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-off | dark | 280 / 288 | 10.201 | 7.89 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | light | 273 / 288 | 5.259 | 2.35 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 35.809 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| tile-on | dark | 280 / 288 | 10.175 | 7.90 | 0.00 % | col 2, row 1, box 180,100,90,100, mean 37.365 | footer, header, tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |

### quick, light

Report: light/quick/report.json; worst cells: light/quick/worst.png

Tone: 72 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.0 to 6.069).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,984,1000,352 | 41017 | 11.59 | backgroundNormal | footer |
| 544,8,496,224 | 8691 | 12.84 | tile_expandable containing 'Bluetooth.', 'Bluetooth' | header, tile-2-bluetooth |
| 40,16,368,40 | 5150 | 60.3 | shade_header_root containing '10:00', 'Mon, Nov 2' | header |
| 232,728,328,32 | 3779 | 45.46 | text 'One test notification' | first-notification-card |
| 392,2360,296,16 | 2828 | 85.52 | (nothing in stock's tree) | none |
| 224,376,184,32 | 2462 | 55.57 | 'No service' | tile-3-mobile-data |
| 80,584,112,112 | 2316 | 26.02 | icon | first-notification-card |
| 232,592,224,32 | 2208 | 41.57 | notification_top_line containing 'Launcher', '\u2022', ... | first-notification-card |
| 80,896,96,48 | 1742 | 38.7 | header_label 'Silent' | none |
| 736,352,112,32 | 945 | 22.14 | 'Modes' | tile-4-modes |
| 112,808,856,8 | 850 | 4.96 | expandableNotificationRow | first-notification-card |
| 96,128,80,64 | 574 | 11.53 | qs_tile_icon | tile-1-wi-fi |
| 608,336,72,64 | 368 | 7.82 | qs_tile_icon | tile-4-modes |
| 112,336,48,64 | 259 | 5.73 | qs_tile_icon | tile-3-mobile-data |

4 more not listed (the 14 largest are shown).

### quick, dark

Report: dark/quick/report.json; worst cells: dark/quick/worst.png

Tone: 155 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.76 to 11.15).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,984,1000,352 | 41768 | 14.07 | backgroundNormal | footer |
| 488,8,552,240 | 8884 | 12.91 | tile_expandable containing 'Bluetooth.', 'Bluetooth' | header, tile-1-wi-fi, tile-2-bluetooth |
| 696,1608,208,80 | 8002 | 12.38 | (nothing in stock's tree) | none |
| 704,2184,136,96 | 7038 | 12.52 | (nothing in stock's tree) | none |
| 232,2200,136,96 | 6266 | 12.42 | (nothing in stock's tree) | none |
| 40,16,368,40 | 5304 | 64.12 | shade_header_root containing '10:00', 'Mon, Nov 2' | header |
| 232,728,328,32 | 3855 | 49.89 | text 'One test notification' | first-notification-card |
| 392,2360,296,16 | 2828 | 128.27 | (nothing in stock's tree) | none |
| 224,376,184,32 | 2560 | 64.79 | 'No service' | tile-3-mobile-data |
| 232,592,224,32 | 2256 | 46.86 | notification_top_line containing 'Launcher', '\u2022', ... | first-notification-card |
| 480,288,368,176 | 2253 | 5.34 | tile_expandable containing 'Modes.', 'Modes' | tile-3-mobile-data, tile-4-modes |
| 80,584,112,112 | 2216 | 15.42 | icon | first-notification-card |
| 80,896,96,48 | 1775 | 47.51 | header_label 'Silent' | none |
| 56,784,968,32 | 934 | 6.84 | expandableNotificationRow | first-notification-card |

8 more not listed (the 14 largest are shown).

### expanded, light

Report: light/expanded/report.json; worst cells: light/expanded/worst.png

Tone: 140 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 19940 | 4.71 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,2120,1000,200 | 11044 | 7.09 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,432,40 | 4095 | 31.19 | (nothing in stock's tree) | none |
| 48,112,232,72 | 3687 | 35.71 | '10:00' | header |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 848,128,184,40 | 2694 | 59.68 | mobile_carrier_text 'No service' | header |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |
| 40,264,192,40 | 1515 | 25.36 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

6 more not listed (the 14 largest are shown).

### expanded, dark

Report: dark/expanded/report.json; worst cells: dark/expanded/worst.png

Tone: 93 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 55722 | 7.54 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 48,2168,992,152 | 17112 | 10.87 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,432,48 | 4468 | 38.22 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 48,112,232,72 | 3747 | 37.2 | '10:00' | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 848,128,184,40 | 2719 | 63.29 | mobile_carrier_text 'No service' | header |

9 more not listed (the 14 largest are shown).

### tile-off, light

Report: light/tile-off/report.json; worst cells: light/tile-off/worst.png

Tone: 140 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 19531 | 4.74 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,2120,1000,200 | 11044 | 7.09 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,432,40 | 4095 | 31.19 | (nothing in stock's tree) | none |
| 48,112,232,72 | 3687 | 35.71 | '10:00' | header |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 848,128,184,40 | 2694 | 59.68 | mobile_carrier_text 'No service' | header |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |
| 40,264,192,40 | 1515 | 25.36 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

7 more not listed (the 14 largest are shown).

### tile-off, dark

Report: dark/tile-off/report.json; worst cells: dark/tile-off/worst.png

Tone: 93 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 55285 | 7.65 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 48,2168,992,152 | 17112 | 10.87 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,432,48 | 4468 | 38.22 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 48,112,232,72 | 3747 | 37.2 | '10:00' | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 848,128,184,40 | 2719 | 63.29 | mobile_carrier_text 'No service' | header |

9 more not listed (the 14 largest are shown).

### tile-on, light

Report: light/tile-on/report.json; worst cells: light/tile-on/worst.png

Tone: 140 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 2.014 to 8.435).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 48,608,984,616 | 19940 | 4.71 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 40,2120,1000,200 | 11044 | 7.09 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 712,264,328,40 | 4241 | 51.03 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 40,1472,432,40 | 4095 | 31.19 | (nothing in stock's tree) | none |
| 48,112,232,72 | 3687 | 35.71 | '10:00' | header |
| 40,1264,360,40 | 3238 | 29.32 | '17 (CP41.260828.004.A7)' | none |
| 40,392,1000,144 | 3088 | 2.45 | brightness_slider containing 'Display brightness' | none |
| 392,2360,296,16 | 2828 | 80.12 | (nothing in stock's tree) | none |
| 848,128,184,40 | 2694 | 59.68 | mobile_carrier_text 'No service' | header |
| 944,1464,64,56 | 1881 | 75.27 | (nothing in stock's tree) | none |
| 944,1256,64,56 | 1761 | 76.16 | 'Edit order of Quick Settings.' | none |
| 648,1088,40,72 | 1717 | 26.0 | qs_tile_icon | tile-7-flashlight |
| 496,2096,80,48 | 1609 | 12.22 | (nothing in stock's tree) | none |
| 40,264,192,40 | 1515 | 25.36 | expanded_shade_header_day_date containing 'Mon, Nov 2' | header |

6 more not listed (the 14 largest are shown).

### tile-on, dark

Report: dark/tile-on/report.json; worst cells: dark/tile-on/worst.png

Tone: 93 of the flagged cells carry less than 0.5 % differing pixels (a wash of colour or brightness, mean 3.031 to 10.807).

| cluster box (x,y,w,h) | differing px | mean diff in box | stock node that names it | regions over |
|---|---|---|---|---|
| 40,584,1000,640 | 55722 | 7.54 | qs_pager containing 'Wi-Fi, AndroidWifi', 'Wi-Fi', ... | tile-1-wi-fi, tile-2-bluetooth, tile-3-mobile-data, tile-4-modes, tile-5-airplane-mode, tile-6-auto-rotate-screen, tile-7-flashlight |
| 496,1256,512,472 | 36763 | 10.41 | 'Edit order of Quick Settings.' | none |
| 536,1992,480,104 | 29014 | 12.55 | (nothing in stock's tree) | none |
| 48,2168,992,152 | 17112 | 10.87 | qs_footer_actions containing '1 app is active', 'Signed in as Owner', ... | footer |
| 680,1592,232,104 | 16811 | 14.13 | (nothing in stock's tree) | none |
| 64,2016,392,72 | 14200 | 12.2 | (nothing in stock's tree) | none |
| 0,2360,1032,40 | 13541 | 23.35 | (nothing in stock's tree) | none |
| 448,1872,208,80 | 8817 | 12.62 | (nothing in stock's tree) | none |
| 40,336,1000,232 | 6260 | 7.03 | brightness_slider containing 'Display brightness' | none |
| 40,1472,432,48 | 4468 | 38.22 | (nothing in stock's tree) | none |
| 712,264,328,40 | 4297 | 52.98 | shade_header_root containing 'Ringer vibrate.', 'Cellular, no signal', ... | header |
| 48,112,232,72 | 3747 | 37.2 | '10:00' | header |
| 40,1264,360,40 | 3300 | 38.53 | '17 (CP41.260828.004.A7)' | none |
| 848,128,184,40 | 2719 | 63.29 | mobile_carrier_text 'No service' | header |

9 more not listed (the 14 largest are shown).
