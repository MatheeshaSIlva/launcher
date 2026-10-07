#!/usr/bin/env bash
# Posts a dozen test notifications (our own text) on DEVICE, for looking at Notification Center on the emulator.
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
msgs=("Delivery|Your parcel is out for delivery." "Reminder|Water the plants" "Alex|Are we still on for lunch tomorrow? I can book the place near the station." "Maya|Running 10 minutes late, sorry!" "Weekend trip|Message 4: long enough to wrap onto a second line of the platter so the height shows." "Bank|Card payment of 12.40 at the bakery." "Weather|Rain expected from 4 PM." "Calendar|Team standup in 15 minutes" "News|Morning briefing: five stories to start your day." "Sam|Sent a photo" "Gym|Your class starts at 6" "Podcast|New episode available")
i=20
for m in "${msgs[@]}"; do
  t=${m%%|*}; x=${m#*|}
  "$ADB" -s "$DEVICE" shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --es title "'$t'" --es text "'$x'" --es open com.android.settings --ei id $i >/dev/null
  i=$((i+1)); sleep 0.3
done
