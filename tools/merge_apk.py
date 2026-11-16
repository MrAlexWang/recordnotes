"""把 classes.dex 与 native so 塞进 aapt2 生成的 APK。

用法: python tools/merge_apk.py <base.apk> <classes.dex> <libs dir> <out.apk>
"""
import os
import shutil
import sys
import zipfile

ZIP_STORED = zipfile.ZIP_STORED


def main() -> None:
    base, dex, libs, out = sys.argv[1:5]
    shutil.copyfile(base, out)
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
        for abi in os.listdir(libs):
            d = os.path.join(libs, abi)
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                if not name.endswith(".so"):
                    continue
                # so 必须 stored（不压缩），否则部分机型加载失败
                with open(os.path.join(d, name), "rb") as f:
                    data = f.read()
                info = zipfile.ZipInfo("lib/" + abi + "/" + name, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_STORED
                z.writestr(info, data)
                print("added", "lib/" + abi + "/" + name, len(data) // 1024, "KB")
    print("merged ->", out, os.path.getsize(out) // 1048576, "MB")


if __name__ == "__main__":
    main()
