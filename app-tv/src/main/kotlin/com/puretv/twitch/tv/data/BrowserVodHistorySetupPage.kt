package com.puretv.twitch.tv.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Self-contained LAN page that gives the user a Twitch bookmarklet and manual link fallback. */
internal object BrowserVodHistorySetupPage {
    fun render(pairingCode: String, gqlClientId: String, historyQuery: String): String = PAGE
        .replace("%PAIRING_CODE%", pairingCode)
        .replace("%GQL_CLIENT_ID%", gqlClientId)
        .replace("%HISTORY_QUERY_JSON%", Json.encodeToString(historyQuery))

    private val PAGE = """
        <!doctype html>
        <html lang="en">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta charset="utf-8">
        <title>PureTV VOD history sync</title>
        <style>
          body{font:16px/1.5 system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 18px;color:#eee;background:#111}
          h1{font-size:1.5rem} section{border:1px solid #444;border-radius:12px;padding:16px;margin:18px 0}
          textarea,input,button{box-sizing:border-box;width:100%;font:inherit;border-radius:8px;padding:10px}
          textarea{min-height:150px;background:#1b1b1b;color:#b8f4c1;word-break:break-all}
          input{background:#fff;color:#111}button{margin-top:10px;background:#9147ff;border:0;color:#fff;font-weight:700}
          .muted{color:#bbb}.code{font-family:monospace;color:#b8f4c1}
        </style>
        <body>
          <h1>Sync Twitch VOD history to PureTV</h1>
          <p>This temporary pairing expires in 15 minutes. The bookmarklet sends VOD IDs and resume positions to your TV; it does not send your Twitch login token.</p>
          <section>
            <h2>One-time Android Chrome setup</h2>
            <ol>
              <li>Copy the bookmarklet text below.</li>
              <li>In Chrome, add any page as a bookmark, edit that bookmark, and replace its address with the copied text. Name it <b>Sync Twitch history</b>.</li>
              <li>Open <a href="https://www.twitch.tv/" style="color:#bf94ff">Twitch</a>, sign in, request Desktop site if needed, then run the bookmark from Chrome’s address bar.</li>
              <li>Allow Chrome to connect to devices on your local network when prompted.</li>
            </ol>
            <p class="muted">If Twitch hides its session from page scripts or blocks the query, the bookmarklet will report that. Use the timestamped-link fallback below for a single VOD.</p>
            <textarea id="bookmarklet" readonly aria-label="Bookmarklet text"></textarea>
            <button onclick="copyBookmarklet()">Copy bookmarklet</button>
            <p id="copyStatus" class="muted">If clipboard access is unavailable, long-press the text, choose Select all, then Copy.</p>
          </section>
          <section>
            <h2>Fallback: send one VOD</h2>
            <p>On Twitch, use Share and enable the timestamp option. Paste the resulting VOD link here.</p>
            <input id="vodUrl" type="url" placeholder="https://www.twitch.tv/videos/123456789?t=1h2m3s">
            <button onclick="sendSingleVod()">Send this VOD to the TV</button>
            <p id="manualStatus" class="muted"></p>
          </section>
          <script>
            const pairCode = "%PAIRING_CODE%";
            const clientId = "%GQL_CLIENT_ID%";
            const historyQuery = %HISTORY_QUERY_JSON%;
            const receiver = location.origin;
            const bearer = "Bearer " + pairCode;
            const bookmarkletBody = `(async()=>{try{
              const cookie=document.cookie.split(";").map(x=>x.trim()).find(x=>x.startsWith("auth-token="));
              if(!cookie)throw new Error("Twitch did not expose its session cookie to this bookmarklet.");
              const token=decodeURIComponent(cookie.slice("auth-token=".length));
              const response=await fetch("https://gql.twitch.tv/gql",{method:"POST",headers:{"Client-ID":${'$'}{JSON.stringify(clientId)},"Authorization":"OAuth "+token,"Content-Type":"application/json"},body:JSON.stringify({operationName:"ResumeWatchingVideosQuery",query:${'$'}{JSON.stringify(historyQuery)},variables:{limit:100}})});
              if(!response.ok)throw new Error("Twitch history request failed ("+response.status+").");
              const data=await response.json();
              const viewer=data&&data.data&&data.data.currentUser;
              if(!viewer||!viewer.id||!viewer.viewedVideos)throw new Error("Twitch did not return browser resume history.");
              const entries=(viewer.viewedVideos.edges||[]).map(edge=>({vodId:String(edge&&edge.node&&edge.node.id||""),positionMs:Math.max(0,Math.round(Number(edge&&edge.history&&edge.history.position||0)*1000))})).filter(x=>/^\\d+$/.test(x.vodId));
              const result=await fetch(${ '$' }{JSON.stringify(receiver+"/v1/browser-vod-history")},{method:"POST",headers:{"Authorization":"Bearer "+${ '$' }{JSON.stringify(pairCode)},"Content-Type":"application/json"},body:JSON.stringify({viewerId:String(viewer.id),entries})});
              if(!result.ok)throw new Error("PureTV rejected the history transfer ("+result.status+"). Keep the TV pairing screen open and try again.");
              alert("Sent "+entries.length+" Twitch VOD checkpoints to PureTV.");
            }catch(error){alert("PureTV history sync: "+(error&&error.message||error));}})()`;
            document.getElementById("bookmarklet").value="javascript:"+bookmarkletBody;
            async function copyBookmarklet(){
              const field=document.getElementById("bookmarklet");field.focus();field.select();
              try{await navigator.clipboard.writeText(field.value);document.getElementById("copyStatus").textContent="Bookmarklet copied. Edit a Chrome bookmark and paste it into the address field.";}
              catch(_){document.getElementById("copyStatus").textContent="Text selected. Choose Copy, then edit a Chrome bookmark and paste it into the address field.";}
            }
            async function sendSingleVod(){
              const status=document.getElementById("manualStatus");status.textContent="Sending…";
              try{
                const response=await fetch(receiver+"/v1/browser-history/manual",{method:"POST",headers:{"Authorization":bearer,"Content-Type":"application/json"},body:JSON.stringify({url:document.getElementById("vodUrl").value})});
                const body=await response.text();if(!response.ok)throw new Error(body||("Request failed ("+response.status+")"));
                status.textContent="VOD sent to the TV. You can close this page.";
              }catch(error){status.textContent=(error&&error.message)||"Could not send the VOD link.";}
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
