package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.schedule.model.Schedule;
import com._1c.g5.v8.dt.schedule.model.ScheduleFactory;
import com._1c.g5.v8.dt.schedule.model.SchedulePackage;
import com._1c.g5.v8.dt.schedule.model.WeekDays;

/**
 * Расписание регламентного задания через {@code update_metadata changes.set.schedule} и
 * {@code create_metadata properties.schedule}.
 *
 * <p>Дефект: {@code update_metadata {target_fqn:"ScheduledJob.X", changes:{set:{schedule:{repeatPeriodInDay:300,
 * daysRepeatPeriod:1}}}}} отвечал {@code [INVALID_METADATA_CHANGE] Field is read-only: schedule}. В модели EDT
 * {@code ScheduledJob.schedule} — {@code @ExternalProperty refers transient Schedule}: расписание живёт отдельным
 * верхним объектом BM (файл {@code Schedule.schedule}), а общий сеттер отбивает любую transient-фичу. Обойти через
 * {@code write_file} нельзя: новые не-.md файлы он не создаёт. Воспроизведено на EDT 2025.2.3.</p>
 *
 * <p>Тест бьёт рефлексией по настоящим {@code setFeatureValue}, {@code unsetFeatureValue} и
 * {@code collectSupportedTopLevelProperties} (задание и расписание — из фабрик EMF) и не ссылается на классы фикса:
 * один бинарник идёт против сборки до фикса (обязан покраснеть поимённо на симптоме) и после. Эталоны — дословные
 * файлы {@code Schedule.schedule} из рабочих проектов EDT, загруженные EMF: сравнение {@link EcoreUtil#equals}
 * ловит и значения, и порядок дней недели. Ветку «расписания ещё нет → attachTopObject» здесь не проверить (нужен
 * движок BM) — её закрывает живая проверка вызовом тула. Полный прогон с саботажем —
 * {@code scripts/run-scheduled-job-schedule-eval.sh}.</p>
 */
public class EdtMetadataServiceScheduledJobScheduleTest {

    private static final MdClassFactory MD = MdClassFactory.eINSTANCE;

    /** ОтправкаSMS: каждый день раз в 300 секунд — ровно запрос, на котором воспроизведён дефект. */
    private static final String EVERY_300_SECONDS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schedule:Schedule xmlns:schedule="http://g5.1c.ru/v8/dt/schedule" beginDate="0001-01-01" endDate="0001-01-01" daysRepeatPeriod="1" beginTime="00:00:00" endTime="00:00:00" completionTime="00:00:00" repeatPeriodInDay="300" weeksPeriod="1">
              <weekDays>Mon</weekDays>
              <weekDays>Tue</weekDays>
              <weekDays>Wed</weekDays>
              <weekDays>Thu</weekDays>
              <weekDays>Fri</weekDays>
              <weekDays>Sat</weekDays>
              <weekDays>Sun</weekDays>
              <months>Jan</months>
              <months>Feb</months>
              <months>Mar</months>
              <months>Apr</months>
              <months>May</months>
              <months>Jun</months>
              <months>Jul</months>
              <months>Aug</months>
              <months>Sep</months>
              <months>Oct</months>
              <months>Nov</months>
              <months>Dec</months>
            </schedule:Schedule>
            """; //$NON-NLS-1$

    /** ОбновлениеАгрегатов: детальные расписания дня. */
    private static final String DAILY_SCHEDULES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schedule:Schedule xmlns:schedule="http://g5.1c.ru/v8/dt/schedule" beginDate="0001-01-01" endDate="0001-01-01" daysRepeatPeriod="1" beginTime="01:00:00" endTime="00:00:00" completionTime="00:00:00" weeksPeriod="1">
              <weekDays>Mon</weekDays>
              <weekDays>Tue</weekDays>
              <weekDays>Wed</weekDays>
              <weekDays>Thu</weekDays>
              <weekDays>Fri</weekDays>
              <weekDays>Sat</weekDays>
              <weekDays>Sun</weekDays>
              <months>Jan</months>
              <months>Feb</months>
              <months>Mar</months>
              <months>Apr</months>
              <months>May</months>
              <months>Jun</months>
              <months>Jul</months>
              <months>Aug</months>
              <months>Sep</months>
              <months>Oct</months>
              <months>Nov</months>
              <months>Dec</months>
              <dailySchedules beginTime="01:00:00" endTime="00:00:00" completionTime="00:00:00"/>
              <dailySchedules beginTime="14:00:00" endTime="00:00:00" completionTime="00:00:00"/>
            </schedule:Schedule>
            """; //$NON-NLS-1$

