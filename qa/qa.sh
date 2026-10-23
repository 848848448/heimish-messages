#!/bin/bash
# QA harness: runs on emulator in CI. Collects evidence into qa/out and acts as the
# release gate: exits non-zero when the app fails to install, crashes or ANRs, or a
# real screen assertion fails. Advisory tap misses stay in problems.txt (warnings).
P=com.heimish.messages
O=qa/out; mkdir -p $O
sh(){ adb shell "$@"; }
shot(){ python3 qa/dismiss.py >/dev/null 2>&1; sleep 1.5; adb exec-out screencap -p > $O/$1.png; python3 qa/dump.py > $O/$1.txt 2>&1; }
tap(){ python3 qa/tap.py "$@" || echo "!! could not tap $*" >> $O/problems.txt; sleep 1; }
xy(){ sh input tap $1 $2; sleep 1; }
back(){ sh input keyevent KEYCODE_BACK; sleep 1; }
assert(){ grep -qi -- "$2" $O/$1.txt 2>/dev/null || echo "ASSERT FAIL: screen $1 missing '$2'" >> $O/assert_fails.txt; }
assert_count(){ # assert_count <shot> <text> <min-occurrences>
  n=$(grep -oi -- "$2" $O/$1.txt 2>/dev/null | wc -l)
  [ "$n" -ge "$3" ] || echo "ASSERT FAIL: screen $1 has $n x '$2', want >= $3" >> $O/assert_fails.txt
}
# The suite runs on the QA variant when it was built (app-qa.apk = public build plus
# the QA-only fixture screen); otherwise on the exact public artifact.
PROD=app/build/outputs/apk/debug/app-debug.apk; QAAPK=app/build/outputs/apk/qa/app-qa.apk
APK=$PROD; [ -f "$QAAPK" ] && APK=$QAAPK
adb install -r $APK > $O/install.txt 2>&1
grep -q Success $O/install.txt || echo "FATAL: apk install failed" >> $O/gate_fails.txt
for p in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS CAMERA RECORD_AUDIO READ_MEDIA_IMAGES; do sh pm grant $P android.permission.$p; done
for i in 1 2 3; do
  sh cmd role add-role-holder --user 0 android.app.role.SMS $P > $O/role.txt 2>&1
  sh cmd role get-role-holders --user 0 android.app.role.SMS >> $O/role.txt 2>&1
  grep -q $P $O/role.txt && break
  echo "role attempt $i failed: $(tail -1 $O/role.txt)" >> $O/problems.txt; sleep 5
