# Sourced by POSIX sh scripts to export JAVA_HOME for a JDK 21.
# Order: a valid $JAVA_HOME, /usr/libexec/java_home -v 21, Homebrew openjdk@21.
logisim_java_home_valid() {
    [ -n "$1" ] && [ -x "$1/bin/java" ] && [ -x "$1/bin/javac" ]
}

logisim_resolve_java_home() {
    if logisim_java_home_valid "${JAVA_HOME:-}"; then
        return 0
    fi
    candidate=""
    if [ -x /usr/libexec/java_home ]; then
        candidate=$(/usr/libexec/java_home -F -v 21 2>/dev/null || true)
    fi
    if ! logisim_java_home_valid "$candidate"; then
        brew_prefix=""
        if command -v brew >/dev/null 2>&1; then
            brew_prefix=$(brew --prefix openjdk@21 2>/dev/null || true)
        fi
        for prefix in "$brew_prefix" /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
            [ -n "$prefix" ] || continue
            if logisim_java_home_valid "$prefix/libexec/openjdk.jdk/Contents/Home"; then
                candidate="$prefix/libexec/openjdk.jdk/Contents/Home"
                break
            fi
            if logisim_java_home_valid "$prefix"; then
                candidate="$prefix"
                break
            fi
        done
    fi
    if ! logisim_java_home_valid "$candidate"; then
        echo "JDK 21 não encontrado. Defina JAVA_HOME, registre um JDK 21 em /usr/libexec/java_home ou instale com 'brew install openjdk@21'." >&2
        return 1
    fi
    JAVA_HOME="$candidate"
    export JAVA_HOME
}

logisim_resolve_java_home
