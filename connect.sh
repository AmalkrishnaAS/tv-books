#!/bin/sh
# Connects Books on the TV to an Audiobookshelf server.
#   TV=<tv-ip>:5555 sh connect.sh http://<server-ip>:13378
# Create the key in Audiobookshelf first: Settings > API Keys > Add (your user, no expiry).
# The key is typed at a prompt, so it never lands in shell history, and checked before it's sent.
set -e
ADB=$(command -v adb || echo ~/Library/Android/sdk/platform-tools/adb)
TV=${TV:?set TV to the TV\'s adb address, e.g. TV=192.168.1.50:5555}
SERVER=${1:?give the Audiobookshelf address, e.g. http://192.168.1.20:13378}
SERVER=${SERVER%/}

printf 'Audiobookshelf API key: '
stty -echo; read -r KEY; stty echo; echo
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$SERVER/api/me")
[ "$code" = 200 ] || { echo "The server refused that key (HTTP $code)." >&2; exit 1; }

"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" shell am start -n com.amalkrishna.books/.MainActivity --es server "$SERVER" --es key "$KEY" >/dev/null
echo "Books is connected to $SERVER."