done
grep -q $P $O/role.txt || echo "FATAL: default SMS role not held after 3 attempts" >> $O/gate_fails.txt
sh settings put global window_animation_scale 0
# contacts
for n in "Moshe Katz:5551110001" "Chaya Weiss:5551110002"; do
  name=${n%%:*}; num=${n##*:}
  sh content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:com.test --bind account_name:s:t >/dev/null
  id=$(sh content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed 's/.*_id=//')
  sh content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind "'data1:s:$name'"
  sh content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$num --bind data2:i:2
done
adb logcat -c
# incoming SMS through the real receive path
adb emu sms send 5551110001 "Hey, are we still on for Shabbos dinner?"; sleep 2
adb emu sms send 5551110002 "Mazel tov! Just heard the news"; sleep 2
adb emu sms send 5551110002 "Call me when you can"; sleep 2
adb emu sms send 7185550123 "Your verification code is 482913"; sleep 2
adb emu sms send 5551110001 "Also bring the folding chairs please, there is a long line of people coming and I want to make sure everybody has a seat at the table"; sleep 2
for i in $(seq 1 12); do adb emu sms send 5551110003 "Message number $i in a long thread"; sleep 1; done
fresh(){ sh am force-stop $P; sh am start -n $P/.MainActivity >/dev/null; sleep 3; python3 qa/dismiss.py >/dev/null 2>&1; }
opent(){ for i in 1 2 3; do
  python3 qa/dismiss.py >/dev/null 2>&1
  python3 qa/tap.py Moshe && break
  python3 qa/tap.py 5551110001 && break
  sleep 2
done; sleep 1.5; }
menu(){ xy 1007 213; }
fresh; shot 01_list
assert 01_list "Messages"
assert 01_list "New chat"
sh dumpsys notification --noredact 2>/dev/null | grep -E 'pkg=com.heimish|android.title|android.text' | head -20 > $O/notifications.txt
sh dumpsys gfxinfo $P reset >/dev/null
for i in 1 2 3 4 5; do sh input swipe 540 1800 540 600 150; sh input swipe 540 600 540 1800 150; done
sh dumpsys gfxinfo $P > $O/gfx_list.txt
fresh; opent; shot 02_thread
assert 02_thread "folding chairs"
sh dumpsys gfxinfo $P reset >/dev/null
for i in 1 2 3; do sh input swipe 540 500 540 1700 120; sh input swipe 540 1700 540 500 120; done
sh dumpsys gfxinfo $P > $O/gfx_thread.txt
sh input tap 540 2250; sh input text "Testing%sa%smessage"; shot 03_typing
python3 qa/tap.py Send || xy 1000 2250 ; sleep 2; shot 04_sent
assert 04_sent "Testing a message"
sh input swipe 300 1500 300 1500 700; shot 05_bubble_menu
fresh; opent; xy 1040 150; shot 06_thread_menu_a
fresh; opent; xy 130 2240; shot 07_attach_menu
fresh; opent; xy 1000 2240; shot 07b_emoji_or_img
fresh; opent; tap "Search in chat" ; shot 07c_thread_search
fresh; menu; shot 08_list_menu
fresh; tap "New chat"; shot 09_new_chat; sh input text "555"; shot 09b_new_chat_typed
assert 09_new_chat "To:"
fresh; sh input tap 540 380; sh input text "Chaya"; shot 10_search_results
fresh; menu; tap "Settings"; shot 12_settings
assert 12_settings "Settings"
for s2 in "Notifications" "Protection" "Swipe" "Advanced" "Theme" "Chat bubbles" "Text size" "Automatic previews" "About"; do fresh; menu; tap "Settings"; tap "$s2" && shot "13_sub_$s2"; done
fresh; menu; tap "Archived"; shot 14_archived
fresh; menu; tap "Starred"; shot 15_starred
fresh; menu; tap "Scheduled"; shot 16_scheduled
fresh; menu; tap "Admin"; shot 17_admin
# Swipe/long-press affordance shots use a sacrificial conversation: a committing
# swipe (swipe-to-archive) must never touch conversations later asserts depend on.
adb emu sms send 5554447777 "swipe affordance test row"; sleep 2
# The swipe gesture only runs after the sacrificial row is verified inside the
# swipe band (first row, y 444-645); otherwise it is skipped and recorded, never
# allowed to hit a real conversation. No assertion is weakened: if the row never
# lands, problems.txt says so and the shot shows the plain list.
fresh
sac_ok=""
for i in 1 2 3 4 5 6; do
  python3 qa/dump.py > /tmp/sac_dump.txt 2>/dev/null
  sac_y=$(grep "swipe affordance test row" /tmp/sac_dump.txt | head -1 | sed -E 's/^\[[0-9]+, ([0-9]+),.*/\1/')
  case "$sac_y" in ''|*[!0-9]*) ;; *) [ "$sac_y" -ge 444 ] && [ "$sac_y" -le 645 ] && sac_ok=1 && break;; esac
  sleep 2
