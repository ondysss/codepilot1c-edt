#!/usr/bin/env bash
# Парный eval привязки основной формы при create_form / apply_form_recipe.
#
# Дефект: create_form без usage или с usage:OBJECT на DataProcessor.X отвечал
# [INVALID_FORM_USAGE] Form usage OBJECT is not supported for owner DataProcessor. Роль OBJECT разрешалась в один
# сеттер setDefaultObjectForm, а основная форма объекта в модели EDT живёт в трёх фичах: defaultObjectForm
# (справочник, документ, ПВХ, бизнес-процесс, задача, план обмена), defaultRecordForm (регистр сведений) и
# defaultForm (обработка, отчёт, внешние обработка и отчёт). Внешним объектам привязку выключили целиком, и форма
# из инструмента оставалась без роли основной. Воспроизведено на EDT 2025.2.3 (обход: usage AUXILIARY, затем
# update_metadata changes.set.defaultForm).
#
# Использование (EDT_HOME — каталог Eclipse установленной 1C:EDT, тот же, что -Dedt.home; JDK берётся из
# JAVA_HOME, без него — javac/java из PATH; JUnit, Hamcrest и компилятор ECJ — из plugins/ самой EDT):
#   EDT_HOME=... bash tools/run-form-default-binding-eval.sh                      # против target/classes, ожидается зелёный
#   EDT_HOME=... bash tools/run-form-default-binding-eval.sh --baseline <каталог> # против сборки ДО фикса:
#                                                                                 # обязан покраснеть поимённо на симптоме
#   EDT_HOME=... bash tools/run-form-default-binding-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   EDT_HOME=... bash tools/run-form-default-binding-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# EdtMetadataServiceDefaultFormBindingTest бьёт рефлексией по настоящим bindDefaultForm, resolveDefaultBinding и
# resolveEffectiveFormUsage (владельцы и формы из фабрики EMF) и не ссылается на классы фикса — один бинарник идёт
# против сборки до фикса и после. Разрешение типов не нужно, поэтому и заглушек нет.
#
# Чего этот eval НЕ проверяет: место вызова в createForm (там снят отдельный фильтр внешних владельцев), запись
# defaultForm в .mdo и откат транзакции BM. Это закрывает только живая проверка вызовом тула через MCP на
# установленной сборке.
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
# EDT поставляет его пакетный вариант в plugins/.
ECJ="${ECJ:-$(ls "$EDT_HOME"/plugins/org.eclipse.jdt.core.compiler.batch_*.jar 2>/dev/null | head -1 || true)}"

# Каталог внутри репозитория, а не mktemp: в Git Bash mktemp отдаёт POSIX-путь, которого JVM не видит.
WORK="$REPO_ROOT/target/form-default-binding-eval"
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
    # Прогон против устаревших классов зелёный ровно так же, как против свежих, поэтому свежесть
    # проверяется. Точка отсчёта — jar последней сборки: mtime каталога classes не меняется при
    # перезаписи файлов внутри. Без jar-а (классы собраны не Maven) ls падает, и под pipefail
    # подстановка без || true молча роняла бы весь скрипт с кодом 2.
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

TEST_CLASS=com.codepilot1c.core.edt.metadata.EdtMetadataServiceDefaultFormBindingTest

# Тест компилируется один раз: саботаж меняет продакшн-класс, а не тест. Ошибки javac видны
# целиком — сломанный тест иначе выглядит как ClassNotFoundException.
"$JAVAC" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "$TESTS/${TEST_CLASS//.//}.java" \
    2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тест не скомпилировался" >&2
    exit 1
}

