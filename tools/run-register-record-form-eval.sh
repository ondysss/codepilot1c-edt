#!/usr/bin/env bash
# Парный eval формы записи регистра сведений и удаления формы без force.
#
# Дефект 1: add_metadata_child {parent_fqn: InformationRegister.X, child_kind: Form, name: ФормаЗаписи,
# form_usage: OBJECT, managed: true, set_as_default: true} ответил «Form role=LIST, defaultAssigned=true»: в .mdo —
# defaultListForm, в Form.form — форма списка. Шаг validate не сворачивал опции формы в properties, мутация применяла
# payload из токена без роли, роль по имени «ФормаЗаписи» не угадывалась, умолчание регистра — LIST. И даже дошедший
# OBJECT давал бы генератору EDT тип OBJECT: у регистра сведений роль — defaultRecordForm, тип генератора — RECORD.
# Дефект 2: delete_metadata формы без force — METADATA_DELETE_CONFLICT по «…Form.<Имя>.Form#mdForm», ссылке её
# собственного содержимого. Воспроизведено на EDT 2025.2.3.
#
# Использование (EDT_HOME — каталог Eclipse установленной 1C:EDT, тот же, что -Dedt.home; JDK берётся из
# JAVA_HOME, без него — javac/java из PATH; JUnit, Hamcrest и компилятор ECJ — из plugins/ самой EDT):
#   EDT_HOME=... bash tools/run-register-record-form-eval.sh                      # против target/classes, ожидается зелёный
#   EDT_HOME=... bash tools/run-register-record-form-eval.sh --baseline <каталог> # против сборки ДО фикса:
#                                                                                 # обязан покраснеть поимённо на симптоме
#   EDT_HOME=... bash tools/run-register-record-form-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   EDT_HOME=... bash tools/run-register-record-form-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# Тесты бьют рефлексией по настоящим методам и не ссылаются на классы фикса (роль RECORD берётся строкой) — один
# бинарник идёт против сборки до фикса и после.
#
# Чего этот eval НЕ проверяет: что генератор EDT строит из типа RECORD форму с измерениями и ресурсами, запись
# defaultRecordForm в .mdo, выгрузку и реальный граф ссылок BM. Это закрывает только живая проверка вызовом тула через
# MCP на установленной сборке.
set -euo pipefail

# Пути к JVM-инструментам обязаны быть в форме Windows: Git Bash отдаёт POSIX (/f/..., /c/...),
# javac и java такой classpath молча принимают и не находят по нему ни одного класса.
win_path() { command -v cygpath >/dev/null 2>&1 && cygpath -m "$1" || printf '%s' "$1"; }
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
    *) CP_SEP=':' ;;
esac

REPO_ROOT="$(win_path "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)")"
SRC="$REPO_ROOT/bundles/com.codepilot1c.core/src"
TESTS="$REPO_ROOT/bundles/com.codepilot1c.core.tests/src"

if [ -z "${EDT_HOME:-}" ]; then
    echo "Задайте EDT_HOME: каталог Eclipse установленной 1C:EDT (тот же, что -Dedt.home для сборки)" >&2
    exit 2
fi
EDT_HOME="$(win_path "$EDT_HOME")"
[ -d "$EDT_HOME/plugins" ] || { echo "НЕ НАЙДЕНО: $EDT_HOME/plugins" >&2; exit 2; }
if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$(win_path "$JAVA_HOME")/bin/javac"
    JAVA="$(win_path "$JAVA_HOME")/bin/java"
else
    JAVAC=javac
    JAVA=java
fi
# ECJ — тот же компилятор, которым собирает Tycho: только он принимает EdtMetadataService.java.
ECJ="${ECJ:-$(ls "$EDT_HOME"/plugins/org.eclipse.jdt.core.compiler.batch_*.jar 2>/dev/null | head -1 || true)}"

# Каталог внутри репозитория, а не mktemp: в Git Bash mktemp отдаёт POSIX-путь, которого JVM не видит.
WORK="$REPO_ROOT/target/register-record-form-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

MODE="run"
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
while [ $# -gt 0 ]; do
    case "$1" in
        --sabotage) MODE="sabotage"; shift ;;
        --classes) CLASSES="$(win_path "$2")"; MODE="classes"; shift 2 ;;
        --baseline) CLASSES="$(win_path "$2")"; MODE="baseline"; shift 2 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -d "$CLASSES" ] || { echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2; exit 2; }

