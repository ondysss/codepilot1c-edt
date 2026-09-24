#!/usr/bin/env bash
# Парный eval проверки входящих ссылок у delete_metadata без force.
#
# Дефект: только что созданный реквизит документа не удалялся — METADATA_DELETE_CONFLICT «Обнаружены ссылки
# на удаляемый объект (6)», примеры Document.X#source, #mdObject, #presentationSource. collectIncomingReferences
# считал внешней любую некорневую ссылку, кроме ссылки от самого объекта, — в том числе производные данные его
# же документа, которые EDT выводит из модели и не сериализует (поля объекта, представление таблицы БД,
# свойства произведённых типов). В примеры шёл FQN верхнего объекта источника, и это выглядело как ссылка от
# документа. С force:true реквизит удалялся.
#
# Производные данные формы (второй коммит): у документа с формой, когда EDT пересчитала производные данные формы
# (например, после update_metadata по самому документу), отказ давал «…Form.ФормаДокумента.Form#source». Модель
# формы — отдельный верхний объект BM, и её производные поля (Form.fields → DerivedField.source, formContext) не
# считались данными владельца. Разрешение записанного пути данных (AbstractDataPath.objects) тоже transient, но
# это привязка разработчика — она считается.
#
# Использование (EDT_HOME — каталог Eclipse установленной 1C:EDT, тот же, что -Dedt.home; JDK 17+ берётся из
# JAVA_HOME, без него — javac/java из PATH; JUnit, Hamcrest и компилятор ECJ — из plugins/ самой EDT):
#   EDT_HOME=... bash tools/run-delete-references-eval.sh                      # против target/classes, ожидается зелёный
#   EDT_HOME=... bash tools/run-delete-references-eval.sh --baseline <каталог> # против распакованного jar сборки ДО фикса:
#                                                                              # обязан покраснеть поимённо на симптоме
#   EDT_HOME=... bash tools/run-delete-references-eval.sh --baseline-form <каталог> # против сборки с первым коммитом,
#                                                                              # без второго: красная на производных данных формы
#   EDT_HOME=... bash tools/run-delete-references-eval.sh --classes <каталог>  # против другой сборки, ожидается зелёный
#   EDT_HOME=... bash tools/run-delete-references-eval.sh --sabotage           # каждый дефект обязан дать красный
#
# EdtMetadataServiceDeleteReferencesTest строит документ с реквизитом и его производными данными из фабрик EMF
# (как в модели EDT 2025.2.3), перекрёстные ссылки считает обходом графа и бьёт рефлексией по настоящему
# collectIncomingReferences через подменённый getBmModelManager. На классы фикса не ссылается — один бинарник
# идёт против сборки до фикса и после.
#
# Чего этот eval НЕ проверяет: что живая BM отдаёт ссылки на производные объекты тем же getReferences(URI) и
# что производные данные лежат под transient-вложением так же, как в xcore. Это закрывает только живая проба
# вызовом тула через MCP на установленной сборке.
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
WORK="$REPO_ROOT/target/delete-references-eval"
rm -rf "$WORK"; mkdir -p "$WORK/tests" "$WORK/src" "$WORK/overlay"
trap 'rm -rf "$WORK"' EXIT

MODE="run"
CLASSES="$REPO_ROOT/bundles/com.codepilot1c.core/target/classes"
while [ $# -gt 0 ]; do
    case "$1" in
        --sabotage) MODE="sabotage"; shift ;;
        --classes) CLASSES="$(win_path "$2")"; MODE="classes"; shift 2 ;;
        --baseline) CLASSES="$(win_path "$2")"; MODE="baseline"; shift 2 ;;
        --baseline-form) CLASSES="$(win_path "$2")"; MODE="baseline-form"; shift 2 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[ -d "$CLASSES" ] || { echo "ОТКАЗ: нет $CLASSES. Сначала: mvn -DskipTests -Dedt.home=<Eclipse> -pl '!:com.codepilot1c.core.tests' package" >&2; exit 2; }

if [ "$MODE" = "run" ] || [ "$MODE" = "sabotage" ]; then
    # Прогон против устаревших классов зелёный ровно так же, как против свежих, поэтому свежесть
    # проверяется. Точка отсчёта — jar последней сборки: mtime каталога classes не меняется при
    # перезаписи файлов внутри.
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

TEST_CLASS=com.codepilot1c.core.edt.metadata.EdtMetadataServiceDeleteReferencesTest

# Тест компилируется один раз: саботаж меняет продакшн-класс, а не тест. Ошибки javac видны
# целиком — сломанный тест иначе выглядит как ClassNotFoundException.
"$JAVAC" -encoding UTF-8 -nowarn -d "$WORK/tests" -cp "$CP" "$TESTS/${TEST_CLASS//.//}.java" 2> "$WORK/javac-tests.log" || {
    cat "$WORK/javac-tests.log" >&2
    echo "тест не скомпилировался" >&2
    exit 1
}

# overlay (саботированный класс) — раньше классов плагина и EDT.
run_suite() {
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/overlay$CP_SEP$WORK/tests$CP_SEP$CP" org.junit.runner.JUnitCore "$TEST_CLASS"
}

