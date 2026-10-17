#!/usr/bin/env bash
# Offline check suite for mc-agent-mcp.
#
# Everything that can be verified without a GPU/account:
#   1. gradle build (compile + mixin remap + jar)
#   2. MCP protocol/transport regression      (ProtocolTest)
#   3. SSE push + concurrent POST             (SseSelfTest)
#   4. block codec formats (rle/layers/json)  (CodecTest)
#   5. change-detection logic                 (DiffLogicTest)
#   6. tool argument validation + chat log    (ArgsTest)
#   7. transfer planning + crafting grid fit  (InventoryMathTest)
#   8. permissions, rate limits, config       (PolicyTest)
#   9. reflex weapon/food/armor choices       (ReflexMathTest)
#  10. every tool's advertised JSON Schema    (ToolSchemaTest)
#
# In-game behaviour (mixin application, scanning a live world, SSE from a real
# game) still needs an actual Minecraft launch; see README.
set -u

cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/.harness/classes"
JARS=""
GHOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GSON=$(find "$GHOME/caches/modules-2" -path '*/com.google.code.gson/gson/*' -name 'gson-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)
SLF4J=$(find "$GHOME/caches/modules-2" -name 'slf4j-api-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)

# A JDK 21 for both Gradle and the standalone checks: ./.jdk21, else $JAVA_HOME.
if [ -x "$ROOT/.jdk21/bin/javac" ]; then
	export JAVA_HOME="$ROOT/.jdk21"
fi
JDK21="${JAVA_HOME:-}/bin"
if [ ! -x "$JDK21/javac" ]; then
	echo "!! JDK 21 not found at $JDK21 (needed for the standalone checks)" >&2
	exit 2
fi

FAILED=0

echo "== 1/10 gradle build =="
if ./gradlew build --console=plain -q; then
	echo "   build ok"
else
	echo "   BUILD FAILED"
	exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT"

run_test() { # name, source, classpath
	local name="$1" src="$2" cp="$3"
	echo "== $name =="
	if ! "$JDK21/javac" -nowarn -d "$OUT" -cp "$cp" "checks/$src"; then
		echo "   COMPILE FAILED"
		FAILED=1
		return
	fi
	"$JDK21/java" -cp "$OUT:$cp" "${src%.java}" 2>&1 | grep -v '^SLF4J'
	if [ "${PIPESTATUS[0]}" -ne 0 ]; then
		FAILED=1
	fi
}

run_test "2/10 MCP protocol"      ProtocolTest.java   "build/classes/java/main:$GSON:$SLF4J"
run_test "3/10 SSE push"          SseSelfTest.java    "build/classes/java/main:$GSON:$SLF4J"
run_test "4/10 block codec"       CodecTest.java      "build/classes/java/main:$GSON"
run_test "5/10 change detection"  DiffLogicTest.java  "build/classes/java/main:$GSON"
run_test "6/10 tool arguments"    ArgsTest.java       "build/classes/java/main:$GSON"
run_test "7/10 inventory logic"   InventoryMathTest.java "build/classes/java/main"
run_test "8/10 permissions+limits" PolicyTest.java   "build/classes/java/main:$GSON:$SLF4J"
run_test "9/10 reflex decisions"  ReflexMathTest.java "build/classes/java/main"

# Loading the client tools requires the Minecraft classes (ModDevGradle's merged NeoForge jar).
MC=$(find "$ROOT/build/moddev/artifacts" -name 'neoforge-*-merged.jar' 2>/dev/null | sort -V | tail -1)
DFU=$(find "$GHOME/caches/modules-2" -path '*/com.mojang/datafixerupper/*' -name 'datafixerupper-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)
run_test "10/10 tool schemas"      ToolSchemaTest.java "build/classes/java/main:$MC:$DFU:$GSON:$SLF4J"

echo
if [ "$FAILED" -eq 0 ]; then
	echo "ALL CHECKS PASSED"
else
	echo "SOME CHECKS FAILED"
fi
exit "$FAILED"