# overlay (саботированный класс) — раньше классов плагина.
run_suite() {
    "$JAVA" -Dfile.encoding=UTF-8 \
        -cp "$WORK/overlay$CP_SEP$WORK/tests$CP_SEP$CP" org.junit.runner.JUnitCore "$TEST_CLASS"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме: красный из-за classpath или
    # NoSuchMethodException доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        dataProcessorObjectFormBecomesDefaultForm
        dataProcessorFormWithoutUsageBecomesDefaultForm
        reportObjectFormBecomesDefaultForm
        externalDataProcessorObjectFormBecomesDefaultForm
        externalReportObjectFormBecomesDefaultForm
        informationRegisterObjectFormBecomesRecordForm
        objectRoleIsBoundInExternalProject
        objectRoleIsBoundForExternalReportOwner
    )
    EXPECTED_GREEN=(
        catalogObjectFormStillBindsDefaultObjectForm
        documentListAndChoiceFormsStillBind
        informationRegisterListFormStillBinds
        auxiliaryFormBindsNothing
        setAsDefaultFalseBindsNothing
        enumObjectRoleIsStillRefused
        dataProcessorListRoleIsStillRefused
        listRoleOfExternalOwnerBindsNothing
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 220) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException' "$WORK/baseline.txt"; then
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
    # Сам симптом из воспроизведения — дословно.
    grep -a -q 'Form usage OBJECT is not supported for owner DataProcessor' "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «Form usage OBJECT is not supported for owner DataProcessor» в причинах"; PROBLEMS=1; }
    TOTAL=$(( ${#EXPECTED_RED[@]} + ${#EXPECTED_GREEN[@]} ))
    grep -a -q -E "^Tests run: $TOTAL," "$WORK/baseline.txt" || { echo "🚨 в наборе не $TOTAL тестов — сверка устарела"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE: сборка до фикса красная ровно на симптоме (${#EXPECTED_RED[@]} из $TOTAL), прежнее поведение не задето"
    exit 0
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -n "$ECJ" ] && [ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: org.eclipse.jdt.core.compiler.batch_*.jar в $EDT_HOME/plugins (или задайте ECJ) — нужен для саботажа" >&2; exit 2; }

# argfile для ECJ: маску plugins/* он не раскрывает, а список jar-ов не влезает в командную строку
# Windows. Весь classpath — одной строкой в кавычках: без них ECJ режет по пробелу в пути установки.
{
    printf -- '-classpath\n"%s' "$CLASSES"
    for ecj_jar in "$EDT_HOME"/plugins/*.jar; do printf '%s%s' "$CP_SEP" "$ecj_jar"; done
    for ecj_dir in "$EDT_HOME"/plugins/*/; do printf '%s%s' "$CP_SEP" "${ecj_dir%/}"; done
    for lib_jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$lib_jar" ] && printf '%s%s' "$CP_SEP" "$lib_jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

# Саботаж на красной базе беззубый: «пойман» там любой дефект. Поэтому сначала чистый прогон обязан быть зелёным.
if ! run_suite > "$WORK/clean.txt" 2>&1; then
    # Без строк теста (JVM не стартовала, класс не найден) grep вернёт 1, и под pipefail скрипт умер бы до
    # сообщения ОТКАЗ — тогда показать хвост вывода, там настоящая причина.
    grep -a -E '^[0-9]+\) |^Tests run' "$WORK/clean.txt" | head -10 || tail -5 "$WORK/clean.txt"
    echo "ОТКАЗ: набор красный и без саботажа — контроль ничего бы не доказал" >&2
    exit 1
fi

echo "=== САБОТАЖ-КОНТРОЛЬ привязки основной формы: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"
STRATEGY="com/codepilot1c/core/edt/forms/FormOwnerStrategy.java"

# sabotage <метка> <файл относительно src> <perl-выражение> — правит копию класса и компилирует её ECJ в overlay.
# У переменных уникальные имена и local (динамическая область видимости
# bash), применение сверяется с копией, снятой до замены, а cmp = 2 — отдельная ошибка, не «различаются».
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
        # Несобравшийся саботаж не проверяет тест — это дефект самого контроля.
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
        echo "ok (красный): $(grep -a -m1 -E '^Tests run' "$WORK/out.txt")"
    fi
}

# Исходный дефект: роль OBJECT снова знает только setDefaultObjectForm.
sabotage "роль OBJECT = только setDefaultObjectForm (дефект до фикса)" "$STRATEGY" \
    's/List\.of\("setDefaultObjectForm", "setDefaultRecordForm", "setDefaultForm"\)/List.of("setDefaultObjectForm")/'

# Форма записи регистра сведений снова не привязывается.
sabotage "роль OBJECT без setDefaultRecordForm" "$STRATEGY" \
    's/"setDefaultObjectForm", "setDefaultRecordForm", "setDefaultForm"/"setDefaultObjectForm", "setDefaultForm"/'

# Перебор кандидатов снова смотрит только на первого.
sabotage "bindDefaultForm пробует только первый сеттер" "$SVC" \
    's/for \(String candidate : setters\) \{/for (String candidate : setters.subList(0, 1)) {/'

# Внешним владельцам снова выключена любая привязка (исходное исключение).
sabotage "внешним владельцам привязка выключена целиком" "$SVC" \
    's/if \(externalOwner && usage != FormUsage\.OBJECT\) \{/if (externalOwner) {/'

# Внешние владельцы получают роли, которых у них нет, — имя «ФормаСписка» свалит создание формы.
sabotage "внешним владельцам разрешены роли кроме OBJECT" "$SVC" \
    's/if \(externalOwner && usage != FormUsage\.OBJECT\) \{/if (false) {/'

# Вспомогательная форма снова претендует на роль основной.
sabotage "AUXILIARY не отсекается в resolveDefaultBinding" "$SVC" \
    's/if \(usage == null \|\| usage == FormUsage\.AUXILIARY\) \{\n            return false;\n        \}\n        String ownerType/if (usage == null) {\n            return false;\n        }\n        String ownerType/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
