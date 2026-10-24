#!/system/bin/sh
PKG=com.convx.music
OUT=/sdcard/convx_prof
mkdir -p $OUT
hb() { echo "$(date +%H:%M:%S) $*" >> $OUT/heartbeat.txt; }
END=$(( $(date +%s) + 3420 ))
CYC=0
rx() { awk '/^(wlan0|rmnet0):/ {s+=$2} END {print s+0}' /proc/net/dev; }
Q="arijit singh"
block_start() { R0=$(rx); dumpsys gfxinfo $PKG reset >/dev/null 2>&1; hb "start $1"; }
block_end() {
  NAME=$1; TOPF=$2
  top -H -b -n 1 -p $(pidof $PKG) | head -16 >> $OUT/top_${TOPF}.txt
  timeout 20 dumpsys gfxinfo $PKG > $OUT/gfx_${NAME}_$CYC.txt 2>/dev/null
  R1=$(rx)
  echo "cycle=$CYC screen=$NAME rxdelta=$((R1-R0))" >> $OUT/net.txt
  hb "end $NAME"
}
pause() { sleep $(( $RANDOM % $1 + $2 )); }
for p in $(ls /proc | grep -E '^[0-9]+$'); do c=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null); case "$c" in *logcat*${OUT}*) kill $p 2>/dev/null;; esac; done
( logcat -f $OUT/logcat2.txt -v time ) &
hb "TOUR START pid=$$"
while [ $(date +%s) -lt $END ]; do
  CYC=$((CYC+1))
  hb "=== CYCLE $CYC ==="
  { echo "=== CYCLE $CYC $(date +%H:%M:%S) ==="; dumpsys battery | grep -E '^\s+(level|temperature):'; } >> $OUT/summary.txt
  LV=$(dumpsys battery | grep -E '^\s+level:' | tr -dc 0-9)
  if [ -n "$LV" ] && [ "$LV" -lt 15 ]; then hb "LOW BATT"; break; fi

  # ---------- HOME ----------
  am start -n com.convx.music/.MainActivity >/dev/null 2>&1
  sleep 2
  block_start home
  input tap 102 2216; pause 3 2
  input swipe 540 1800 540 1200 400; pause 2 2
  input tap 268 85; pause 2 2
  input swipe 540 1800 540 1000 350
  input tap 700 85; pause 2 2
  input swipe 540 1700 540 900 300
  input tap 997 90; pause 2 2
  input swipe 540 1900 540 800 250
  block_end hometabs hometabs

  # open a playlist tile like a person browsing
  block_start tile1
  input tap 102 2216; pause 2 2
  input tap 268 668
  pause 6 3
  input swipe 540 1800 540 1100 350; pause 2 1
  input swipe 540 1100 540 1800 350; pause 2 1
  block_end tile1 tile1
  input keyevent 4; pause 2 1

  block_start tile2
  input tap 700 1375
  pause 6 3
  input swipe 540 1800 540 1200 350; pause 3 2
  block_end tile2 tile2
  input keyevent 4; pause 2 1

  # ---------- SEARCH + PLAY ----------
  block_start search
  input tap 974 2216; pause 3 2
  input tap 540 160; pause 2 1
  case $(( CYC % 3 )) in
    0) Q="weeknd";;
    1) Q="arijit singh";;
    2) Q="lofi beats";;
  esac
  hb "typing $Q"
  input text "$Q"; pause 5 2
  input swipe 540 1500 540 1100 300; pause 3 2
  input tap 540 480
  pause 10 5
  block_end search search

  # ---------- NOW PLAYING FULL WORKOUT ----------
  am start -n com.convx.music/.MainActivity >/dev/null 2>&1
  sleep 1
  input tap 537 2216
  pause 3 2
  block_start nowplaying
  input tap 541 1808; pause 2 1
  input tap 541 1808; pause 4 2
  input tap 836 1808; pause 8 4
  input tap 244 1808; pause 5 2
  input tap 839 1389; pause 2 1
  input tap 965 1389; pause 3 2
  input keyevent 4; pause 2 1
  input tap 89 2017; input tap 89 2017; pause 2 1
  input tap 985 2017; input tap 985 2017; input tap 985 2017; pause 2 1
  input tap 261 2176
  hb "lyrics dwell"
  pause 12 6
  input swipe 900 1300 200 1300 250; pause 4 2
  input swipe 200 1300 900 1300 250; pause 3 2
  input swipe 900 1300 200 1300 250; pause 5 3
  input tap 822 2176; pause 3 2
  input swipe 540 1600 540 1000 300; pause 2 1
  input swipe 200 1300 900 1300 250; pause 3 2
  input tap 541 2176; pause 3 2
  input keyevent 4; pause 2 1
  input swipe 900 1200 200 1200 220; pause 4 3
  input swipe 200 1200 900 1200 220; pause 4 3
  input tap 540 741; pause 3 2
  block_end nowplaying nowplaying
  input keyevent 4; pause 3 2

  # ---------- SETTINGS PEEK ----------
  block_start settings
  input tap 102 2216; pause 2 1
  input tap 1007 78
  pause 4 2
  input swipe 540 1700 540 1000 300; pause 2 1
  block_end settings settings
  input keyevent 4; pause 2 1

  # long listening window - real usage, canvas/lyrics/network active
  block_start listening
  hb "just listening"
  pause 25 20
  block_end listening listening
done
echo "TOUR_DONE cycles=$CYC $(date +%H:%M:%S)" >> $OUT/summary.txt
hb DONE
