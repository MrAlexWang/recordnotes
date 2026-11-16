"""从 sherpa-onnx AAR 中取出 classes.jar 与 arm64-v8a 的 so。"""
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAR = os.path.join(ROOT, "build-dl", "sherpa.aar")
OUT = os.path.join(ROOT, "build", "libs")


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    with zipfile.ZipFile(AAR) as z:
        with open(os.path.join(OUT, "sherpa.jar"), "wb") as f:
            f.write(z.read("classes.jar"))
        so = "jni/arm64-v8a/libsherpa-onnx-jni.so"
        dst = os.path.join(OUT, "arm64-v8a")
        os.makedirs(dst, exist_ok=True)
        with z.open(so) as src, open(os.path.join(dst, "libsherpa-onnx-jni.so"), "wb") as f:
            shutil.copyfileobj(src, f)
    print("aar ready:", os.listdir(OUT))

if __name__ == "__main__":
    main()
