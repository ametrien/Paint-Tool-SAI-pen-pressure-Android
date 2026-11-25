#!/bin/bash
# speed-bench.sh <label>: how much CPU SAI uses for a set of brush strokes, how long it keeps
# drawing after the pen is up, and on which CPU cores its threads ran. Not part of the app.
#
#   speed-bench.sh <label>        strokes: SAI must already be open, on a new canvas, with the
#                                 brush you want to test picked
#   IDLE=1 speed-bench.sh <label> no strokes: 30 s of every process of the app, nothing drawn
#
#   OUT=dir (default ./bench-out)  N=strokes (20)  GAP=seconds between strokes (2.5)
#   SAMPLE=seconds between samples (0.05)  SECONDS_IDLE=30
#
# Everything is timed on the tablet itself: one shell script there draws the strokes with
# `input stylus swipe` and, in the background, every SAMPLE seconds reads /proc/<pid>/task/*/stat
# of SAI (or of every process of the app, IDLE=1). Field 39 of a thread's stat is the core it ran
# on last. Works on the release build: /proc/<pid>/stat is readable from adb shell.
# Summarise with tools/speed-benchstats.py bench-out/speed-<label>.txt
#
# It does not stop or restart anything, but the strokes are drawn on SAI's open canvas and
# count in the drawing statistics. Coordinates are for a 2000x1200 landscape screen.
set -euo pipefail
label=${1:?usage: speed-bench.sh <label>}
S=${OUT:-./bench-out}
mkdir -p "$S"
N=${N:-20}; GAP=${GAP:-2.5}; SAMPLE=${SAMPLE:-0.05}; IDLE=${IDLE:-0}; SECONDS_IDLE=${SECONDS_IDLE:-30}

pid=$(adb shell pidof sai2.exe </dev/null | tr -d '\r' || true)
[ -n "$pid" ] || { echo "SAI (sai2.exe) is not running on the tablet"; exit 1; }
if [ "$IDLE" = 1 ]; then
  echo "Idle sample for ${SECONDS_IDLE} s: do not touch the tablet."
else
  echo "Will draw $N strokes on SAI's open canvas (they count in the drawing statistics)."
  # GO=1: already asked (adb reads stdin, so a piped "yes" never reaches read)
  [ "${GO:-0}" = 1 ] || read -r -p "SAI on a new canvas, the test brush picked, nothing unsaved? Enter to go, Ctrl-C to stop "
fi

cat > "$S/.speed-bench-device.sh" <<'EOF'
#!/system/bin/sh
# runs on the tablet: $1 mode (strokes|idle), $2 N, $3 GAP, $4 SAMPLE, $5 idle seconds
mode=$1; n=$2; gap=$3; sample=$4; idle=$5
up() { read -r u _ < /proc/uptime; echo "$u"; }
echo "# date $(date '+%Y-%m-%d %H:%M:%S') mode $mode n $n gap $gap sample $sample"
for c in /sys/devices/system/cpu/cpu[0-9]*; do
  echo "# cpu ${c##*cpu} max_khz $(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null)"
done
echo "# powersave $(settings get global low_power 2>/dev/null)"
sai=$(pidof sai2.exe)
if [ "$mode" = idle ]; then
  uid=$(stat -c %u /proc/$sai)
  pids=""
  for p in /proc/[0-9]*; do [ "$(stat -c %u $p 2>/dev/null)" = "$uid" ] && pids="$pids ${p#/proc/}"; done
else
  pids=$sai
fi
echo "# pids $pids"
# the sampler and the strokes write to their own files: one stdout would mix their lines
rm -f /data/local/tmp/sb-samples /data/local/tmp/sb-marks
(
  while :; do
    echo "T $(up)"
    for p in $pids; do cat /proc/$p/task/*/stat 2>/dev/null | sed "s/^/P $p /"; done
    sleep $sample
  done
) > /data/local/tmp/sb-samples &
sampler=$!
sleep 1
if [ "$mode" = idle ]; then
  sleep $idle
else
  i=0
  while [ $i -lt $n ]; do
    y=$((200 + (i % 12) * 60))
    echo "S $(up)" >> /data/local/tmp/sb-marks
    input stylus swipe 740 $y 1680 $((y + 30)) 400
    echo "E $(up)" >> /data/local/tmp/sb-marks
    sleep $gap
    i=$((i + 1))
  done
  sleep 2
fi
kill $sampler
wait $sampler 2>/dev/null
EOF
adb push -q "$S/.speed-bench-device.sh" /data/local/tmp/speed-bench.sh
mode=strokes; [ "$IDLE" = 1 ] && mode=idle
adb shell sh /data/local/tmp/speed-bench.sh $mode "$N" "$GAP" "$SAMPLE" "$SECONDS_IDLE" </dev/null \
  | tr -d '\r' > "$S/speed-$label.txt" || true
# the samples come back as files: through adb shell's output a long run got cut off (2026-11-24)
for f in sb-marks sb-samples; do
  adb pull -q /data/local/tmp/$f "$S/.$f" 2>/dev/null && cat "$S/.$f" >> "$S/speed-$label.txt"
  rm -f "$S/.$f"
done
echo "saved $S/speed-$label.txt ($(wc -l < "$S/speed-$label.txt") lines)"
python3 -I "$(dirname "$0")/speed-benchstats.py" "$S/speed-$label.txt"
