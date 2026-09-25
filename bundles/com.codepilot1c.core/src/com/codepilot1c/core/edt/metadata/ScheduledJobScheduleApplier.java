package com.codepilot1c.core.edt.metadata;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.schedule.model.DailySchedule;
import com._1c.g5.v8.dt.schedule.model.Months;
import com._1c.g5.v8.dt.schedule.model.Schedule;
import com._1c.g5.v8.dt.schedule.model.ScheduleFactory;
import com._1c.g5.v8.dt.schedule.model.SchedulePackage;
import com._1c.g5.v8.dt.schedule.model.WeekDays;
import com._1c.g5.v8.dt.schedule.model.util.ScheduleDateTimeUtil;

/**
 * Builds the content of {@code ScheduledJob.schedule} (the {@code Schedule.schedule} file) from a
 * JSON-like map.
 *
 * <p>{@code ScheduledJob.schedule} is an {@code @ExternalProperty refers transient Schedule}: the
 * schedule lives in its own BM top object, so the generic setter rejects it as read-only. The owner
 * side (attach the top object, bind the reference) is in {@link EdtMetadataService}; this class holds
 * only the value logic and runs without BM/OSGi.
 *
 * <p>Semantics are "replace": the schedule is reset to the EDT defaults of a new schedule and then the
 * given keys are applied. Defaults mirror {@code com._1c.g5.v8.dt.schedule.ScheduleObjectFactory}
 * (dates and times {@code 0001-01-01}/{@code 00:00:00}, {@code weeksPeriod=1}, every week day and
 * month), except the order of week days: Monday..Sunday, as the platform import writes them.
 *
 * <p>Keys are the model feature names (case-insensitive) or the property names of the platform
 * {@code РасписаниеРегламентногоЗадания}. Week days accept names ({@code Mon}, {@code Monday},
 * {@code Пн}, {@code Понедельник}) or 1C numbers 1..7 (1 = Monday); months accept names or 1..12.
 * Unknown keys and malformed values are refused, nothing is guessed.
 */
final class ScheduledJobScheduleApplier {

