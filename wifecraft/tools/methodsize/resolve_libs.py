# Downloads the AndroidX/Compose/coroutines jars the app compiles against (aars unpacked to classes.jar) into ./libs,
# from Google Maven and Maven Central's mirror: python3 -I resolve_libs.py androidx.compose.ui:ui-android:1.8.0 ... (the
# versions are the app's: compose-bom 2025.04.00, activity-compose 1.9.3, lifecycle 2.8.7; the explicit ones win over
# what the poms ask for). Used with compile_app.sh to measure the bytecode of the scene screen without the Android SDK.
import sys,os,re,urllib.request,zipfile,io,xml.etree.ElementTree as ET
REPOS=["https://maven.google.com/","https://maven-central.storage-download.googleapis.com/maven2/"]
LIB="libs"
seen={}
def get(path):
    for r in REPOS:
        try:
            return urllib.request.urlopen(r+path,timeout=60).read()
        except Exception as e: pass
    return None
def strip(root):
    for e in root.iter():
        if '}' in e.tag: e.tag=e.tag.split('}',1)[1]
    return root
def pom(g,a,v):
    p=g.replace('.','/')+f"/{a}/{v}/{a}-{v}.pom"
    d=get(p)
    return strip(ET.fromstring(d)) if d else None
pinned={}
def resolve(g,a,v):
    k=(g,a)
    v=pinned.get(k,v)
    if k in seen: return
    seen[k]=v
    root=pom(g,a,v)
    if root is None: print("NOPOM",g,a,v); return
    pk=(root.findtext('packaging') or 'jar')
    base=g.replace('.','/')+f"/{a}/{v}/{a}-{v}"
    out=f"{LIB}/{g}_{a}_{v}.jar"
    if not os.path.exists(out):
        if pk=='aar':
            d=get(base+".aar")
            if d: z=zipfile.ZipFile(io.BytesIO(d)); open(out,'wb').write(z.read('classes.jar') if 'classes.jar' in z.namelist() else b'PK\x05\x06'+b'\0'*18)
            else: print("NOAAR",g,a,v)
        else:
            d=get(base+".jar")
            if d: open(out,'wb').write(d)
            else:
                d=get(base+".aar")
                if d: z=zipfile.ZipFile(io.BytesIO(d)); open(out,'wb').write(z.read('classes.jar') if 'classes.jar' in z.namelist() else b'PK\x05\x06'+b'\0'*18)
                else: print("NOJAR",g,a,v)
    props={}
    for p in root.findall('properties/*'): props[p.tag]=p.text
    props['project.version']=v
    def sub(s):
        return re.sub(r'\$\{([^}]+)\}',lambda m:props.get(m.group(1),m.group(0)),s or '')
    mgd={}
    for d in root.findall('dependencyManagement/dependencies/dependency'):
        mgd[(sub(d.findtext('groupId')),sub(d.findtext('artifactId')))]=sub(d.findtext('version'))
    for d in root.findall('dependencies/dependency'):
        sc=d.findtext('scope') or 'compile'
        if sc not in('compile','runtime'): continue
        if d.findtext('optional')=='true': continue
        dg=sub(d.findtext('groupId')); da=sub(d.findtext('artifactId'))
        dv=sub(d.findtext('version')) or mgd.get((dg,da))
        if not dv: print("NOVER",dg,da); continue
        dv=re.sub(r'[\[\]\(\)]','',dv).split(',')[0]
        if dg.startswith('org.jetbrains.kotlin') and da.startswith('kotlin-stdlib'): continue
        resolve(dg,da,dv)
for c in sys.argv[1:]:
    g,a,v=c.split(':'); pinned[(g,a)]=v
for c in sys.argv[1:]:
    g,a,v=c.split(':'); resolve(g,a,v)
