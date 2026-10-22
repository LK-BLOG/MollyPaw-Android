"""准备本地 Maven 依赖，让 Android 编译**不用联网**。

背景：用户流量紧张，能省则省。
这些 jar 都能在本机找到现成的：
  - bouncycastle : Gradle 自带的 lib/plugins 里（Gradle 自己用它做签名）
  - junit       : Gradle 自带的 lib 里
  - hamcrest    : 跟着 junit 一起用（小A记 的 gradle 发行版里有）

用法：python tools/setup_local_deps.py
"""
import os
import shutil
import sys

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

GRADLE_HOME = r"D:\小A记\work\tools\gradle-8.9"
M2 = os.path.join(os.environ.get("USERPROFILE", ""), ".m2", "repository")

# (group, artifact, version, 候选源目录)
DEPS = [
    ("org.bouncycastle", "bcprov-jdk18on", "1.78.1",
     [os.path.join(GRADLE_HOME, "lib", "plugins")]),
    ("junit", "junit", "4.13.2",
     [os.path.join(GRADLE_HOME, "lib")]),
    # junit 4 依赖 hamcrest，缺了会报
    #   NoClassDefFoundError: org/hamcrest/SelfDescribing
    ("org.hamcrest", "hamcrest-core", "1.3",
     [os.path.join(GRADLE_HOME, "lib")]),
]

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>jar</packaging>
  <name>{artifact}</name>
</project>
"""


def install(group, artifact, version, source_dirs):
    dest_dir = os.path.join(M2, group.replace(".", os.sep), artifact, version)
    os.makedirs(dest_dir, exist_ok=True)
    jar_name = f"{artifact}-{version}.jar"
    dest_jar = os.path.join(dest_dir, jar_name)

    if not os.path.exists(dest_jar):
        found = None
        for d in source_dirs:
            cand = os.path.join(d, jar_name)
            if os.path.isfile(cand):
                found = cand
                break
        if not found:
            print(f"  跳过 {artifact}-{version}（本机找不到 jar）")
            return False
        shutil.copy2(found, dest_jar)
        print(f"  拷贝 {artifact}-{version}.jar")
    else:
        print(f"  已存在 {artifact}-{version}.jar")

    pom_path = os.path.join(dest_dir, f"{artifact}-{version}.pom")
    if not os.path.exists(pom_path):
        with open(pom_path, "w", encoding="utf-8") as f:
            f.write(POM.format(group=group, artifact=artifact, version=version))
        print(f"  生成 {artifact}-{version}.pom")
    return True


def main():
    print(f"本地仓库: {M2}\n")
    for group, artifact, version, sources in DEPS:
        install(group, artifact, version, sources)
    print("\n完成。现在可以用 --offline 编译与跑测试了。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