    static final String FIELD_NAME = "schedule"; //$NON-NLS-1$

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})"); //$NON-NLS-1$
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"); //$NON-NLS-1$

    private enum Kind {
        DATE, TIME, NON_NEGATIVE_INT, WEEKS_PERIOD, INT, WEEK_DAYS, MONTHS, DAILY_SCHEDULES
    }

    private record Key(EStructuralFeature feature, Kind kind) {
    }

    private static final Map<String, Key> SCHEDULE_KEYS = new LinkedHashMap<>();
    private static final Map<String, Key> DAILY_KEYS = new LinkedHashMap<>();
    private static final Map<String, WeekDays> WEEK_DAY_TOKENS = new LinkedHashMap<>();
    private static final Map<String, Months> MONTH_TOKENS = new LinkedHashMap<>();

    static {
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__BEGIN_DATE, Kind.DATE, "ДатаНачала"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__END_DATE, Kind.DATE, "ДатаКонца", "ДатаОкончания"); //$NON-NLS-1$ //$NON-NLS-2$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__DAYS_REPEAT_PERIOD, Kind.NON_NEGATIVE_INT,
                "ПериодПовтораДней"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__BEGIN_TIME, Kind.TIME, "ВремяНачала"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__END_TIME, Kind.TIME, "ВремяКонца", "ВремяОкончания"); //$NON-NLS-1$ //$NON-NLS-2$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__COMPLETION_TIME, Kind.TIME, "ВремяЗавершения"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__COMPLETION_INTERVAL, Kind.NON_NEGATIVE_INT,
                "ИнтервалЗавершения"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__REPEAT_PERIOD_IN_DAY, Kind.NON_NEGATIVE_INT,
                "ПериодПовтораВТечениеДня"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__REPEAT_PAUSE, Kind.NON_NEGATIVE_INT, "ПаузаПовтора"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__DAILY_SCHEDULES, Kind.DAILY_SCHEDULES,
                "ДетальныеРасписанияДня"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__WEEKS_PERIOD, Kind.WEEKS_PERIOD, "ПериодНедель"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__WEEK_DAYS, Kind.WEEK_DAYS, "ДниНедели"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__WEEK_DAY_IN_MONTH, Kind.INT, "ДеньНеделиВМесяце"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__DAY_IN_MONTH, Kind.INT, "ДеньВМесяце"); //$NON-NLS-1$
        key(SCHEDULE_KEYS, SchedulePackage.Literals.SCHEDULE__MONTHS, Kind.MONTHS, "Месяцы"); //$NON-NLS-1$

        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__BEGIN_TIME, Kind.TIME, "ВремяНачала"); //$NON-NLS-1$
        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__END_TIME, Kind.TIME, "ВремяКонца", "ВремяОкончания"); //$NON-NLS-1$ //$NON-NLS-2$
        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__COMPLETION_TIME, Kind.TIME, "ВремяЗавершения"); //$NON-NLS-1$
        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__COMPLETION_INTERVAL, Kind.NON_NEGATIVE_INT,
                "ИнтервалЗавершения"); //$NON-NLS-1$
        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__REPEAT_PERIOD_IN_DAY, Kind.NON_NEGATIVE_INT,
                "ПериодПовтораВТечениеДня"); //$NON-NLS-1$
        key(DAILY_KEYS, SchedulePackage.Literals.DAILY_SCHEDULE__REPEAT_PAUSE, Kind.NON_NEGATIVE_INT, "ПаузаПовтора"); //$NON-NLS-1$

        weekDay(WeekDays.MONDAY, "понедельник", "пн"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.TUESDAY, "вторник", "вт"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.WEDNESDAY, "среда", "ср"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.THURSDAY, "четверг", "чт"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.FRIDAY, "пятница", "пт"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.SATURDAY, "суббота", "сб"); //$NON-NLS-1$ //$NON-NLS-2$
        weekDay(WeekDays.SUNDAY, "воскресенье", "вс"); //$NON-NLS-1$ //$NON-NLS-2$

        month(Months.JANUARY, "январь", "янв"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.FEBRUARY, "февраль", "фев"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.MARCH, "март", "мар"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.APRIL, "апрель", "апр"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.MAY, "май"); //$NON-NLS-1$
        month(Months.JUNE, "июнь", "июн"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.JULY, "июль", "июл"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.AUGUST, "август", "авг"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.SEPTEMBER, "сентябрь", "сен"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.OCTOBER, "октябрь", "окт"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.NOVEMBER, "ноябрь", "ноя"); //$NON-NLS-1$ //$NON-NLS-2$
        month(Months.DECEMBER, "декабрь", "дек"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private ScheduledJobScheduleApplier() {
    }

    /** {@code true} when {@code fieldName} addresses the schedule of a scheduled job. */
    static boolean isScheduleField(Object target, String fieldName) {
        return target instanceof com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob
                && fieldName != null
                && FIELD_NAME.equalsIgnoreCase(fieldName.trim());
    }

    /** A new schedule with the defaults of a schedule created by EDT. */
    static Schedule newDefaultSchedule() {
        Schedule schedule = ScheduleFactory.eINSTANCE.createSchedule();
        resetToDefaults(schedule);
        return schedule;
    }

    /**
     * Replaces the content of {@code schedule} with the defaults plus {@code value}. The value is fully
     * parsed before the schedule is touched, so a refused value leaves the schedule as it was.
     */
    static void fill(Schedule schedule, Object value) {
        Map<EStructuralFeature, Object> parsed = parse(value);
        resetToDefaults(schedule);
        for (Map.Entry<EStructuralFeature, Object> entry : parsed.entrySet()) {
            setFeature(schedule, entry.getKey(), entry.getValue());
        }
    }

    /** Supported keys of the schedule object, for error messages and docs. */
    static List<String> supportedKeys() {
        List<String> names = new ArrayList<>();
        for (Key key : new java.util.LinkedHashSet<>(SCHEDULE_KEYS.values())) {
            names.add(key.feature().getName());
        }
        return names;
    }

    private static void resetToDefaults(Schedule schedule) {
        schedule.setBeginDate(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        schedule.setEndDate(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        schedule.setDaysRepeatPeriod(0);
        schedule.setBeginTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        schedule.setEndTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        schedule.setCompletionTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        schedule.setCompletionInterval(0);
        schedule.setRepeatPeriodInDay(0);
        schedule.setRepeatPause(0);
        schedule.getDailySchedules().clear();
        schedule.setWeeksPeriod(1);
        replaceList(schedule.getWeekDays(), allWeekDays());
        schedule.setWeekDayInMonth(0);
        schedule.setDayInMonth(0);
        replaceList(schedule.getMonths(), Months.VALUES);
    }

    private static DailySchedule newDefaultDailySchedule() {
        DailySchedule daily = ScheduleFactory.eINSTANCE.createDailySchedule();
        daily.setBeginTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        daily.setEndTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        daily.setCompletionTime(ScheduleDateTimeUtil.DEFAULT_DATE_TIME);
        return daily;
    }

    private static List<WeekDays> allWeekDays() {
        return List.of(WeekDays.MONDAY, WeekDays.TUESDAY, WeekDays.WEDNESDAY, WeekDays.THURSDAY,
                WeekDays.FRIDAY, WeekDays.SATURDAY, WeekDays.SUNDAY);
    }

    private static <T> void replaceList(List<T> target, Collection<? extends T> values) {
        target.clear();
        target.addAll(values);
    }

    @SuppressWarnings("unchecked")
    private static void setFeature(org.eclipse.emf.ecore.EObject owner, EStructuralFeature feature, Object value) {
        if (feature.isMany()) {
            replaceList((List<Object>) owner.eGet(feature), (Collection<Object>) value);
        } else {
            owner.eSet(feature, value);
        }
    }

    private static Map<EStructuralFeature, Object> parse(Object value) {
        Map<?, ?> map = asMap(value, "schedule"); //$NON-NLS-1$
        Map<EStructuralFeature, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String rawKey = String.valueOf(entry.getKey());
            Key key = SCHEDULE_KEYS.get(normalize(rawKey));
            if (key == null) {
                throw invalid("schedule: unknown key '" + rawKey + "'. Supported keys: " //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", supportedKeys())); //$NON-NLS-1$
            }
            if (parsed.containsKey(key.feature())) {
                throw invalid("schedule: key '" + key.feature().getName() + "' is given twice"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            parsed.put(key.feature(), convert(key, entry.getValue(), "schedule." + rawKey)); //$NON-NLS-1$
        }
        return parsed;
    }

    private static Object convert(Key key, Object raw, String path) {
        return switch (key.kind()) {
            case DATE -> raw == null ? ScheduleDateTimeUtil.DEFAULT_DATE_TIME : parseDate(raw, path);
            case TIME -> raw == null ? ScheduleDateTimeUtil.DEFAULT_DATE_TIME : parseTime(raw, path);
            case NON_NEGATIVE_INT -> Integer.valueOf(raw == null ? 0 : parseInt(raw, path, 0));
            // The model comment says "> 0", but schedules imported from the platform carry 0 (the
            // attribute is absent), so 0 is accepted; null restores the EDT default 1.
            case WEEKS_PERIOD -> Integer.valueOf(raw == null ? 1 : parseInt(raw, path, 0));
            case INT -> Integer.valueOf(raw == null ? 0 : parseInt(raw, path, Integer.MIN_VALUE));
            case WEEK_DAYS -> raw == null ? allWeekDays() : parseWeekDays(raw, path);
            case MONTHS -> raw == null ? Months.VALUES : parseMonths(raw, path);
            case DAILY_SCHEDULES -> raw == null ? List.of() : parseDailySchedules(raw, path);
        };
    }

    private static List<DailySchedule> parseDailySchedules(Object raw, String path) {
        List<DailySchedule> result = new ArrayList<>();
        int index = 0;
        for (Object item : asList(raw, path)) {
            String itemPath = path + "[" + index++ + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            Map<?, ?> map = asMap(item, itemPath);
            DailySchedule daily = newDefaultDailySchedule();
            Map<EStructuralFeature, Object> seen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                Key key = DAILY_KEYS.get(normalize(rawKey));
                if (key == null) {
                    List<String> names = new ArrayList<>();
                    for (Key k : new java.util.LinkedHashSet<>(DAILY_KEYS.values())) {
                        names.add(k.feature().getName());
                    }
                    throw invalid(itemPath + ": unknown key '" + rawKey + "'. Supported keys: " //$NON-NLS-1$ //$NON-NLS-2$
                            + String.join(", ", names)); //$NON-NLS-1$
                }
                if (seen.put(key.feature(), Boolean.TRUE) != null) {
                    throw invalid(itemPath + ": key '" + key.feature().getName() + "' is given twice"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                setFeature(daily, key.feature(), convert(key, entry.getValue(), itemPath + "." + rawKey)); //$NON-NLS-1$
            }
            result.add(daily);
        }
        return result;
    }

    private static List<WeekDays> parseWeekDays(Object raw, String path) {
        TreeSet<Integer> numbers = new TreeSet<>();
        for (Object item : asList(raw, path)) {
            WeekDays day = null;
            if (item instanceof Number || isInteger(item)) {
                int number = parseInt(item, path, Integer.MIN_VALUE);
                if (number >= 1 && number <= 7) {
                    day = allWeekDays().get(number - 1);
                }
            } else if (item != null) {
                day = WEEK_DAY_TOKENS.get(normalize(String.valueOf(item)));
            }
            if (day == null) {
                throw invalid(path + ": unknown week day '" + item //$NON-NLS-1$
                        + "' (use Mon..Sun, Monday..Sunday, Пн..Вс or 1C numbers 1..7, 1 = Monday)"); //$NON-NLS-1$
            }
            numbers.add(Integer.valueOf(allWeekDays().indexOf(day)));
        }
        List<WeekDays> result = new ArrayList<>();
        for (Integer number : numbers) {
            result.add(allWeekDays().get(number.intValue()));
        }
        return result;
    }

    private static List<Months> parseMonths(Object raw, String path) {
        TreeSet<Integer> numbers = new TreeSet<>();
        for (Object item : asList(raw, path)) {
            Months month = null;
            if (item instanceof Number || isInteger(item)) {
                int number = parseInt(item, path, Integer.MIN_VALUE);
                if (number >= 1 && number <= 12) {
                    month = Months.get(number - 1);
                }
            } else if (item != null) {
                month = MONTH_TOKENS.get(normalize(String.valueOf(item)));
            }
            if (month == null) {
                throw invalid(path + ": unknown month '" + item //$NON-NLS-1$
                        + "' (use Jan..Dec, January..December, Январь..Декабрь or numbers 1..12)"); //$NON-NLS-1$
            }
            numbers.add(Integer.valueOf(month.getValue()));
        }
        List<Months> result = new ArrayList<>();
        for (Integer number : numbers) {
            result.add(Months.get(number.intValue()));
        }
        return result;
    }

    private static Date parseDate(Object raw, String path) {
        String text = String.valueOf(raw).trim();
        Matcher matcher = DATE.matcher(text);
        if (!matcher.matches()) {
            throw invalid(path + ": date must be YYYY-MM-DD, got '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            // ScheduleDateTimeUtil parses leniently (2026-13-01 becomes 2027-01-01), so the value
            // is accepted only when it survives a round trip unchanged.
            Date date = ScheduleDateTimeUtil.dateFromString(text);
            if (!text.equals(ScheduleDateTimeUtil.dateToString(date))) {
                throw invalid(path + ": no such date '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return date;
        } catch (ParseException e) {
            throw invalid(path + ": date must be YYYY-MM-DD, got '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static Date parseTime(Object raw, String path) {
        String text = String.valueOf(raw).trim();
        Matcher matcher = TIME.matcher(text);
        if (!matcher.matches()) {
            throw invalid(path + ": time must be HH:MM:SS or HH:MM, got '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int hours = Integer.parseInt(matcher.group(1));
        int minutes = Integer.parseInt(matcher.group(2));
        int seconds = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hours > 23 || minutes > 59 || seconds > 59) {
            throw invalid(path + ": no such time '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String normalized = String.format(Locale.ROOT, "%02d:%02d:%02d", //$NON-NLS-1$
                Integer.valueOf(hours), Integer.valueOf(minutes), Integer.valueOf(seconds));
        try {
            return ScheduleDateTimeUtil.timeFromString(normalized);
        } catch (ParseException e) {
            throw invalid(path + ": time must be HH:MM:SS or HH:MM, got '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean isInteger(Object raw) {
        return raw instanceof String text && text.trim().matches("[+-]?\\d+"); //$NON-NLS-1$
    }

    private static int parseInt(Object raw, String path, int min) {
        long value;
        if (raw instanceof Number number) {
            double asDouble = number.doubleValue();
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.rint(asDouble)) {
                throw invalid(path + ": integer expected, got " + raw); //$NON-NLS-1$
            }
            value = (long) asDouble;
        } else if (isInteger(raw)) {
            value = Long.parseLong(((String) raw).trim());
        } else {
            throw invalid(path + ": integer expected, got '" + raw + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value < min || value > Integer.MAX_VALUE) {
            throw invalid(path + ": value " + value + " is out of range" //$NON-NLS-1$ //$NON-NLS-2$
                    + (min == Integer.MIN_VALUE ? "" : " (minimum " + min + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return (int) value;
    }

    private static Map<?, ?> asMap(Object raw, String path) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        throw invalid(path + " must be an object, e.g. {\"repeatPeriodInDay\": 300, \"daysRepeatPeriod\": 1}"); //$NON-NLS-1$
    }

    private static List<?> asList(Object raw, String path) {
        if (raw instanceof List<?> list) {
            return list;
        }
        throw invalid(path + " must be an array"); //$NON-NLS-1$
    }

    private static MetadataOperationException invalid(String message) {
        return new MetadataOperationException(MetadataOperationCode.INVALID_PROPERTY_VALUE, message, false);
    }

    private static String normalize(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace('ё', 'е').replace("_", "").replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void key(Map<String, Key> keys, EStructuralFeature feature, Kind kind, String... aliases) {
        Key key = new Key(feature, kind);
        keys.put(normalize(feature.getName()), key);
        for (String alias : aliases) {
            keys.put(normalize(alias), key);
        }
    }

    private static void weekDay(WeekDays day, String... aliases) {
        WEEK_DAY_TOKENS.put(normalize(day.getLiteral()), day);
        WEEK_DAY_TOKENS.put(normalize(day.getName()), day);
        for (String alias : aliases) {
            WEEK_DAY_TOKENS.put(normalize(alias), day);
        }
    }

    private static void month(Months month, String... aliases) {
        MONTH_TOKENS.put(normalize(month.getLiteral()), month);
        MONTH_TOKENS.put(normalize(month.getName()), month);
        for (String alias : aliases) {
            MONTH_TOKENS.put(normalize(alias), month);
        }
    }
}