done
if [ -n "$sac_ok" ]; then sh input swipe 900 600 200 600 200
else echo "!! sacrificial row not at swipe position; swipe gesture skipped" >> $O/problems.txt; fi
shot 18_swipe_row
fresh; sh input swipe 600 600 600 600 800; shot 19_row_longpress
cmd_night(){ sh cmd uimode night $1; sleep 2; }
cmd_night yes; fresh; shot 20_dark_list; opent; shot 21_dark_thread; back; menu; tap Settings; shot 22_dark_settings; cmd_night no
assert 21_dark_thread "folding chairs"
adb logcat -d > $O/logcat_full.txt
grep -E 'FATAL EXCEPTION|ANR in|AndroidRuntime|Skipped [0-9]+ frames|Choreographer|StrictMode|Exception' $O/logcat_full.txt | grep -i -E "heimish|FATAL|ANR|Skipped|StrictMode" | head -80 > $O/logcat_problems.txt
adb logcat -d -s HM:* > $O/hm_log.txt
sh dumpsys meminfo $P | head -30 > $O/meminfo.txt
cat > $O/summary.txt <<S
gfx list: $(grep -E 'Total frames|Janky frames|50th|90th|95th|99th' $O/gfx_list.txt | tr '\n' ' ')
gfx thread: $(grep -E 'Total frames|Janky frames|50th|90th|95th|99th' $O/gfx_thread.txt | tr '\n' ' ')
S
# populated numeric dataset
for n in $(seq 1 25); do adb emu sms send 1718555$(printf %04d $n) "Order $n shipped, track at https://example.com/t/$n"; sleep 0.5; done
adb emu sms send 18005551234 "50% off sale today only. Reply STOP to opt out"; sleep 1
adb emu sms send 5559990001 "."; sleep 1; adb emu sms send 5559990001 "ok"; sleep 1; adb emu sms send 5559990001 "see you at 8, bring the kids and the folding chairs please"; sleep 2
fresh; shot 30_list_populated
sh input tap 540 1000; sleep 1; shot 31_back_check
fresh; python3 qa/tap.py 5559990001; sleep 2; shot 32_short_bubbles
sh input tap 540 2240; sh input text "hello%sthere"; xy 540 2240; shot 33_compose
xy 1040 150; python3 qa/tap.py "Schedule send"; sleep 1; shot 34_schedule_quick
back; fresh; python3 qa/tap.py "Transactions"; sleep 1; shot 35_category
fresh; sh input tap 540 520; shot 36_update_row_or_status
fresh; python3 qa/tap.py 5559990001; sleep 2; xy 1040 150; shot 40_thread_menu
python3 qa/tap.py "Details"; sleep 1.5; shot 41_details
fresh; python3 qa/tap.py 5559990001; sleep 2; sh input tap 540 2240; sh input text "hello%sthere"; sleep 1; back; sleep 1; xy 1040 150; python3 qa/tap.py "Schedule send"; sleep 1.5; shot 42_schedule_quick
back; sleep 1; back; sleep 1; back; sleep 2; shot 45_list_draft
fresh; sh input swipe 540 700 540 700 900; sleep 1; python3 qa/tap.py "Select"; sleep 1; sh input tap 540 900; sleep 1; shot 44_multi_select
fresh; python3 qa/tap.py "Transactions"; sleep 1; shot 46_transactions
fresh; sh input swipe 540 700 540 700 900; sleep 1; python3 qa/tap.py "Mark as unread"; sleep 2; shot 47_marked_unread
fresh; python3 qa/tap.py 5559990001; sleep 2; xy 400 210; sleep 1; shot 50_details_header
sh input swipe 540 1500 540 500 250; shot 51_details_scrolled
back; sleep 1; back; sleep 1; fresh; python3 qa/tap.py 5559990001; sleep 2; xy 1040 210; shot 52_menu_short
python3 qa/tap.py More; sleep 1; shot 53_menu_more
fresh; python3 qa/tap.py Unknown; sleep 1; shot 54_unknown_tab
# Pin flow: long-press the seeded message, choose Pin, verify the pinned banner appears
fresh; python3 qa/tap.py 5559990001; sleep 2
python3 qa/lp.py "see you at 8" || python3 qa/lp.py "folding chairs" || echo "!! could not long-press message" >> $O/problems.txt
sleep 1; shot 55a_msg_menu; assert 55a_msg_menu "Pin"
python3 qa/tap.py Pin || echo "!! could not tap Pin" >> $O/problems.txt
sleep 1.5; shot 55_pinned; assert_count 55_pinned "see you at 8" 2
cmd_night yes; fresh; python3 qa/tap.py 5559990001; sleep 2; xy 400 210; sleep 1; shot 56_dark_details; cmd_night no
fresh; menu; tap Settings; sh input swipe 540 1800 540 300 250; sleep 1; shot 57_settings_bottom; tap About; sleep 1; shot 58_about; tap "Check for updates"; sleep 6; shot 59_about_checked
fresh; menu; tap Settings; tap Protection; shot 60_protection
# MMS media-type fixtures: if the provider accepts inserted attachment-only MMS rows,
# the list must label each by its real type. Fixture failure is advisory (real carrier
# ingestion stays a separate, device-verified limit), fixture success makes labels asserted.
if python3 qa/mms_fixture.py > $O/mms_fixture.txt 2>&1 && grep -q FIXTURE_OK $O/mms_fixture.txt; then
  fresh; shot 61_mms_labels
  assert 61_mms_labels "Voice message"; assert 61_mms_labels "Video"; assert 61_mms_labels "GIF"; assert 61_mms_labels "Contact card"
else
  echo "MMS fixture unavailable; per-type labels remain code-verified only" >> $O/problems.txt
fi
# QA-only list fixture: real rows, synthetic models (nothing written to the
# provider). Asserts two-line group titles (EN + RTL), timestamps and unread badges.
# On the qa variant the fixture MUST launch and render (fixture_start.txt kept as
# evidence of the shell invocation); on a production-only run it is advisory-skipped.
sh am start -n $P/.QaFixtureActivity > $O/fixture_start.txt 2>&1; sleep 3
python3 qa/dump.py > /tmp/fix_probe.txt 2>/dev/null
if grep -q "QA list fixture" /tmp/fix_probe.txt; then
  shot 70_fixture_light; assert 70_fixture_light "Yankel Friedman"
  python3 qa/fixture_check.py $O/70_fixture_light.txt || echo "ASSERT FAIL: fixture wrap/alignment (70_fixture_light): $(python3 qa/fixture_check.py $O/70_fixture_light.txt 2>&1)" >> $O/assert_fails.txt
  sh settings put system font_scale 1.3; sleep 1
  sh am start -n $P/.QaFixtureActivity >/dev/null 2>&1; sleep 2
  shot 71_fixture_largefont; assert 71_fixture_largefont "Yankel Friedman"
  python3 qa/fixture_check.py $O/71_fixture_largefont.txt || echo "ASSERT FAIL: fixture wrap/alignment (71_fixture_largefont): $(python3 qa/fixture_check.py $O/71_fixture_largefont.txt 2>&1)" >> $O/assert_fails.txt
  sh settings put system font_scale 1.0; sleep 1
  cmd_night yes; sh am start -n $P/.QaFixtureActivity >/dev/null 2>&1; sleep 2
  shot 72_fixture_dark; assert 72_fixture_dark "Yankel Friedman"
  cmd_night no; back; sleep 1