    /** ОчисткаНенужныхФайлов: выходные, окно 01:00–07:00. */
    private static final String WEEKENDS_WINDOW = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schedule:Schedule xmlns:schedule="http://g5.1c.ru/v8/dt/schedule" beginDate="0001-01-01" endDate="0001-01-01" daysRepeatPeriod="1" beginTime="01:00:00" endTime="07:00:00" completionTime="00:00:00" weeksPeriod="1">
              <weekDays>Sat</weekDays>
              <weekDays>Sun</weekDays>
              <months>Jan</months>
              <months>Feb</months>
              <months>Mar</months>
              <months>Apr</months>
              <months>May</months>
              <months>Jun</months>
              <months>Jul</months>
              <months>Aug</months>
              <months>Sep</months>
              <months>Oct</months>
              <months>Nov</months>
              <months>Dec</months>
            </schedule:Schedule>
            """; //$NON-NLS-1$

    /** РегламентнаяОбработкаОчередиЗадач (БТС): пустые списки и weeksPeriod=0, как пришло из платформы. */
    private static final String EMPTY_LISTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schedule:Schedule xmlns:schedule="http://g5.1c.ru/v8/dt/schedule" beginDate="0001-01-01" endDate="0001-01-01" beginTime="00:00:00" endTime="00:00:00" completionTime="00:00:00"/>
            """; //$NON-NLS-1$

    // --- сам симптом: update_metadata по заданию, у которого расписание есть ---------------

