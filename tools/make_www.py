"""把 design/base.css（基础样式）+ extra.css（Android 端样式）+ body.html 合成 App 页面。

注意：base.css 是样式的唯一来源，绝不能从已生成的 index.html 里再取样式，
否则每次生成都会把 extra.css 叠加一遍，导致文件越来越大、层叠规则错乱。
"""
import io
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_CSS = os.path.join(ROOT, "design", "base.css")
WWW = os.path.join(ROOT, "app", "src", "main", "assets", "www")
OUT = os.path.join(WWW, "index.html")


def main() -> None:
    with io.open(BASE_CSS, encoding="utf-8") as f:
        head = f.read()
    with io.open(os.path.join(WWW, "extra.css"), encoding="utf-8") as f:
        extra = f.read()
    with io.open(os.path.join(WWW, "body.html"), encoding="utf-8") as f:
        body = f.read()

    out = head + extra + "</style>\n</head>\n" + body
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("wrote", OUT, len(out), "chars")

    # 模型不再打进 APK：首次使用时联网下载（转写 / 翻译各自按需下载）
    model_dir = os.path.join(ROOT, "app", "src", "main", "assets", "model")
    if os.path.isdir(model_dir):
        for name in os.listdir(model_dir):
            try:
                os.remove(os.path.join(model_dir, name))
            except OSError:
                pass
    print("models: 不打进 APK（按需下载）")


if __name__ == "__main__":
    main()