if [ "$MODE" = "run" ] || [ "$MODE" = "sabotage" ]; then
    # Прогон против устаревших классов зелёный ровно так же, как против свежих. Точка отсчёта — jar последней сборки,
    # без него (классы собраны не Maven) — сам каталог классов; под pipefail подстановка обязана иметь || true.
    BUILD_STAMP="$(ls -1t "$REPO_ROOT/bundles/com.codepilot1c.core/target"/com.codepilot1c.core-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$BUILD_STAMP" ] || BUILD_STAMP="$CLASSES"
    NEWEST_SRC="$(find "$SRC" -name '*.java' -newer "$BUILD_STAMP" -print -quit 2>/dev/null)"
    if [ -n "$NEWEST_SRC" ]; then
        echo "ОТКАЗ: bundles/com.codepilot1c.core/target/classes старше исходников." >&2
        echo "  первый более новый: ${NEWEST_SRC#$SRC/}" >&2
        echo "  Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2
        exit 2
    fi
fi

# JUnit 4 и Hamcrest EDT поставляет в plugins/ (org.junit, org.hamcrest.core).
CP="$EDT_HOME/plugins/*"
# JNA лежит в EDT распакованным каталогом и под маску plugins/* не попадает.
JNA_DIR="$(ls -d "$EDT_HOME"/plugins/com.sun.jna_* 2>/dev/null | head -1 || true)"
[ -n "$JNA_DIR" ] && CP="$CP$CP_SEP$JNA_DIR"
# Библиотеки плагина: у target/classes они в lib/ бандла, у распакованного jar — в его lib/.
CP="$CP$CP_SEP$REPO_ROOT/bundles/com.codepilot1c.core/lib/*"
[ -d "$CLASSES/lib" ] && CP="$CP$CP_SEP$CLASSES/lib/*"
CP="$CLASSES$CP_SEP$CP"

TEST_CLASSES=(
    com.codepilot1c.core.edt.metadata.EdtMetadataServiceRecordFormTest
    com.codepilot1c.core.edt.metadata.EdtMetadataServiceDeleteOwnFormTest
    com.codepilot1c.core.edt.validation.MetadataRequestValidationServiceFormChildTest
    com.codepilot1c.core.edt.validation.MetadataRequestValidationServiceHttpChildTest
)
TEST_SOURCES=()
for test_class in "${TEST_CLASSES[@]}"; do TEST_SOURCES+=("$TESTS/${test_class//.//}.java"); done

# Тесты компилируются один раз: саботаж меняет продакшн-класс, а не тест.
"$JAVAC" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "${TEST_SOURCES[@]}" \
    2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тесты не скомпилировались" >&2
    exit 1
}

