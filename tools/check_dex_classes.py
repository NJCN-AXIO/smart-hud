import sys
import zipfile

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))
keys = [
    "Lno/nordicsemi/android/ble/BleManager;",
    "Lno/nordicsemi/android/ble/observer/ConnectionObserver;",
    "Lno/nordicsemi/android/ble/ValueChangedCallback;",
    "Lcom/hud/engine/NordicBleLink;",
    "Lcom/hud/engine/BleLink;",
    "Lcom/hud/engine/HudBle;",
]
for k in keys:
    print(k, k.encode() in blob)