elif [ "$APK" != "$PROD" ]; then
  echo "ASSERT FAIL: fixture activity did not launch/render on the qa variant (fixture_start.txt)" >> $O/assert_fails.txt
else
  echo "!! QA list fixture activity unavailable on this build; fixture shots skipped" >> $O/problems.txt
fi
# Pin behavior in a real long thread: the banner tap jumps to the pinned message
# (asserted: bubble appears next to the banner text), the banner stays fixed while
# the list scrolls, and keyboard open/close leaves it in place. Screenshots are the
# visual evidence; assert_count on 62_pin_jump is the executed jump assertion.
# The long thread is seeded first and later fixtures bury it below the fold; bump it
# back to the top row before opening (run 39930565756 failed here on a buried row).
adb emu sms send 5551110003 "Message number 13 in a long thread"; sleep 2
fresh; python3 qa/tap.py 5551110003; sleep 2
for i in 1 2 3 4 5 6; do sh input swipe 540 500 540 1700 120; done; sleep 1
python3 qa/lp.py "Message number 1 in a long thread" && sleep 1 && python3 qa/tap.py Pin || echo "!! could not pin message 1" >> $O/problems.txt
sleep 1.5
fresh; python3 qa/tap.py 5551110003; sleep 2
shot 62_pin_banner_long
python3 qa/tap.py "Message number 1 in a long thread" && sleep 2 || echo "!! could not tap pin banner" >> $O/problems.txt
shot 62_pin_jump; assert_count 62_pin_jump "Message number 1 in a long thread" 2
sh input swipe 540 1700 540 500 120; sleep 1; shot 63_pin_scrolled; assert 63_pin_scrolled "Message number 1 in a long thread"
sh input tap 540 2240; sleep 2; shot 64_pin_keyboard; assert 64_pin_keyboard "Message number 1 in a long thread"
sh input keyevent KEYCODE_BACK; sleep 1.5; shot 65_pin_keyboard_closed; assert 65_pin_keyboard_closed "Message number 1 in a long thread"
# Production-APK smoke: when the suite ran on the QA variant, prove the exact public
# artifact installs, launches clean, and does NOT contain the fixture activity.
if [ "$APK" != "$PROD" ]; then
  adb install -r $PROD > $O/install_prod.txt 2>&1
  grep -q Success $O/install_prod.txt || echo "FATAL: production apk install failed" >> $O/gate_fails.txt
  sh am start -n $P/.QaFixtureActivity > $O/prod_fixture_absence.txt 2>&1
  grep -qi "does not exist\|Unable to find\|Error" $O/prod_fixture_absence.txt || echo "ASSERT FAIL: fixture activity resolves in the production apk (prod_fixture_absence.txt)" >> $O/assert_fails.txt
  fresh; shot 80_prod_smoke; assert 80_prod_smoke "Messages"; assert 80_prod_smoke "New chat"
fi
# Crash/ANR gate: scoped to the app under test; system-process noise (e.g. Pixel
# Launcher ANRs, contacts provider StrictMode) is evidence, not a failure.
adb logcat -d > $O/logcat_full.txt
grep -B2 -A12 'FATAL EXCEPTION' $O/logcat_full.txt | grep -q "Process: $P" && echo "FATAL: app crashed (logcat_problems.txt)" >> $O/gate_fails.txt
grep -q "ANR in $P" $O/logcat_full.txt && echo "FATAL: app ANR (logcat_problems.txt)" >> $O/gate_fails.txt
grep -E 'FATAL EXCEPTION|ANR in|AndroidRuntime|Skipped [0-9]+ frames|Choreographer|StrictMode|Exception' $O/logcat_full.txt | grep -i -E "heimish|FATAL|ANR|Skipped|StrictMode" | head -80 > $O/logcat_problems.txt
{
  echo "gate_fails:"; cat $O/gate_fails.txt 2>/dev/null
  echo "assert_fails:"; cat $O/assert_fails.txt 2>/dev/null
  echo "problems (advisory):"; cat $O/problems.txt 2>/dev/null
} > $O/gate_report.txt
if [ -s $O/gate_fails.txt ] || [ -s $O/assert_fails.txt ]; then echo "QA GATE FAILED"; cat $O/gate_report.txt; exit 1; fi
echo "QA GATE PASSED"
