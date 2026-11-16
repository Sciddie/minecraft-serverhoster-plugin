
const $=id=>document.getElementById(id);
const TABS=[['status','Status'],['console','Konsole'],['players','Spieler'],['props','server.properties'],['config','config.yml'],['files','Dateien'],['logs','Logs'],['diag','Diagnose'],['setup','Einrichtung'],['tasks','Aufgaben'],['backup','Backup'],['settings','Einstellungen']];
let cur='status',es=null,cwd='',logLines=[],stTimer=null,srvName='child',actionPending=false,statusGeneration=0;
const chartHistory={cpuHost:[],cpuChild:[],rssHost:[],rssChild:[],cgroup:[],mspt:[]};
const CHART_HISTORY_LIMIT=60;
function toast(m,bad){const t=$('toast');t.textContent=m;t.style.borderColor=bad?'var(--r)':'var(--b)';t.style.display='block';clearTimeout(t._h);t._h=setTimeout(()=>t.style.display='none',4000)}
async function api(path,opt={}){opt.headers=Object.assign({'X-FL':'1'},opt.headers||{});opt.credentials='same-origin';const r=await fetch(path,opt);
 if(r.status===401){showLogin();throw new Error('Nicht angemeldet')}
 const ct=r.headers.get('content-type')||'';const d=ct.includes('json')?await r.json():await r.text();
 if(!r.ok){const m=(d&&d.error)||d||r.status;toast('Fehler: '+m,true);throw new Error(m)}return d}
const post=(p,body,raw)=>api(p,{method:'POST',body:raw?body:JSON.stringify(body||{})});
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function showLogin(){$('app').classList.add('hide');$('login').classList.remove('hide');if(es){es.close();es=null}clearInterval(stTimer)}
async function doLogin(){$('lerr').textContent='';const r=await fetch('/api/login',{method:'POST',headers:{'X-FL':'1'},body:JSON.stringify({password:$('pw').value})});
 if(r.ok){$('pw').value='';start()}else{const d=await r.json().catch(()=>({}));$('lerr').textContent=d.error||'Fehler'}}
async function logout(){await post('/api/logout').catch(()=>{});showLogin()}
function tab(t){cur=t;for(const [k] of TABS){const panel=$('t-'+k);if(panel)panel.classList.toggle('hide',k!==t)}
 if(t==='props'){loadGameplay();loadPropertiesPreview()}if(t==='config')loadConfigForm();if(t==='files')ls(cwd);if(t==='logs')loadLogs();if(t==='console')scrollCon();if(t==='diag')loadDiag();}