if [ "$MODE" = "run" ] || [ "$MODE" = "classes" ]; then
    run_suite
    exit $?
fi

if [ "$MODE" = "baseline" ]; then
    # Сборка до фикса обязана покраснеть ровно на симптоме: красный из-за classpath или
    # NoSuchMethodException доказал бы только обвязку. Поэтому сверяются имена и причины.
    EXPECTED_RED=(
        freshAttributeHasNoIncomingReferences
        attributeWithTwoDerivedFieldsHasNoIncomingReferences
        inputByStringThroughDerivedFieldIsNamed
        choiceParameterLinkOfSiblingThroughDerivedFieldIsNamed
        derivedDataOfAnotherTopObjectStillCounts
        unreadableReferencesToDerivedObjectStillCount
        directReferenceFromAnotherObjectStillRefuses
        # Тесты производных данных формы на сборке до фикса: там любая производная ссылка засчитывалась, точные
        # счётчики не сходятся.
        derivedFieldOfOwnerFormHasNoIncomingReferences
        formContextOfOwnerFormHasNoIncomingReferences
        writtenFormFieldBindingThroughDerivedFieldIsNamed
        writtenFormFieldBindingToAttributeStillRefuses
        derivedFieldOfAnotherDocumentFormStillCounts
        derivedFieldOfFormWithoutMdFormStillCounts
    )
    EXPECTED_GREEN=(
        inputByStringThroughDerivedFieldStillRefuses
        writtenFormFieldBindingThroughDerivedFieldStillRefuses
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 260) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка до фикса ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException|обвязка:' "$WORK/baseline.txt"; then
        echo "🚨 красный из-за обвязки (инициализация/classpath/сигнатура/URI), а не из-за симптома"; PROBLEMS=1
    fi
    for red_test in "${EXPECTED_RED[@]}"; do
        grep -a -q -E "^[0-9]+\) $red_test\(" "$WORK/baseline.txt" || { echo "🚨 не покраснел: $red_test"; PROBLEMS=1; }
    done
    for green_test in "${EXPECTED_GREEN[@]}"; do
        if grep -a -q -E "^[0-9]+\) $green_test\(" "$WORK/baseline.txt"; then
            echo "🚨 покраснел то, что до фикса работало: $green_test"; PROBLEMS=1
        fi
    done
    # Сам симптом: свежий реквизит — ссылки от производных данных своего документа. В фикстуре пять видов
    # (поле объекта, два поля и тип представления БД, свойство типа); живьём было шесть — один вид встречается дважды.
    grep -a -q -E 'ссылки на свежий реквизит: .*expected:<0> but was:<5>' "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «0 → 5 ссылок» в причинах"; PROBLEMS=1; }
    TOTAL=$(( ${#EXPECTED_RED[@]} + ${#EXPECTED_GREEN[@]} ))
    grep -a -q -E "^Tests run: $TOTAL," "$WORK/baseline.txt" || { echo "🚨 в наборе не $TOTAL тестов — сверка устарела"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE: сборка до фикса красная ровно на симптоме (${#EXPECTED_RED[@]} из $TOTAL), прежний отказ по настоящей ссылке не задет"
    exit 0
fi

if [ "$MODE" = "baseline-form" ]; then
    # Сборка с первым коммитом, но без второго: красная только на производных данных формы владельца.
    # Симптом из живого замера — «…Form.ФормаДокумента.Form#source», одна ссылка на свежий реквизит.
    FORM_RED=(
        derivedFieldOfOwnerFormHasNoIncomingReferences
        formContextOfOwnerFormHasNoIncomingReferences
        writtenFormFieldBindingThroughDerivedFieldIsNamed
    )
    set +e
    run_suite > "$WORK/baseline.txt" 2>&1
    suite_rc=$?
    set -e
    awk '/^[0-9]+\) / { print; getline; print "     " substr($0, 1, 260) } /^Tests run|^OK \(/ { print }' "$WORK/baseline.txt"
    PROBLEMS=0
    [ "$suite_rc" -ne 0 ] || { echo "🚨 сборка без фикса форм ЗЕЛЁНАЯ — eval не воспроизводит дефект"; PROBLEMS=1; }
    if grep -a -q -E 'ExceptionInInitializerError|NoClassDefFoundError|NoSuchMethodException|обвязка:' "$WORK/baseline.txt"; then
        echo "🚨 красный из-за обвязки (инициализация/classpath/сигнатура/URI), а не из-за симптома"; PROBLEMS=1
    fi
    for red_test in "${FORM_RED[@]}"; do
        grep -a -q -E "^[0-9]+\) $red_test\(" "$WORK/baseline.txt" || { echo "🚨 не покраснел: $red_test"; PROBLEMS=1; }
    done
    red_count="$(grep -a -c -E '^[0-9]+\) ' "$WORK/baseline.txt" || true)"
    if [ "$red_count" -gt "${#FORM_RED[@]}" ]; then
        echo "🚨 красных $red_count, а не ${#FORM_RED[@]}: задето то, что чинил первый коммит"; PROBLEMS=1
    elif [ "$red_count" -lt "${#FORM_RED[@]}" ]; then
        echo "🚨 красных $red_count, а не ${#FORM_RED[@]}: это не сборка без фикса форм (или eval не воспроизводит дефект)"; PROBLEMS=1
    fi
    grep -a -q -E 'ссылки на свежий реквизит: \[DerivedField#source\] expected:<0> but was:<1>' "$WORK/baseline.txt" \
        || { echo "🚨 нет симптома «производное поле формы — одна ссылка» в причинах"; PROBLEMS=1; }
    if [ "$PROBLEMS" -ne 0 ]; then
        echo "BASELINE-FORM: ПРОВАЛ"
        exit 1
    fi
    echo "BASELINE-FORM: сборка без фикса форм красная ровно на производных данных формы (${#FORM_RED[@]}), остальное зелёное"
    exit 0
fi

# ─── саботаж ──────────────────────────────────────────────────────────────

[ -n "$ECJ" ] && [ -f "$ECJ" ] || { echo "НЕ НАЙДЕНО: org.eclipse.jdt.core.compiler.batch_*.jar в $EDT_HOME/plugins (или задайте ECJ) — нужен для саботажа EdtMetadataService" >&2; exit 2; }

# argfile для ECJ: маску plugins/* он не раскрывает, а список jar-ов не влезает в командную строку
# Windows. Весь classpath — одной строкой в кавычках: без них ECJ режет по пробелу в пути установки.
{
    printf -- '-classpath\n"%s' "$CLASSES"
    for ecj_jar in "$EDT_HOME"/plugins/*.jar; do printf '%s%s' "$CP_SEP" "$ecj_jar"; done
    for ecj_dir in "$EDT_HOME"/plugins/*/; do printf '%s%s' "$CP_SEP" "${ecj_dir%/}"; done
    for lib_jar in "$REPO_ROOT"/bundles/com.codepilot1c.core/lib/*.jar; do [ -e "$lib_jar" ] && printf '%s%s' "$CP_SEP" "$lib_jar"; done
    printf '"\n'
} > "$WORK/ecj-classpath.args"

echo "=== САБОТАЖ-КОНТРОЛЬ ссылок delete_metadata: каждый дефект обязан дать красный ==="
FAILED_TO_CATCH=0
SABOTAGES=0
SVC="com/codepilot1c/core/edt/metadata/EdtMetadataService.java"

# sabotage <метка> <perl-выражение> — правит копию EdtMetadataService и компилирует её ECJ в overlay.
# У переменных уникальные имена и local (динамическая область видимости
# bash), применение сверяется с копией, снятой до замены, а cmp = 2 — отдельная ошибка, не «различаются».
sabotage() {
    local sabotage_label="$1" sabotage_expr="$2"
    local sabotaged="$WORK/src/$SVC" pristine="$WORK/pristine.java"
    SABOTAGES=$((SABOTAGES + 1))
    rm -rf "$WORK/src" "$WORK/overlay"; mkdir -p "$(dirname "$sabotaged")" "$WORK/overlay"
    cp "$SRC/$SVC" "$sabotaged"
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

# Исходный дефект: производные данные владельца снова считаются внешними ссылками.
sabotage "производные данные владельца считаются ссылками (дефект до фикса)" \
    's/if \(isDerivedDataOf\(source, owner\)\) \{/if (false) {/'

# Производный объект молча выбрасывается, а не проходится насквозь: теряется inputByString и связи параметров.
sabotage "производный объект не проходится насквозь" \
    's/pending\.add\(throughDerived\);\n/\n/'

# Transient-вложение не требуется: любой объект под владельцем (и сам документ) — «производный».
sabotage "не требуется transient-вложение" \
    's/return belowTransientContainment;/return true;/'

# Производные данные ЧУЖОГО верхнего объекта тоже выбрасываются.
sabotage "производные данные чужого объекта не считаются" \
    's/if \(current == owner\) \{/if (current.eContainer() == null) {/'

# Нечитаемые ссылки на производный объект молча считаются пустыми.
sabotage "нечитаемые ссылки на производный объект — пустой список" \
    's/return engine != null \? engine\.getBackReferences\(target\) : null;/return engine != null ? engine.getBackReferences(target) : List.of();/'

# Модель формы снова обрывает подъём к владельцу — производные данные формы опять считаются.
sabotage "форма владельца не ведёт к владельцу через mdForm (дефект форм)" \
    's/container = formModel\.getMdForm\(\);/container = null;/'

# Разрешение записанного пути данных принимается за производные данные — привязка поля теряется.
sabotage "разрешение записанного пути данных считается производным" \
    's/if \(current instanceof AbstractDataPath\) \{\n\s*return false;/if (false) {\n                return false;/'

echo
if [ "$FAILED_TO_CATCH" -ne 0 ]; then
    echo "САБОТАЖ-КОНТРОЛЬ ПРОВАЛЕН: не пойманных или неприменённых дефектов $FAILED_TO_CATCH из $SABOTAGES"
    exit 1
fi
echo "САБОТАЖ-КОНТРОЛЬ: все $SABOTAGES дефектов пойманы"