    @Test
    public void setScheduleEvery300SecondsMatchesPlatformFile() throws Exception {
        ScheduledJob job = jobWithSchedule(load(DAILY_SCHEDULES));
        Schedule before = job.getSchedule();
        setFeature(job, "schedule", map("repeatPeriodInDay", 300, "daysRepeatPeriod", 1)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSame("существующее расписание переписано на месте, не заменено", before, job.getSchedule()); //$NON-NLS-1$
        assertScheduleEquals(EVERY_300_SECONDS, job.getSchedule());
    }

    @Test
    public void numbersFromJsonAsDoubleAreAccepted() throws Exception {
        // Gson отдаёт числа JSON как Double: 300.0 обязано стать 300, а не отказом.
        ScheduledJob job = jobWithSchedule(newSchedule());
        setFeature(job, "schedule", map("repeatPeriodInDay", Double.valueOf(300), "daysRepeatPeriod", Double.valueOf(1))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertScheduleEquals(EVERY_300_SECONDS, job.getSchedule());
    }

    @Test
    public void platformPropertyNamesAreAccepted() throws Exception {
        ScheduledJob job = jobWithSchedule(newSchedule());
        setFeature(job, "Schedule", map("ПериодПовтораВТечениеДня", 300, "ПериодПовтораДней", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertScheduleEquals(EVERY_300_SECONDS, job.getSchedule());
    }

    @Test
    public void dailySchedulesMatchPlatformFile() throws Exception {
        ScheduledJob job = jobWithSchedule(newSchedule());
        setFeature(job, "schedule", map( //$NON-NLS-1$
                "daysRepeatPeriod", 1, //$NON-NLS-1$
                "beginTime", "01:00:00", //$NON-NLS-1$ //$NON-NLS-2$
                "dailySchedules", List.of(map("beginTime", "01:00"), map("ВремяНачала", "14:00:00")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertScheduleEquals(DAILY_SCHEDULES, job.getSchedule());
    }

    @Test
    public void weekDaysAreStoredInPlatformOrder() throws Exception {
        // Вс и 6 (суббота по нумерации 1С) — на выходе Sat, Sun в порядке платформы, без дублей.
        ScheduledJob job = jobWithSchedule(newSchedule());
        setFeature(job, "schedule", map( //$NON-NLS-1$
                "daysRepeatPeriod", 1, //$NON-NLS-1$
                "beginTime", "01:00:00", //$NON-NLS-1$ //$NON-NLS-2$
                "endTime", "07:00", //$NON-NLS-1$ //$NON-NLS-2$
                "weekDays", List.of("Вс", 6, "sunday"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertScheduleEquals(WEEKENDS_WINDOW, job.getSchedule());
    }

    @Test
    public void emptyListsAndZeroWeeksPeriodAreReproducible() throws Exception {
        ScheduledJob job = jobWithSchedule(newSchedule());
        setFeature(job, "schedule", map("weeksPeriod", 0, "weekDays", List.of(), "months", List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertScheduleEquals(EMPTY_LISTS, job.getSchedule());
    }

    @Test
    public void setReplacesTheWholeSchedule() throws Exception {
        // «Замена», а не слияние: окно и дни недели прежнего расписания не переживают set.
        ScheduledJob job = jobWithSchedule(load(WEEKENDS_WINDOW));
        setFeature(job, "schedule", map("repeatPeriodInDay", 300, "daysRepeatPeriod", 1)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertScheduleEquals(EVERY_300_SECONDS, job.getSchedule());
    }

    // --- отказы: ничего не угадываем, расписание не трогаем -------------------------------

    @Test
    public void refusedValueLeavesScheduleUntouched() throws Exception {
        ScheduledJob job = jobWithSchedule(load(WEEKENDS_WINDOW));
        assertRefused(job, map("repeatPeriodInDay", 300, "weekDays", List.of("Mon", "Funday")), "Funday"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertScheduleEquals(WEEKENDS_WINDOW, job.getSchedule());
    }

    @Test
    public void malformedValuesAreRefused() throws Exception {
        ScheduledJob job = jobWithSchedule(newSchedule());
        // EDT разбирает время и дату нестрого: 25:00:00 стало бы 01:00:00, 2026-13-01 — 2027-01-01.
        assertRefused(job, map("beginTime", "25:00:00"), "25:00:00"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("beginDate", "2026-13-01"), "2026-13-01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("endDate", "2026-02-30"), "2026-02-30"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("endDate", "30.01.2026"), "30.01.2026"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("repeatPeriodInDay", -1), "repeatPeriodInDay"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, map("repeatPeriodInDay", 1.5), "repeatPeriodInDay"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, map("repeatPeriodInDay", "часто"), "repeatPeriodInDay"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("weekDays", List.of(0)), "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, map("weekDays", List.of(8)), "8"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, map("weekDays", "Mon"), "array"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(job, map("months", List.of(13)), "13"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, map("dailySchedules", List.of(map("repeatEvery", 5))), "repeatEvery"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused(job, map("repeatEvery", 300), "repeatEvery"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(job, "каждые 5 минут", "object"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void newScheduleOnDetachedJobFailsClosed() throws Exception {
        // Расписания нет, а задание не в транзакции BM: отказ, а не привязка «висящего» объекта.
        ScheduledJob job = MD.createScheduledJob();
        job.setName("Зонд"); //$NON-NLS-1$
        MetadataOperationException error = invokeExpectingError(
                () -> setFeature(job, "schedule", map("repeatPeriodInDay", 300))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MetadataOperationCode.EDT_TRANSACTION_FAILED, error.getCode());
        assertNull(job.getSchedule());
    }

    @Test
    public void unresolvedScheduleProxyIsTreatedAsMissing() throws Exception {
        // Живая проба: у задания, загруженного без файла Schedule.schedule, getSchedule() отдаёт не null, а
        // неразрешённый прокси на ожидаемый внешний объект. Правка прокси ничего не сохраняет — update отвечал успехом,
        // а файла на диске не было. Прокси обязан вести в ветку создания (здесь вне BM — отказ, прокси не тронут).
        Schedule proxy = ScheduleFactory.eINSTANCE.createSchedule();
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(
                URI.createURI("bm://Project/ScheduledJob.Зонд.Schedule#/")); //$NON-NLS-1$
        ScheduledJob job = MD.createScheduledJob();
        job.setName("Зонд"); //$NON-NLS-1$
        job.eSet(com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.SCHEDULED_JOB__SCHEDULE, proxy);
        MetadataOperationException error = invokeExpectingError(
                () -> setFeature(job, "schedule", map("repeatPeriodInDay", 300))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(error.getMessage(), MetadataOperationCode.EDT_TRANSACTION_FAILED, error.getCode());
        assertEquals("прокси не правится", 0, proxy.getRepeatPeriodInDay()); //$NON-NLS-1$
    }

    @Test
    public void unsetScheduleExplainsTheAlternative() throws Exception {
        ScheduledJob job = jobWithSchedule(newSchedule());
        MetadataOperationException error = invokeExpectingError(() -> unsetFeature(job, "schedule")); //$NON-NLS-1$
        assertTrue(error.getMessage(), error.getMessage().contains("use=false")); //$NON-NLS-1$
        assertNotNull(job.getSchedule());
    }

    @Test
    public void createMetadataListsScheduleAsSupportedProperty() throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("collectSupportedTopLevelProperties", MdObject.class); //$NON-NLS-1$
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) method.invoke(new EdtMetadataService(), MD.createScheduledJob());
        assertTrue(names.toString(), names.contains("schedule")); //$NON-NLS-1$
    }

    // --- прежнее поведение не задето ---------------------------------------------------------

    @Test
    public void otherScheduledJobFieldsStillUseGenericSetter() throws Exception {
        ScheduledJob job = jobWithSchedule(load(WEEKENDS_WINDOW));
        setFeature(job, "use", Boolean.TRUE); //$NON-NLS-1$
        setFeature(job, "methodName", "ОбщийМодуль.Метод"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(job.isUse());
        assertEquals("CommonModule.ОбщийМодуль.Метод", job.getMethodName()); //$NON-NLS-1$
        assertScheduleEquals(WEEKENDS_WINDOW, job.getSchedule());
    }

    @Test
    public void transientFieldsOfOtherObjectsStayReadOnly() throws Exception {
        // Исключение сделано только для ScheduledJob.schedule: suppressObject — тоже transient, и он по-прежнему отказ.
        ScheduledJob job = jobWithSchedule(newSchedule());
        MetadataOperationException error = invokeExpectingError(
                () -> setFeature(job, "suppressObject", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error.getMessage(), error.getMessage().contains("read-only")); //$NON-NLS-1$
    }

    // --- обвязка -------------------------------------------------------------------------------

    private interface Call {
        void run() throws Exception;
    }

    private static ScheduledJob jobWithSchedule(Schedule schedule) {
        ScheduledJob job = MD.createScheduledJob();
        job.setName("Зонд"); //$NON-NLS-1$
        job.setSchedule(schedule);
        return job;
    }

    private static Schedule newSchedule() {
        Schedule schedule = ScheduleFactory.eINSTANCE.createSchedule();
        schedule.getWeekDays().add(WeekDays.MONDAY);
        return schedule;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static Schedule load(String xml) throws Exception {
        ResourceSet resourceSet = new ResourceSetImpl();
        resourceSet.getPackageRegistry().put(SchedulePackage.eNS_URI, SchedulePackage.eINSTANCE);
        Resource resource = new XMIResourceImpl(URI.createURI("memory:/Schedule.schedule")); //$NON-NLS-1$
        resourceSet.getResources().add(resource);
        resource.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Map.of());
        EObject root = resource.getContents().get(0);
        Schedule schedule = (Schedule) root;
        // Отвязать от ресурса: задание держит расписание transient-ссылкой, ресурс тесту не нужен.
        resource.getContents().clear();
        return schedule;
    }

    private static void assertScheduleEquals(String expectedXml, Schedule actual) throws Exception {
        Schedule expected = load(expectedXml);
        if (!EcoreUtil.equals(expected, actual)) {
            fail("расписание не совпало с файлом платформы:\n  ожидалось " + describe(expected) //$NON-NLS-1$
                    + "\n  получено   " + describe(actual)); //$NON-NLS-1$
        }
    }

    private static String describe(Schedule schedule) {
        if (schedule == null) {
            return "null"; //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder();
        for (var feature : schedule.eClass().getEAllStructuralFeatures()) {
            text.append(feature.getName()).append('=').append(schedule.eGet(feature)).append(' ');
        }
        return text.toString().trim();
    }

    private static void setFeature(ScheduledJob job, String field, Object value) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("setFeatureValue", Configuration.class, //$NON-NLS-1$
                MdObject.class, String.class, Object.class,
                com._1c.g5.v8.bm.core.IBmPlatformTransaction.class, Map.class, String.class);
        method.setAccessible(true);
        invoke(method, new EdtMetadataService(), null, job, field, value, null, Map.of(), null);
    }

    private static void unsetFeature(ScheduledJob job, String field) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("unsetFeatureValue", MdObject.class, String.class); //$NON-NLS-1$
        method.setAccessible(true);
        invoke(method, new EdtMetadataService(), job, field);
    }

    private static void invoke(Method method, Object target, Object... args) throws Exception {
        try {
            method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static MetadataOperationException invokeExpectingError(Call call) throws Exception {
        try {
            call.run();
        } catch (MetadataOperationException e) {
            return e;
        }
        fail("ожидался отказ MetadataOperationException"); //$NON-NLS-1$
        return null;
    }

    private static void assertRefused(ScheduledJob job, Object value, String mentioned) throws Exception {
        MetadataOperationException error = invokeExpectingError(() -> setFeature(job, "schedule", value)); //$NON-NLS-1$
        assertEquals(error.getMessage(), MetadataOperationCode.INVALID_PROPERTY_VALUE, error.getCode());
        assertTrue(error.getMessage(), error.getMessage().contains(mentioned));
    }
}
