#!/bin/zsh
# Send keys to the TV through a virtual USB keyboard (supports real long-presses).
# Usage: ./tvkey.sh enter            press Enter
#        ./tvkey.sh hold:enter       long-press Enter (1.5s)
#        ./tvkey.sh right right down enter
# Keys: enter up down left right esc menu
typeset -A code=(enter 40 esc 41 right 79 left 80 down 81 up 82 menu 118)
desc='5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192'
json='{"id":1,"command":"register","name":"Claude Keyboard","vid":6353,"pid":1,"bus":"usb","descriptor":['$desc']}
{"id":1,"command":"delay","duration":800}'
for k in "$@"; do
  hold=200
  [[ $k == hold:* ]] && { hold=1500; k=${k#hold:}; }
  json+='
{"id":1,"command":"report","report":[0,0,'${code[$k]}',0,0,0,0,0]}
{"id":1,"command":"delay","duration":'$hold'}
{"id":1,"command":"report","report":[0,0,0,0,0,0,0,0]}
{"id":1,"command":"delay","duration":350}'
done
print -r -- "$json" | adb shell hid -
