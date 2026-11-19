import sys, zipfile
apk = sys.argv[1]
z = zipfile.ZipFile(apk)
print("testzip:", z.testzip())
print("entries:", len(z.namelist()))
print("dex:", [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")])
