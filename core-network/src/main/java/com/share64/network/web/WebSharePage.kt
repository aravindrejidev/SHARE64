package com.share64.network.web

/**
 * The page a browser gets when it opens a share link. One self-contained file (no external fonts,
 * scripts or images: a hotspot has no internet), English and Malayalam, light and dark.
 * File names are only ever put into the page with textContent, never as HTML.
 */
object WebSharePage {
    fun html(): String = PAGE

    private val PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<meta name="referrer" content="no-referrer">
<title>SHARE64</title>
<style>
:root{--bg:#fbf8ff;--card:#efeaf8;--card2:#e5def2;--ink:#1c1b22;--mut:#5d5967;--pri:#5e43d8;--onpri:#fff;--tert:#e4f06b;--ontert:#1b1e00;--err:#ba1a1a;--errbg:#ffdad6}
@media (prefers-color-scheme:dark){:root{--bg:#141318;--card:#201f25;--card2:#2b292f;--ink:#e6e1e9;--mut:#c9c5d0;--pri:#cbbeff;--onpri:#2d0f8f;--tert:#c8d44f;--ontert:#2e3300;--err:#ffb4ab;--errbg:#93000a}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Malayalam",sans-serif}
main{max-width:640px;margin:0 auto;padding:max(20px,env(safe-area-inset-top)) 16px calc(120px + env(safe-area-inset-bottom))}
.brand{display:flex;align-items:center;gap:12px;margin:8px 0 22px}
.brand svg{width:44px;height:44px;flex:none;animation:spin 18s linear infinite}
.brand b{font-weight:900;letter-spacing:-.5px;font-size:20px}
@keyframes spin{to{transform:rotate(360deg)}}
h1{font-size:34px;line-height:1.1;font-weight:900;letter-spacing:-.8px;margin:0 0 6px;overflow-wrap:anywhere}
.sub{color:var(--mut);margin:0 0 22px}
.hero{background:var(--card);border-radius:36px;padding:28px 22px;text-align:center;animation:pop .5s cubic-bezier(.2,1.4,.4,1) both}
@keyframes pop{from{transform:scale(.9);opacity:0}to{transform:none;opacity:1}}
.rings{position:relative;width:120px;height:120px;margin:0 auto 14px}
.rings i{position:absolute;inset:0;border:3px solid var(--pri);border-radius:50%;opacity:0;animation:ping 2.4s ease-out infinite}
.rings i:nth-child(2){animation-delay:1.2s}
.rings svg{position:absolute;inset:24px;width:72px;height:72px;animation:spin 14s linear infinite}
@keyframes ping{from{transform:scale(.45);opacity:.7}to{transform:scale(1);opacity:0}}
.hero h2{margin:0 0 6px;font-size:22px;font-weight:800}
.hero p{margin:0;color:var(--mut)}
.err{background:var(--errbg);color:var(--err)}
.err p{color:inherit}
.file{display:flex;align-items:center;gap:14px;background:var(--card);border-radius:28px;padding:14px 14px 14px 16px;margin:0 0 10px;animation:pop .45s cubic-bezier(.2,1.4,.4,1) both}
.file .ic{font-size:30px;flex:none;width:44px;text-align:center}
.file .meta{flex:1;min-width:0}
.file .nm{font-weight:700;overflow-wrap:anywhere}
.file .sz{color:var(--mut);font-size:14px}
.btn{display:inline-block;border:0;border-radius:40px;padding:13px 20px;font:700 15px system-ui,sans-serif;color:var(--onpri);background:var(--pri);text-decoration:none;cursor:pointer;transition:border-radius .2s,transform .15s}
.btn:active{border-radius:12px;transform:scale(.96)}
.btn.t{background:var(--card2);color:var(--ink)}
.row{display:flex;gap:8px;flex:none}
.bar{position:fixed;left:0;right:0;bottom:0;padding:12px 16px calc(14px + env(safe-area-inset-bottom));background:linear-gradient(transparent,var(--bg) 35%);display:flex;justify-content:center}
.bar .btn{width:100%;max-width:608px;text-align:center;font-size:17px;padding:17px 20px;background:var(--tert);color:var(--ontert)}
.note{text-align:center;color:var(--mut);font-size:13px;margin-top:18px}
[hidden]{display:none!important}
</style>
</head>
<body>
<main>
<div class="brand"><svg id="logo" viewBox="-50 -50 100 100"><path id="blob" fill="var(--pri)"/></svg><b>SHARE64</b></div>
<div id="app"></div>
<p class="note" id="note"></p>
</main>
<div class="bar" id="bar" hidden><a class="btn" id="zip"></a></div>
<script>
(function(){
"use strict";
var ml=/^ml/i.test(navigator.language||"");
var T={
 en:{connecting:"Connecting\u2026",waitT:"Waiting for permission",waitD:"%s needs to tap Allow on their phone. Your files will appear here.",denied:"Access was declined",deniedD:"The sender did not allow this device.",full:"Too many devices",fullD:"This share is full right now. Ask the sender to remove a device.",stopped:"Sharing has stopped",stoppedD:"The sender stopped sharing, or you left the Wi-Fi / hotspot.",retry:"Try again",
  head:"%s is sharing with you",files:"files",file:"file",dl:"Download",view:"View",zip:"Download all as ZIP",app:"Have SHARE64? Open in the app",note:"Files go straight from the sender's phone over the local network.",none:"No files in this share."},
 ml:{connecting:"\u0d15\u0d23\u0d15\u0d4d\u0d1f\u0d4d \u0d1a\u0d46\u0d2f\u0d4d\u0d2f\u0d41\u0d28\u0d4d\u0d28\u0d41\u2026",waitT:"\u0d05\u0d28\u0d41\u0d2e\u0d24\u0d3f\u0d15\u0d4d\u0d15\u0d3e\u0d2f\u0d3f \u0d15\u0d3e\u0d24\u0d4d\u0d24\u0d3f\u0d30\u0d3f\u0d15\u0d4d\u0d15\u0d41\u0d28\u0d4d\u0d28\u0d41",waitD:"%s \u0d2b\u0d4b\u0d23\u0d3f\u0d7d Allow \u0d05\u0d2e\u0d7c\u0d24\u0d4d\u0d24\u0d41\u0d28\u0d4d\u0d28\u0d24\u0d41\u0d35\u0d30\u0d46 \u0d15\u0d3e\u0d24\u0d4d\u0d24\u0d3f\u0d30\u0d3f\u0d15\u0d4d\u0d15\u0d42. \u0d2b\u0d2f\u0d32\u0d41\u0d15\u0d7e \u0d07\u0d35\u0d3f\u0d1f\u0d46 \u0d24\u0d46\u0d33\u0d3f\u0d2f\u0d41\u0d02.",denied:"\u0d05\u0d28\u0d41\u0d2e\u0d24\u0d3f \u0d28\u0d3f\u0d37\u0d47\u0d27\u0d3f\u0d1a\u0d4d\u0d1a\u0d41",deniedD:"\u0d05\u0d2f\u0d15\u0d4d\u0d15\u0d41\u0d28\u0d4d\u0d28\u0d2f\u0d3e\u0d7e \u0d08 \u0d09\u0d2a\u0d15\u0d30\u0d23\u0d24\u0d4d\u0d24\u0d46 \u0d05\u0d28\u0d41\u0d35\u0d26\u0d3f\u0d1a\u0d4d\u0d1a\u0d3f\u0d32\u0d4d\u0d32.",full:"\u0d09\u0d2a\u0d15\u0d30\u0d23\u0d19\u0d4d\u0d19\u0d33\u0d41\u0d1f\u0d46 \u0d2a\u0d30\u0d3f\u0d27\u0d3f \u0d0e\u0d24\u0d4d\u0d24\u0d3f",fullD:"\u0d07\u0d2a\u0d4d\u0d2a\u0d4b\u0d7e \u0d15\u0d42\u0d1f\u0d41\u0d24\u0d7d \u0d09\u0d2a\u0d15\u0d30\u0d23\u0d19\u0d4d\u0d19\u0d7e \u0d1a\u0d47\u0d7c\u0d15\u0d4d\u0d15\u0d3e\u0d28\u0d3e\u0d35\u0d3f\u0d32\u0d4d\u0d32. \u0d05\u0d2f\u0d15\u0d4d\u0d15\u0d41\u0d28\u0d4d\u0d28\u0d2f\u0d3e\u0d33\u0d4b\u0d1f\u0d4d \u0d12\u0d30\u0d41 \u0d09\u0d2a\u0d15\u0d30\u0d23\u0d02 \u0d28\u0d40\u0d15\u0d4d\u0d15\u0d3e\u0d7b \u0d2a\u0d31\u0d2f\u0d42.",stopped:"\u0d2a\u0d19\u0d4d\u0d15\u0d3f\u0d1f\u0d7d \u0d28\u0d3f\u0d7c\u0d24\u0d4d\u0d24\u0d3f",stoppedD:"\u0d05\u0d2f\u0d15\u0d4d\u0d15\u0d41\u0d28\u0d4d\u0d28\u0d2f\u0d3e\u0d7e \u0d2a\u0d19\u0d4d\u0d15\u0d3f\u0d1f\u0d7d \u0d28\u0d3f\u0d7c\u0d24\u0d4d\u0d24\u0d3f, \u0d05\u0d32\u0d4d\u0d32\u0d46\u0d19\u0d4d\u0d15\u0d3f\u0d7d \u0d28\u0d3f\u0d19\u0d4d\u0d19\u0d7e Wi-Fi / hotspot \u0d35\u0d3f\u0d1f\u0d4d\u0d1f\u0d41.",retry:"\u0d35\u0d40\u0d23\u0d4d\u0d1f\u0d41\u0d02 \u0d36\u0d4d\u0d30\u0d2e\u0d3f\u0d15\u0d4d\u0d15\u0d42",head:"%s \u0d28\u0d3f\u0d19\u0d4d\u0d19\u0d33\u0d41\u0d2e\u0d3e\u0d2f\u0d3f \u0d2b\u0d2f\u0d32\u0d41\u0d15\u0d7e \u0d2a\u0d19\u0d4d\u0d15\u0d3f\u0d1f\u0d41\u0d28\u0d4d\u0d28\u0d41",files:"\u0d2b\u0d2f\u0d32\u0d41\u0d15\u0d7e",file:"\u0d2b\u0d2f\u0d7d",dl:"\u0d21\u0d57\u0d7a\u0d32\u0d4b\u0d21\u0d4d",view:"\u0d15\u0d3e\u0d23\u0d41\u0d15",zip:"\u0d0e\u0d32\u0d4d\u0d32\u0d3e\u0d02 ZIP \u0d06\u0d2f\u0d3f \u0d21\u0d57\u0d7a\u0d32\u0d4b\u0d21\u0d4d \u0d1a\u0d46\u0d2f\u0d4d\u0d2f\u0d42",app:"SHARE64 \u0d09\u0d23\u0d4d\u0d1f\u0d4b? \u0d06\u0d2a\u0d4d\u0d2a\u0d3f\u0d7d \u0d24\u0d41\u0d31\u0d15\u0d4d\u0d15\u0d42",note:"\u0d2b\u0d2f\u0d32\u0d41\u0d15\u0d7e \u0d05\u0d2f\u0d15\u0d4d\u0d15\u0d41\u0d28\u0d4d\u0d28\u0d2f\u0d3e\u0d33\u0d41\u0d1f\u0d46 \u0d2b\u0d4b\u0d23\u0d3f\u0d7d \u0d28\u0d3f\u0d28\u0d4d\u0d28\u0d4d \u0d32\u0d4b\u0d15\u0d4d\u0d15\u0d7d \u0d28\u0d46\u0d31\u0d4d\u0d31\u0d4d\u200c\u0d35\u0d7c\u0d15\u0d4d\u0d15\u0d4d \u0d35\u0d34\u0d3f \u0d28\u0d47\u0d30\u0d3f\u0d1f\u0d4d\u0d1f\u0d4d \u0d35\u0d30\u0d41\u0d28\u0d4d\u0d28\u0d41.",none:"\u0d08 \u0d2a\u0d19\u0d4d\u0d15\u0d3f\u0d1f\u0d32\u0d3f\u0d7d \u0d2b\u0d2f\u0d32\u0d41\u0d15\u0d33\u0d3f\u0d32\u0d4d\u0d32."}
};
var L=T[ml?"ml":"en"];
function t(k,a){var s=L[k]||T.en[k]||k;return a===undefined?s:s.replace("%s",a);}
var base=location.pathname.replace(/[^\/]*${'$'}/,"");
var client="",sender="SHARE64",pollTimer=0;
var app=document.getElementById("app"),bar=document.getElementById("bar"),zip=document.getElementById("zip"),note=document.getElementById("note");

// the scalloped "cookie" logo
(function(){var n=9,a=.09,d="";for(var i=0;i<=120;i++){var th=2*Math.PI*i/120,r=46*(1+a*Math.cos(n*th))/(1+a);d+=(i?"L":"M")+(r*Math.cos(th)).toFixed(2)+" "+(r*Math.sin(th)).toFixed(2);}document.getElementById("blob").setAttribute("d",d+"Z");})();
function blobSvg(){return document.getElementById("logo").cloneNode(true);}

function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;}
function clear(){while(app.firstChild)app.removeChild(app.firstChild);bar.hidden=true;}
function url(path){return base+path+(client?(path.indexOf("?")<0?"?":"&")+"c="+encodeURIComponent(client):"");}
function fmt(n){if(n<0)return "";var u=["B","KB","MB","GB","TB"],i=0;while(n>=1024&&i<4){n/=1024;i++;}return (i?n.toFixed(n<10?1:0):n)+" "+u[i];}
function icon(f){var m=f.mime||"",x=(f.name.split(".").pop()||"").toLowerCase();
 if(m.indexOf("image/")===0)return "\ud83d\uddbc\ufe0f";if(m.indexOf("video/")===0)return "\ud83c\udfac";if(m.indexOf("audio/")===0)return "\ud83c\udfb5";
 if(x==="pdf")return "\ud83d\udcd5";if(x==="apk")return "\ud83d\udce6";if(/^(zip|rar|7z|gz|tar)${'$'}/.test(x))return "\ud83d\uddc2\ufe0f";
 if(/^(doc|docx|txt|md|xls|xlsx|ppt|pptx|csv)${'$'}/.test(x))return "\ud83d\udcc4";return "\ud83d\udcc1";}

function status(kind,title,detail,spin){
 clear();
 var h=el("div","hero"+(kind==="err"?" err":""));
 if(spin){var r=el("div","rings");r.appendChild(el("i"));r.appendChild(el("i"));var s=blobSvg();s.removeAttribute("id");r.appendChild(s);h.appendChild(r);}
 h.appendChild(el("h2","",title));h.appendChild(el("p","",detail||""));
 if(kind==="err"){var b=el("button","btn t",t("retry"));b.style.marginTop="16px";b.onclick=start;h.appendChild(b);}
 app.appendChild(h);
}

function post(){var lbl=(/iPhone|iPad/.test(navigator.userAgent)?"iPhone":/Android/.test(navigator.userAgent)?"Android":/Mac/.test(navigator.platform)?"Mac":/Win/.test(navigator.platform)?"Windows":"Browser");
 return fetch(base+"access"+(client?"?c="+encodeURIComponent(client):""),{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"label="+encodeURIComponent(lbl),credentials:"same-origin"});}
function poll(){return fetch(url("access"),{credentials:"same-origin"});}

function handle(j){
 if(j.sender)sender=j.sender;
 if(j.client)client=j.client;
 if(j.state==="approved")return load();
 if(j.state==="pending"){status("ok",t("waitT"),t("waitD",sender),true);pollTimer=setTimeout(function(){poll().then(function(r){return r.json();}).then(function(x){if(x.state==="none"){return post().then(function(r){return r.json();}).then(handle);}handle(x);}).catch(fail);},1200);return;}
 if(j.state==="denied")return status("err",t("denied"),t("deniedD"));
 if(j.state==="full")return status("err",t("full"),t("fullD"));
 if(j.state==="none")return post().then(function(r){return r.json();}).then(handle).catch(fail);
 fail();
}
function fail(){clearTimeout(pollTimer);status("err",t("stopped"),t("stoppedD"));}

function load(){
 fetch(url("manifest.json"),{credentials:"same-origin"}).then(function(r){if(r.status===403)return start();return r.json().then(render);}).catch(fail);
}
function render(m){
 clear();
 var h=el("h1","",t("head",m.sender||sender));app.appendChild(h);
 var total=0;m.files.forEach(function(f){if(f.size>0)total+=f.size;});
 app.appendChild(el("p","sub",m.files.length+" "+(m.files.length===1?t("file"):t("files"))+(total?" \u00b7 "+fmt(total):"")));
 if(!m.files.length)app.appendChild(el("p","sub",t("none")));
 m.files.forEach(function(f,i){
  var row=el("div","file");row.style.animationDelay=(Math.min(i,10)*45)+"ms";
  row.appendChild(el("div","ic",icon(f)));
  var meta=el("div","meta");meta.appendChild(el("div","nm",f.name));meta.appendChild(el("div","sz",fmt(f.size)));row.appendChild(meta);
  var act=el("div","row");var nameUrl="f/"+encodeURIComponent(f.id)+"/"+encodeURIComponent(f.name);
  if(/^(image|video|audio)\//.test(f.mime||"")&&!/svg/.test(f.mime)){var v=el("a","btn t",t("view"));v.href=url(nameUrl+"?inline=1");v.target="_blank";v.rel="noopener";act.appendChild(v);}
  var d=el("a","btn",t("dl"));d.href=url(nameUrl);d.setAttribute("download",f.name);act.appendChild(d);
  row.appendChild(act);app.appendChild(row);
 });
 if(m.files.length>1&&m.zip){zip.textContent=t("zip");zip.href=url("zip/share64-files.zip");zip.setAttribute("download","share64-files.zip");bar.hidden=false;}
 if(/Android/.test(navigator.userAgent)){var o=el("a","btn t",t("app"));o.style.marginTop="14px";o.href="intent://w?u="+encodeURIComponent(location.origin+base)+"#Intent;scheme=share64;package=com.share64.app;end";app.appendChild(o);}
}
function start(){clearTimeout(pollTimer);status("ok",t("connecting"),"",true);post().then(function(r){return r.json();}).then(handle).catch(fail);}
note.textContent=t("note");
start();
})();
</script>
</body>
</html>
"""
}
