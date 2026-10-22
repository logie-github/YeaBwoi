import json, urllib.request, concurrent.futures
urls=[
 'https://archive.org/metadata/lrdpsychochains-legion-7.3.5.26972-client',
 'http://ashamanecore.com/dicpics/LegionClient.zip',
 'https://ashamanecore.com/dicpics/LegionClient.zip',
 'https://cdn.wowarriors.de/wow/cdnve/cdns',
 'https://blzddist1-a.akamaihd.net/tpr/wow/config/3b/05/3b0517b51edbe0b96f6ac5ea7eaaed38',
 'http://level3.blizzard.com/tpr/wow/config/3b/05/3b0517b51edbe0b96f6ac5ea7eaaed38',
]
def check(url):
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={'Range':'bytes=0-1023'}),timeout=30) as r:
   body=r.read(2000000 if '/metadata/' in url else 1024)
   result={'url':url,'final':r.url,'status':r.status,'headers':dict(r.headers),'sample':repr(body[:200])}
   if '/metadata/' in url:
    data=json.loads(body);result['files']=[{k:f.get(k) for k in ['name','size','sha1','md5','format','private']} for f in data.get('files',[])];result['metadata']=data.get('metadata')
   print(json.dumps(result),flush=True)
 except Exception as e: print(json.dumps({'url':url,'error':str(e)}),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: list(pool.map(check,urls))