function fmtUp(s){const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return (d?d+'d ':'')+h+'h '+m+'m'}
function fmtSize(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';if(n<1073741824)return (n/1048576).toFixed(1)+' MB';return (n/1073741824).toFixed(2)+' GB'}
function lifecycleLabel(s){return s.setupRequired?'Einrichtung erforderlich':({STARTING:'Startet',RUNNING:'Läuft',STOPPING:'Stoppt',RESTART_WAIT:'Neustart ausstehend',STOPPED:'Gestoppt'}[s.lifecycle]||(s.running?'Läuft':'Gestoppt'))}
function updateChildActions(s){const state=s.lifecycle||(s.running?'RUNNING':'STOPPED'),locked=actionPending||s.setupRequired;
 $('child-start').classList.toggle('hide',state!=='STOPPED'||s.setupRequired);
 $('child-restart').classList.toggle('hide',state!=='RUNNING'||s.setupRequired);
 $('child-stop').classList.toggle('hide',!['STARTING','RUNNING','RESTART_WAIT'].includes(state)||s.setupRequired);
 for(const id of ['child-start','child-restart','child-stop'])$(id).disabled=locked;
 $('cin').disabled=$('console-send').disabled=state!=='RUNNING'||s.setupRequired;
 $('console-state').textContent=state==='RUNNING'&&!s.setupRequired?'':'Befehle sind verfügbar, sobald der Child vollständig gestartet ist.';
 $('child-action-status').textContent=actionPending?'Aktion wird angefordert …':state==='STOPPING'?'Child wird gestoppt …':state==='RESTART_WAIT'?'Automatischer Neustart ausstehend. Mit Stoppen abbrechen.':state==='STARTING'?'Child startet …':''}
async function status(){const generation=++statusGeneration;try{const s=await api('/api/status');if(generation!==statusGeneration)return;window.dispatchEvent(new CustomEvent('dashboard:setup-required',{detail:!!s.setupRequired}));updateChildActions(s);if($('host-connection'))$('host-connection').textContent='Spigot-Host verbunden';
 $('hstate').innerHTML='<span class="'+(s.lifecycle==='RUNNING'?'ok':'bad')+'">● '+esc(lifecycleLabel(s).toLowerCase())+'</span>';
 if(s.setupRequired&&!window.setupRedirected){window.setupRedirected=true;if(window.dashboardUi)window.dashboardUi.navigate('setup');else tab('setup')}
 const kv=[['Server',esc(s.id||'')],[ 'Status',esc(lifecycleLabel(s))],['Uptime',s.running?fmtUp(s.uptime):'–'],['PID',s.pid>0?s.pid:'–'],['CPU',s.cpu>=0&&s.cpu!=null?s.cpu+' %':'–'],['RAM (RSS)',s.rssMb>0?(s.rssMb/1024).toFixed(2)+' GB / '+esc(s.xmx):'–'],['Spieler',s.players.length],['Typ',s.setupRequired?'Noch nicht eingerichtet':esc(s.type+' '+s.mcVersion)],['Bind-Adresse',esc(s.address)],['TLS',s.tls?'https':'http'],['Letzter Zustand',esc(s.status)]];
 const hm=s.hostMemory||{},memUse=hm.usePercent>=0?hm.usePercent.toFixed(1)+'% ('+hm.cgroupCurrentMb+' / '+hm.cgroupLimitMb+' MB)':'cgroup nicht verfügbar';
 kv.push(['Container-Speicher',esc(memUse)],['Swap (Container)',hm.cgroupSwapMb>=0?hm.cgroupSwapMb+' MB':'–'],['Host MemAvailable',hm.availableMb>=0?hm.availableMb+' MB':'–'],['Host Swap frei / gesamt',hm.swapFreeMb>=0&&hm.swapTotalMb>=0?hm.swapFreeMb+' / '+hm.swapTotalMb+' MB':'–'],['cgroup-Limit-Treffer',hm.memoryMaxEvents>=0?hm.memoryMaxEvents:'–']);
 if(hm.recoveryPending)kv.push(['Speicherschutz','<span class="bad">Child-Autostart pausiert</span>']);
 if(hm.lastIncidentReport)kv.push(['Speicher-Notfallbericht','plugins/ServerhosterPlugin/'+esc(hm.lastIncidentReport)]);
 $('stats').innerHTML=kv.map(([k,v])=>`<div class="kv"><b>${k}</b><span>${v}</span></div>`).join('');
 const g=s.spigot||{},n=v=>v!=null&&v>=0,mb=v=>n(v)?(v>=1024?(v/1024).toFixed(2)+' GB':v+' MB'):'–';
 const rows=[['Prozess-RAM (RSS)',mb(g.rssMb),s.running?mb(s.rssMb):'–'],['Spigot-Heap belegt / committed / max',mb(g.heapUsedMb)+' / '+mb(g.heapCommittedMb)+' / '+mb(g.heapMaxMb),s.running?'nicht gemessen / – / Xmx-Decke '+esc(s.xmx):'–'],['CPU (% eines Kerns)',n(g.cpu)?g.cpu+' %':'–',s.running&&n(s.cpu)?s.cpu+' %':'–'],['Threads',n(g.threads)?g.threads:'–',s.running&&n(s.threads)?s.threads:'–'],['TPS',n(g.tps)?g.tps:'–',n(s.tps)?s.tps:'–'],['MSPT',n(g.mspt)?g.mspt+' ms (Tick-Intervall)':'–',n(s.mspt)?s.mspt+' ms':'–'],['Geladene Chunks / Entities',(g.chunks??'–')+' / '+(g.entities??'–')+' ('+(g.worlds??0)+' Welten)','–'],['PID',g.pid??'–',s.pid>0?s.pid:'–']];
 $('res').innerHTML='<thead><tr><th></th><th>Spigot</th><th>'+esc(s.type||'child')+'</th></tr></thead><tbody>'+rows.map(r=>`<tr><td><b>${r[0]}</b></td><td>${r[1]}</td><td>${r[2]}</td></tr>`).join('')+'</tbody>';
 updateCharts(g,s,hm);
 $('spark').innerHTML=spark(s.msptHistory||[]);
 $('online').innerHTML=s.players.length?s.players.map(p=>`<span class="pill">${esc(p)}</span>`).join(''):'<span class="muted">Niemand online</span>'}catch(e){if(generation===statusGeneration){for(const id of ['child-start','child-restart','child-stop','cin','console-send'])$(id).disabled=true;$('child-action-status').textContent='Status nicht verfügbar – Aktionen vorübergehend gesperrt.';$('console-state').textContent='Status nicht verfügbar – Befehle vorübergehend gesperrt.';$('hstate').textContent='● Status nicht verfügbar';if($('host-connection'))$('host-connection').textContent='Host-Verbindung nicht verfügbar'}}}
async function act(a){if(actionPending)return;actionPending=true;for(const id of ['child-start','child-restart','child-stop'])$(id).disabled=true;$('child-action-status').textContent='Aktion wird angefordert …';
 try{await post('/api/action?do='+a);toast({start:'Start angefordert',stop:'Stopp angefordert (wartet ggf. auf Startbereitschaft)',restart:'Neustart angefordert'}[a]);await new Promise(resolve=>setTimeout(resolve,500))}
 finally{actionPending=false;await status()}}
async function confirmStop(){const accepted=await window.dashboardUi?.confirm({title:'Server wirklich stoppen?',description:'Der Child-Server wird regulär beendet. Der Spigot-Host bleibt aktiv.',confirmLabel:'Child stoppen',destructive:true});if(accepted)act('stop')}
function cls(l){return /\bERROR\b|Exception|FATAL/.test(l)?'E':/\bWARN/.test(l)?'W':l.startsWith('[ServerhosterPlugin]')||l.startsWith('> ')?'L':''}
function addLine(l){const c=$('con'),d=document.createElement('div');d.textContent=l;const k=cls(l);if(k)d.className=k;c.appendChild(d);while(c.childElementCount>2000)c.firstChild.remove();if($('auto').checked)c.scrollTop=c.scrollHeight}
function scrollCon(){const c=$('con');c.scrollTop=c.scrollHeight}
function stream(){if(es)es.close();$('con').innerHTML='';es=new EventSource('/api/console/stream');es.onmessage=e=>addLine(JSON.parse(e.data));
 es.onerror=()=>{es.close();es=null;setTimeout(async()=>{try{await api('/api/status');stream()}catch(e){}},3000)}}
async function cmd(c){const r=await post('/api/cmd',{command:c});if(!r.ok)toast('Server läuft nicht',true);else if(cur!=='console')toast('Gesendet: '+c)}
function sendCmd(){const v=$('cin').value.trim();if(!v)return;cmd(v);$('cin').value=''}
async function loadGameplay(){const d=await api('/api/properties/gameplay');$('prop-motd').value=d.motd||'';$('prop-difficulty').value=d.difficulty||'normal';$('prop-whitelist').value=d.whitelist?'true':'false'}
async function saveGameplay(){await post('/api/properties/gameplay',{motd:$('prop-motd').value,difficulty:$('prop-difficulty').value,whitelist:$('prop-whitelist').value==='true'});toast('Gespeichert. Wirkt beim nächsten Child-Start.');loadPropertiesPreview()}
async function loadConfigForm(){const d=await api('/api/config/form');if(window.dashboardEditors)window.dashboardEditors.set('config',d.yaml||'','config.yml');else $('config').value=d.yaml||''}
async function saveConfigForm(){const yaml=window.dashboardEditors?window.dashboardEditors.get('config'):$('config').value;const r=await post('/api/config/form',{yaml});toast(r.hint||'Gespeichert');loadConfigForm()}
async function loadPropertiesPreview(){const rows=await api('/api/properties/preview'),body=$('propdiff');body.innerHTML=rows.length?rows.map(p=>`<tr><td><code>${esc(p.key)}</code></td><td>${p.present?esc(p.current):'<span class="muted">nicht gesetzt</span>'}</td><td><code>${esc(p.next)}</code></td><td>${esc(p.action)}</td></tr>`).join(''):'<tr><td colspan="4" class="muted">Keine erzwungenen Änderungen.</td></tr>'}
function jp(a,b){return a?a+'/'+b:b}
async function ls(p){const d=await api('/api/fs/list?path='+encodeURIComponent(p||''));cwd=d.path==='.'?'':d.path;
 const parts=cwd?cwd.split('/'):[];let crumbs=`<a onclick="ls('')">${esc(srvName)}</a>`,acc='';for(const x of parts){acc=jp(acc,x);crumbs+=` / <a onclick="ls('${esc(acc)}')">${esc(x)}</a>`}$('cwd').innerHTML=crumbs;
 let h=cwd?`<tr><td><a onclick="ls('${esc(parts.slice(0,-1).join('/'))}')">⬆ ..</a></td><td></td><td></td><td></td></tr>`:'';
 for(const e of d.entries){const p=jp(cwd,e.name),ep=esc(JSON.stringify(p)).replace(/'/g,'&#39;');
  h+=`<tr><td>${e.dir?'📁':'📄'} <a onclick='${e.dir?'ls':'openFile'}(${ep})'>${esc(e.name)}</a></td><td class="muted">${e.dir?'':fmtSize(e.size)}</td><td class="muted">${new Date(e.mtime).toLocaleString('de-DE')}</td>
  <td style="text-align:right">${e.dir?'':`<a href="/api/fs/download?path=${encodeURIComponent(p)}">Download</a> · `}<a onclick='ren(${ep})'>Umbenennen</a> · <a class="dan" onclick='del(${ep})'>Löschen</a></td></tr>`}
 $('flist').innerHTML=h}
async function openFile(p){if(/\.(jar|zip|mca|dat|png|gz|db|lock)$/i.test(p)&&!p.endsWith('.log.gz')){const accepted=await window.dashboardUi?.confirm({title:'Datei als Text öffnen?',description:'Diese Datei könnte binär sein. Die Textansicht ist möglicherweise nicht lesbar.',confirmLabel:'Trotzdem öffnen'});if(!accepted)return}
 const text=await api('/api/fs/read?path='+encodeURIComponent(p));$('epath').textContent=p;$('editor').classList.remove('hide');if(window.dashboardEditors)window.dashboardEditors.set('etext',text,p);else $('etext').value=text;$('editor').scrollIntoView()}
async function saveFile(){const p=$('epath').textContent;if(p.endsWith('.gz')){toast('.gz kann nicht gespeichert werden',true);return}const text=window.dashboardEditors?window.dashboardEditors.get('etext'):$('etext').value;await post('/api/fs/write?path='+encodeURIComponent(p),text,true);toast('Gespeichert');ls(cwd)}
async function ren(p){const n=await window.dashboardUi?.prompt({title:'Datei oder Ordner umbenennen',description:'Gib den neuen Namen ein.',placeholder:'Neuer Name',initialValue:p,confirmLabel:'Umbenennen'});if(!n||n===p)return;await post('/api/fs/rename',{from:p,to:n});const ep=$('epath').textContent;if(ep===p||ep.startsWith(p+'/')){$('epath').textContent=n+ep.slice(p.length);window.dashboardEditors?.setPath('etext',$('epath').textContent)}toast('Umbenannt');ls(cwd)}
async function del(p){const name=p.split('/').pop();const typed=await window.dashboardUi?.prompt({title:'Dauerhaft löschen?',description:'„'+p+'“ wird mit allem Inhalt gelöscht. Gib zur Bestätigung den Namen „'+name+'“ ein. Eine Welt wird nicht still gelöscht.',placeholder:name,confirmLabel:'Dauerhaft löschen',destructive:true,requiredValue:name});if(typed!==name)return;await post('/api/fs/delete',{path:p,confirm:name});const ep=$('epath').textContent;if(ep===p||ep.startsWith(p+'/')){$('editor').classList.add('hide');$('epath').textContent=''}toast('Gelöscht');ls(cwd)}
async function mkdir(){const n=await window.dashboardUi?.prompt({title:'Neuen Ordner erstellen',description:'Der Ordner wird im aktuellen Verzeichnis angelegt.',placeholder:'Ordnername',confirmLabel:'Ordner erstellen'});if(!n)return;await post('/api/fs/mkdir?path='+encodeURIComponent(jp(cwd,n)));ls(cwd)}
async function newFile(){const n=await window.dashboardUi?.prompt({title:'Neue Datei erstellen',description:'Die neue leere Textdatei wird im aktuellen Verzeichnis angelegt.',placeholder:'Dateiname',confirmLabel:'Datei erstellen'});if(!n)return;await post('/api/fs/write?path='+encodeURIComponent(jp(cwd,n)),'',true);ls(cwd);openFile(jp(cwd,n))}
async function upload(files){for(const f of files){let ow='';try{await api('/api/fs/upload?path='+encodeURIComponent(cwd)+'&name='+encodeURIComponent(f.name),{method:'POST',body:f})}
 catch(e){if(String(e.message).includes('existiert')&&await window.dashboardUi?.confirm({title:'Datei überschreiben?',description:f.name+' existiert bereits. Soll die vorhandene Datei ersetzt werden?',confirmLabel:'Überschreiben',destructive:true}))await api('/api/fs/upload?overwrite=1&path='+encodeURIComponent(cwd)+'&name='+encodeURIComponent(f.name),{method:'POST',body:f});else continue}toast('Hochgeladen: '+f.name)}$('up').value='';ls(cwd)}
async function loadLogs(){try{const d=await api('/api/fs/list?path=logs');const fs=d.entries.filter(e=>!e.dir).sort((a,b)=>b.mtime-a.mtime);const sel=$('logsel'),prev=sel.value;
 sel.innerHTML=fs.map(e=>`<option>${esc(e.name)}</option>`).join('');if(prev&&fs.some(e=>e.name===prev))sel.value=prev;else if(fs.some(e=>e.name==='latest.log'))sel.value='latest.log';loadLog()}catch(e){$('logv').textContent='Kein logs-Ordner'}}
async function loadLog(){const n=$('logsel').value;if(!n)return;const q=$('lfilter').value,level=$('llevel').value;
 const d=await api('/api/logs?file='+encodeURIComponent(n)+'&level='+encodeURIComponent(level)+'&q='+encodeURIComponent(q));
 logLines=d.lines||[];$('ltrunc').textContent=d.truncated?'gekürzt':'';renderLog();const v=$('logv');v.scrollTop=v.scrollHeight}
function renderLog(){const f=$('lfilter').value.toLowerCase();const v=$('logv');v.innerHTML='';const fr=document.createDocumentFragment();
 for(const l of logLines){if(f&&!l.toLowerCase().includes(f))continue;const d=document.createElement('div');d.textContent=l;const k=cls(l);if(k)d.className=k;fr.appendChild(d)}v.appendChild(fr)}
function spark(vals){const a=(vals||[]).filter(v=>v!=null&&v>=0);if(a.length<2)return '';const w=420,h=42,max=Math.max(50,...a);
 const pts=a.map((v,i)=>((i/(a.length-1))*w).toFixed(1)+','+(h-(v/max)*(h-2)).toFixed(1)).join(' ');
 return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><polyline fill="none" stroke="#4f8cff" stroke-width="1.5" points="${pts}"/></svg>`}
function pushChartValue(key,value){const a=chartHistory[key],n=value==null||value===''?NaN:Number(value);a.push(Number.isFinite(n)&&n>=0?n:null);if(a.length>CHART_HISTORY_LIMIT)a.shift()}
function updateCharts(host,child,memory){
 pushChartValue('cpuHost',host.cpu);pushChartValue('cpuChild',child.cpu);
 pushChartValue('rssHost',host.rssMb);pushChartValue('rssChild',child.rssMb);
 pushChartValue('cgroup',memory.usePercent);pushChartValue('mspt',child.mspt);
 const items=[
  ['CPU-Auslastung','Spigot-Host und Child-Prozess · % eines Kerns',[['Spigot',chartHistory.cpuHost,'#a78bfa'],['Child',chartHistory.cpuChild,'#34d399']],'%',100],
  ['Prozess-RAM (RSS)','Residenter Speicher · Spigot-Host und Child',[['Spigot',chartHistory.rssHost,'#a78bfa'],['Child',chartHistory.rssChild,'#34d399']],' MB',null],
  ['Container-Speicher','cgroup-Belegung · Grenzwert',[['Container',chartHistory.cgroup,'#c084fc']],'%',100],
  ['Child-Tickzeit','Vanilla tick query · MSPT',[['Child',chartHistory.mspt,'#38bdf8']],' ms',null]
 ];
 $('overviewCharts').innerHTML=items.map(([title,caption,series,unit,max])=>chartCard(title,caption,series,unit,max)).join('');
}
function chartCard(title,caption,series,unit,limit){
 const w=640,h=170,pad=8,innerW=w-pad*2,innerH=h-pad*2;
 const all=series.flatMap(s=>s[1].map((v,i)=>v==null?null:{value:v,index:i})).filter(Boolean);
 const sampleCount=Math.max(1,...series.map(s=>s[1].length));
 if(all.length<2)return `<article class="chart-card"><div class="chart-title">${title}</div><div class="chart-caption">${caption}</div><div class="chart-empty">${sampleCount>1&&!all.length?'Keine Messwerte verfügbar':'Messwerte werden gesammelt …'}</div></article>`;
 const high=Math.max(limit||1,Math.ceil(Math.max(...all.map(p=>p.value))*1.15));
 const low=0;
 const y=v=>pad+innerH-(Math.max(low,Math.min(high,v)-low)/(high-low||1))*innerH;
 const x=i=>pad+(sampleCount<2?innerW:(i/(sampleCount-1))*innerW);
 const grid=[0,.5,1].map(t=>{const gy=pad+innerH*t;return `<line class="chart-gridline" x1="${pad}" y1="${gy}" x2="${w-pad}" y2="${gy}"/>`}).join('');
 const paths=series.map(([name,values,color])=>{
  const segments=[];let points=[];
  for(let i=0;i<values.length;i++){if(values[i]==null){if(points.length>1)segments.push(points);points=[]}else points.push([x(i),y(values[i])])}
  if(points.length>1)segments.push(points);
  return segments.map(points=>{const d=points.map((pt,i)=>(i?'L':'M')+pt[0].toFixed(1)+' '+pt[1].toFixed(1)).join(' '),last=points[points.length-1];
   return `<path class="chart-area" d="${d} L ${last[0].toFixed(1)} ${pad+innerH} L ${points[0][0].toFixed(1)} ${pad+innerH} Z" fill="${color}"/><path class="chart-line" d="${d}" stroke="${color}"><title>${name}: ${values.at(-1)?.toFixed(1)??'–'}${unit}</title></path>`}).join('')
 }).join('');
 const legend=series.map(([name,values,color])=>{const v=values.at(-1);return `<span class="chart-legend"><i style="background:${color}"></i>${name}<b>${v==null?'–':v.toFixed(1)+unit}</b></span>`}).join('');
 return `<article class="chart-card"><div class="chart-title">${title}</div><div class="chart-caption">${caption}</div><div class="chart-legend-row">${legend}</div><svg class="metric-chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="${title}">${grid}${paths}</svg></article>`;
}
async function loadDiag(){const d=await api('/api/diag'),hm=d.hostMemory||{};$('diagraw').textContent=JSON.stringify(d,null,2);
 const metric=(v,unit='')=>typeof v==='number'&&Number.isFinite(v)&&v>=0?`${v.toFixed(v%1?1:0)}${unit}`:'Nicht verfügbar';
 const row=(title,value,help)=>`<div class="kv"><b>${esc(title)}</b><span>${esc(value)}<br><small>${esc(help)}</small></span></div>`;
 $('diag').innerHTML=[
  row('Child-Server',d.running?'Läuft':'Gestoppt',d.status||'Aktueller Prozesszustand.'),
  row('Ticks pro Sekunde',d.running?metric(d.tps,' TPS'):'Nicht verfügbar','20 TPS sind das Minecraft-Ziel; dauerhaft weniger kann auf Überlastung hindeuten.'),
  row('Zeit pro Tick',d.running?metric(d.mspt,' ms'):'Nicht verfügbar','Unter 50 ms pro Tick ist das Ziel für 20 TPS. Einzelne Spitzen sind möglich.'),
  row('Container-Speicher',hm.usePercent>=0?`${metric(hm.usePercent,' %')} (${metric(hm.cgroupCurrentMb,' MiB')} von ${metric(hm.cgroupLimitMb,' MiB')})`:'Nicht verfügbar','Gesamter Containerverbrauch einschließlich Host, Child und weiterer Prozesse; nicht nur Java-Heap.'),
  row('Child-Speicher (RSS)',d.running?metric(d.rssMb,' MiB'):'Nicht verfügbar','Tatsächlich im RAM befindlicher Speicher des Child-Prozesses, nicht seine Heap-Grenze.'),
  row('Host-Speicher (RSS)',metric(d.hostRssMiB,' MiB'),'Speicher des äußeren Spigot-Prozesses. Host und Child haben getrennte JVMs.'),
  row('Child-Dateien',typeof d.diskBytes==='number'&&d.diskBytes>=0?fmtSize(d.diskBytes):'Nicht verfügbar',d.diskPartial?'Die Erfassung ist unvollständig.':'Größe der erfassten Dateien im Child-Ordner.'),
  row('Speicherschutz',hm.recoveryPending?'Child-Neustart pausiert':'Keine aktive Schutzpause gemeldet','Bei bestätigtem kritischem Containerdruck kann der Child gestoppt werden.'),
  row('Nächster Schritt',hm.recoveryPending?'Speicherverbrauch prüfen und Entlastung abwarten':hm.usePercent>=85?'Freien Container-Speicher prüfen':d.running&&d.mspt>=50?'Tickzeit und Serverlast weiter beobachten':d.running?'Messwerte über einen längeren Zeitraum beobachten':'Bei unerwartetem Stopp die Konsole und Logs prüfen','Die Hinweise beruhen auf aktuellen Einzelwerten und ersetzen keine längere Analyse.')
 ].join('')}
async function loadCrashes(){const d=await api('/api/crashes');const files=Array.isArray(d)?d:(d.files||[]);$('crashsel').innerHTML=files.map(n=>`<option>${esc(n)}</option>`).join('');$('diagout').textContent=files.join('\n')||'–'}
async function readCrash(){const n=$('crashsel').value;if(!n){toast('keine Datei',true);return}$('diagout').textContent=await api('/api/crashes/read?file='+encodeURIComponent(n))}
function start(){$('login').classList.add('hide');$('app').classList.remove('hide');status();clearInterval(stTimer);stTimer=setInterval(status,3000);stream();window.dispatchEvent(new Event('dashboard:started'))}
fetch('/api/status',{credentials:'same-origin'}).then(r=>r.ok?start():showLogin()).catch(showLogin).finally(()=>window.dispatchEvent(new Event('dashboard:auth-ready')));
