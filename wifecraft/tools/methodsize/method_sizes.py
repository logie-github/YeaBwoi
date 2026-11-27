# The biggest methods of the compiled classes: python3 -I method_sizes.py OUT_DIR/com/logie/wifecraft/ui 'SceneSessionScreenKt*' 12
# (the code length is the last instruction's offset + 1; the JVM's limit is 65,535). See README, "Scene screen layout".
import sys,subprocess,re,glob
# print the largest methods (code length) in classes matching a glob dir
d=sys.argv[1]; pat=sys.argv[2] if len(sys.argv)>2 else '*'
res=[]
for f in glob.glob(d+'/'+pat+'.class'):
    out=subprocess.run(['javap','-c','-p','-cp',d.split('/com/')[0],f],capture_output=True,text=True,env={'JAVA_TOOL_OPTIONS':''}).stdout
    cur=None;last=0
    for line in out.split('\n'):
        if re.match(r'^  \S.*\(.*\)',line) or re.match(r'^  \S.*static \{\}',line): 
            if cur: res.append((last+1,cur,f))
            cur=line.strip();last=0
        m=re.match(r'^\s+(\d+): ',line)
        if m: last=int(m.group(1))
    if cur: res.append((last+1,cur,f))
res.sort(reverse=True)
for n,c,f in res[:int(sys.argv[3]) if len(sys.argv)>3 else 12]: print(n,c[:110],f.split('/')[-1])
