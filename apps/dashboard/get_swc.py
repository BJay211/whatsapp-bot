import urllib.request, tarfile, io, os, shutil
url='https://registry.npmjs.org/@next/swc-win32-x64-msvc/-/swc-win32-x64-msvc-15.5.28.tgz'
req=urllib.request.Request(url, headers={'User-Agent':'node'})
resp=urllib.request.urlopen(req, timeout=900)
data=resp.read()
print('len', len(data))
f=io.BytesIO(data)
with tarfile.open(fileobj=f, mode='r:gz') as tar:
    for m in tar.getmembers():
        if m.name.endswith('.node'):
            print(m.name, m.size)
            content=tar.extractfile(m).read()
            target=os.path.join('node_modules','@next','swc-win32-x64-msvc-15.5.28','next-swc.win32-x64-msvc.node')
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target,'wb') as w:
                w.write(content)
            target2=os.path.join('node_modules','next','next-swc-fallback','@next','swc-win32-x64-msvc','next-swc.win32-x64-msvc.node')
            os.makedirs(os.path.dirname(target2), exist_ok=True)
            shutil.copy2(target, target2)
            print('saved')
            break
