# Sourced: sets MENUS_CP to the Evolution JAR ($1), the Mobile core JAR and the Kotlin standard library.
menus_kotlin_stdlib=${KOTLIN_STDLIB_JAR:-$(python3 -c '
from pathlib import Path
import os,re
version=re.search(r"kotlin\(\"multiplatform\"\) version \"([^\"]+)\"",Path("build.gradle.kts").read_text()).group(1)
cache=Path(os.environ.get("GRADLE_USER_HOME",Path.home()/".gradle"))
print(next((cache/"caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"/version).glob("*/*.jar")))
')}
MENUS_CP="$1:${MENUS_CORE_JAR:-core/build/libs/core-jvm.jar}:$menus_kotlin_stdlib"
