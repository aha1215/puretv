# PureTV Twitch VOD Resume Sync

This companion extension can link a Twitch website session to PureTV once, then sync VOD positions between your desktop browser and the TV. PureTV stores the linked session encrypted and can read Twitch resume history directly, so the computer does not need to stay on. The optional live browser-to-TV position sync remains available as well.

## Pair it

1. On the TV, open **Settings → Desktop browser VOD resume** and enable **Allow paired browser sync**.
2. Keep the TV and computer on the same home network. Note the `http://…:47821` address and pairing code shown by PureTV. New codes are 8 characters; choose **Rotate pairing code** once if the TV still shows an older 12-character code.
3. Install the extension in your browser:
   - **Chrome or Edge:** open the extensions page, turn on Developer mode, choose **Load unpacked**, and select this `twitch-vod-sync` folder.
   - **Zen:** open `about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on**, and select `manifest.json` from this folder. Firefox based browsers use the included background-script fallback; temporary add-ons are removed when the browser closes.
4. Open the extension popup, enter the TV address and pairing code, then choose **Pair / Save** and grant access to that local address.
5. In PureTV Settings, create the 10-character browser sign-in code (valid for 15 minutes). In the extension popup, enter the TV address and that one-time code, choose **Link Twitch website session to TV**, and approve the optional cookie permission. This sends the website session encrypted over the local network using the one-time code; the code itself is not sent with the encrypted session. PureTV stores the session in Android encrypted storage. After linking, the TV can read and update Twitch VOD resume positions even while the computer is off.
6. If you also want live browser-to-TV sync, open Twitch on your computer. The homepage's Continue Watching positions advance the TV history, and an open VOD syncs its player time. If the TV is farther along when a VOD opens, the browser player seeks forward to the TV's saved checkpoint after Twitch restores its own position.

Normal position sync never reads Twitch cookies, chat, or message text. The opt-in **Link Twitch website session to TV** action reads only the Twitch `auth-token` cookie after the browser permission prompt, encrypts it with AES-GCM using a key derived from the TV pairing code, and sends the ciphertext directly to the paired TV. The TV decrypts and saves it using Android encrypted storage. The extension does not save the cookie value. Both sync paths keep the farther checkpoint; older or delayed updates never move the TV's saved position backward. The TV receiver runs only while browser sync is enabled and the PureTV app process is alive.

The homepage sync covers VODs currently shown in Continue Watching. It does not import older VODs that Twitch no longer shows in that row, or positions created while the TV receiver was disabled.