# overlay (саботированный класс) — раньше классов плагина.
run_suite() {
    "$JAVA" -Dfile.encoding=UTF-8 \
        -cp "$WORK/overlay$CP_SEP$WORK/tests$CP_SEP$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме: красный из-за classpath или NoSuchMethodException
    # доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        informationRegisterFormNamedRecordWithoutUsageIsRecordForm
        informationRegisterObjectUsageResolvesToRecord
        recordUsageIsParsedFromItsNames
        recordUsageBindsDefaultRecordForm
        recordUsageGeneratesRecordForm
        recordFormGetsRecordFormNameByDefault
        recordUsageOfCatalogIsRefusedWithExplanation
        objectUsageOfAccumulationRegisterIsRefusedWithExplanation
        objectUsageOfEnumIsRefusedWithChoiceHint
        objectRoleGuessedFromNameOfAccumulationRegisterFallsBackToList
        ownFormContentDoesNotBlockDeletingTheForm
        defaultFormRoleOfOwnerStillBlocksDeletingTheForm
        anotherFormContentBoundToTheFormStillCounts
        topLevelFormOptionsReachTheTokenBoundPayload
        tokenPayloadEqualsWhatTheToolAppliesForAFormChild
        tokenPayloadEqualsWhatTheToolAppliesWhenPropertiesAlsoCarryTheRole
    )
    EXPECTED_GREEN=(
        recordRoleGuessedFromNameOfCatalogFallsBackToObject
        informationRegisterListUsageStaysList
        informationRegisterListFormStillBinds
        accumulationRegisterListFormStillBinds
        catalogObjectFormStillBindsDefaultObjectForm
        attributeBoundOnTheFormStillBlocksDeletingTheAttribute
        formOptionsDoNotLeakIntoOtherChildren
        topLevelTemplateTypeStillReachesNonHttpChildren
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 220) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException|NoSuchFieldError' "$WORK/baseline.txt"; then
        echo "🚨 красный из-за обвязки (инициализация/classpath/сигнатура), а не из-за симптома"; PROBLEMS=1
    fi
    for red_test in "${EXPECTED_RED[@]}"; do
        grep -a -q -E "^[0-9]+\) $red_test\(" "$WORK/baseline.txt" || { echo "🚨 не покраснел: $red_test"; PROBLEMS=1; }
    done
    for green_test in "${EXPECTED_GREEN[@]}"; do
        if grep -a -q -E "^[0-9]+\) $green_test\(" "$WORK/baseline.txt"; then
            echo "🚨 покраснел то, что до фикса работало: $green_test"; PROBLEMS=1
        fi
    done
    # Симптомы — дословно: роль LIST у «ФормаЗаписи» и отказ удаления по Form#mdForm.
    grep -a -q "expected:<\[RECORD\]> but was:<\[LIST\]>" "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «RECORD, а было LIST» в причинах"; PROBLEMS=1; }
    grep -a -q 'ссылки на удаляемую форму: \[Form#mdForm\]' "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «Form#mdForm» в причинах"; PROBLEMS=1; }
    FAILED_COUNT="$(grep -a -c -E '^[0-9]+\) ' "$WORK/baseline.txt" || true)"
    [ "$FAILED_COUNT" = "${#EXPECTED_RED[@]}" ] \
        || { echo "🚨 покраснело $FAILED_COUNT, ожидалось ровно ${#EXPECTED_RED[@]} — сверка устарела"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE: сборка до фикса красная ровно на симптоме (${#EXPECTED_RED[@]}), прежнее поведение не задето"
    exit 0
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -n "$ECJ" ] && [ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: org.eclipse.jdt.core.compiler.batch_*.jar в $EDT_HOME/plugins (или задайте ECJ) — нужен для саботажа" >&2; exit 2; }

# argfile для ECJ: маску plugins/* он не раскрывает, а список jar-ов не влезает в командную строку Windows. Весь
# classpath — одной строкой в кавычках: без них ECJ режет по пробелу в пути установки. javax.xml из EDT выкидывается:
# иначе ECJ -17 падает split-package «accessible from more than one module: <unnamed>, java.xml».
{
    printf -- '-classpath\n"%s' "$CLASSES"
    for ecj_jar in "$EDT_HOME"/plugins/*.jar; do
        case "$(basename "$ecj_jar")" in javax.xml_*.jar|javax.xml.stream_*.jar) continue ;; esac
        printf '%s%s' "$CP_SEP" "$ecj_jar"
    done
    for ecj_dir in "$EDT_HOME"/plugins/*/; do printf '%s%s' "$CP_SEP" "${ecj_dir%/}"; done
    for lib_jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$lib_jar" ] && printf '%s%s' "$CP_SEP" "$lib_jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

# Саботаж на красной базе беззубый: «пойман» там любой дефект. Поэтому сначала чистый прогон обязан быть зелёным.
if ! run_suite > "$WORK/clean.txt" 2>&1; then
    grep -a -E '^[0-9]+\) |^Tests run' "$WORK/clean.txt" | head -10 || tail -5 "$WORK/clean.txt"
    echo "ОТКАЗ: набор красный и без саботажа — контроль ничего бы не доказал" >&2
    exit 1
fi

echo "=== САБОТАЖ-КОНТРОЛЬ формы записи и удаления формы: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"
STRATEGY="com/codepilot1c/core/edt/forms/FormOwnerStrategy.java"
VALIDATION="com/codepilot1c/core/edt/validation/MetadataRequestValidationService.java"
TOOL="com/codepilot1c/core/tools/metadata/AddMetadataChildTool.java"

# sabotage <метка> <файл относительно src> <perl-выражение> — правит копию класса и компилирует её ECJ в overlay.
# У переменных уникальные имена и local (динамическая область видимости bash), применение сверяется с копией,
# снятой до замены, а cmp = 2 — отдельная ошибка, не «различаются».
sabotage() {
    local sabotage_label="$1" sabotage_file="$2" sabotage_expr="$3"
    local sabotaged="$WORK/src/$sabotage_file" pristine="$WORK/pristine.java"
    SABOTAGES=$((SABOTAGES + 1))
    rm -rf "$WORK/src" "$WORK/overlay"; mkdir -p "$(dirname "$sabotaged")" "$WORK/overlay"
    cp "$SRC/$sabotage_file" "$sabotaged"
    cp "$sabotaged" "$pristine"
    perl -0pi -e "$sabotage_expr" "$sabotaged"
    echo
    echo "--- саботаж: $sabotage_label"
    local cmp_rc=0
    cmp -s "$pristine" "$sabotaged" || cmp_rc=$?
    if [ "$cmp_rc" -eq 0 ]; then
        echo "🚨 НЕ ПРИМЕНЁН: выражение не нашло текст — саботаж ничего не проверил"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    elif [ "$cmp_rc" -ne 1 ]; then
        echo "🚨 cmp вернул $cmp_rc — сверка применения сломана"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    local compile_rc=0
    "$JAVA" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -17 -encoding UTF-8 -nowarn -proc:none -d "$WORK/overlay" \
        "@$WORK/ecj-classpath.args" "$sabotaged" > "$WORK/compile.log" 2>&1 || compile_rc=$?
    if [ "$compile_rc" -ne 0 ]; then
        echo "🚨 НЕ СОБРАЛСЯ саботированный класс (rc=$compile_rc) — контроль недостоверен"
        tail -5 "$WORK/compile.log"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
        return
    fi
    local run_rc=0
    set +e
    run_suite > "$WORK/out.txt" 2>&1
    run_rc=$?
    set -e
    if [ "$run_rc" -eq 0 ]; then
        echo "🚨 НЕ ПОЙМАН: $sabotage_label — набор остался зелёным"
        FAILED_TO_CATCH=$((FAILED_TO_CATCH + 1))
    else
        echo "ok (красный): $(grep -a -m1 -E '^Tests run' "$WORK/out.txt" || true)"
    fi
}

# Шаг validate снова не несёт опции формы в токен (исходный дефект 1).
sabotage "validate не сворачивает опции формы" "$VALIDATION" \
    's/if \(isFormChildKind\(asString\(request\.payload\(\)\.get\("child_kind"\)\)\)\) \{/if (false) {/'

# Тул сворачивает опции формы, а validate — нет: разные списки у двух шагов.
sabotage "тул сворачивает не тот список, что validate" "$TOOL" \
    's/for \(String formOption : MetadataRequestValidationService\.ADD_CHILD_FORM_OPTIONS\) \{\n            putIfPresent\(merged, formOption, parameters\.get\(formOption\)\);\n        \}/putIfPresent(merged, "form_usage", parameters.get("form_usage"));/'

# OBJECT регистра сведений снова остаётся OBJECT.
sabotage "OBJECT регистра сведений не превращается в RECORD" "$SVC" \
    's/if \(usage == FormUsage\.OBJECT && informationRegister\) \{\n            return FormUsage\.RECORD;/if (usage == FormUsage.OBJECT && informationRegister) {\n            return usage;/'

# Имя «ФормаЗаписи» снова ничего не подсказывает.
sabotage "имя «…записи» не даёт роль RECORD" "$SVC" \
    's/if \(normalized\.contains\("записи"\) \|\| normalized\.contains\("record"\)\) \{/if (false) {/'

# Генератор EDT снова строит форму объекта вместо формы записи.
sabotage "генератор получает OBJECT вместо RECORD" "$SVC" \
    's/case RECORD -> "RECORD";/case RECORD -> "OBJECT";/'

# Роль RECORD привязывается не к defaultRecordForm.
sabotage "RECORD привязывается к defaultListForm" "$STRATEGY" \
    's/case RECORD -> "setDefaultRecordForm";/case RECORD -> "setDefaultListForm";/'

# Владельцы только со списками снова принимают OBJECT молча.
sabotage "OBJECT у регистра накопления и перечисления не отклоняется" "$SVC" \
    's/case "enum", "accumulationregister", "accountingregister", "calculationregister" -> true;/case "no-such-owner" -> true;/'

# RECORD вне регистра сведений снова проходит.
sabotage "RECORD у справочника не отклоняется" "$SVC" \
    's/if \(usage == FormUsage\.RECORD && !informationRegister\) \{/if (false) {/'

# Содержимое формы снова не своё: переход AbstractForm.mdForm убран (исходный дефект 2).
sabotage "isOwnContentOf без перехода через mdForm" "$SVC" \
    's/(private static boolean isOwnContentOf.*?)container = formModel\.getMdForm\(\);/$1container = null;/s'

# Починка «не туда»: всё, что ссылается на форму, считается её содержимым.
sabotage "isOwnContentOf всегда истина" "$SVC" \
    's/(private static boolean isOwnContentOf\(EObject source, EObject target\) \{\n)/$1        if (source != null) {\n            return true;\n        }\n/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
