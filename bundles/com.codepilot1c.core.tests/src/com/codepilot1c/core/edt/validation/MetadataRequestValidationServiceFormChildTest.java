package com.codepilot1c.core.edt.validation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.tools.metadata.AddMetadataChildTool;

/**
 * Опции формы {@code add_metadata_child} в токене.
 *
 * <p>Дефект: {@code add_metadata_child} с {@code child_kind:Form} публикует {@code form_usage}, {@code managed},
 * {@code set_as_default}, {@code wait_ms} параметрами верхнего уровня и сворачивает их в {@code properties}, а шаг
 * validate сворачивал только {@code template_type} и поля HTTP-сервиса. Мутация применяет payload из токена, и роль
 * терялась: в логе {@code Validated payload from token: {child_kind=FORM, name=ФормаЗаписи, …}} без
 * {@code form_usage} — форма записи регистра сведений вышла формой списка.</p>
 *
 * <p>Инвариант — payload токена равен тому, что тул нормализует из тех же параметров: сверяются настоящий
 * {@code normalizePayload} шага validate и настоящий свёртыватель тула {@code mergeFormOptions} (рефлексией, чтобы
 * один бинарник шёл и против сборки до фикса).</p>
 */
public class MetadataRequestValidationServiceFormChildTest {

    @Test
    public void topLevelFormOptionsReachTheTokenBoundPayload() {
        Map<String, Object> properties = properties(normalize(formRequest()));
        assertEquals("OBJECT", properties.get("form_usage")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, properties.get("managed")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, properties.get("set_as_default")); //$NON-NLS-1$
        assertEquals(Double.valueOf(20000.0), properties.get("wait_ms")); //$NON-NLS-1$
    }

    @Test
    public void tokenPayloadEqualsWhatTheToolAppliesForAFormChild() throws Exception {
        Map<String, Object> request = formRequest();
        assertEquals(toolPayload(request), normalize(request));
    }

    @Test
    public void tokenPayloadEqualsWhatTheToolAppliesWhenPropertiesAlsoCarryTheRole() throws Exception {
        // Роль и в properties, и верхним уровнем: побеждает одна и та же сторона — в токене и в туле.
        Map<String, Object> request = formRequest();
        request.put("properties", new LinkedHashMap<>(Map.of("form_usage", "LIST"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(toolPayload(request), normalize(request));
    }

    @Test
    public void formOptionsDoNotLeakIntoOtherChildren() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("parent_fqn", "InformationRegister.Настройки"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("child_kind", "Dimension"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("name", "Клиент"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("properties", new LinkedHashMap<>(Map.of("type", "String(10)"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        request.put("managed", Boolean.TRUE); //$NON-NLS-1$
        Map<String, Object> normalized = normalize(request);
        assertFalse(String.valueOf(normalized), properties(normalized).containsKey("managed")); //$NON-NLS-1$
        assertEquals(toolPayload(request), normalized);
    }

    // --- обвязка -------------------------------------------------------------

    /** Вызов формы записи параметрами верхнего уровня, как их публикует схема тула. */
    private static Map<String, Object> formRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("project", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("parent_fqn", "InformationRegister.Настройки"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("child_kind", "Form"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("name", "ФормаЗаписи"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("synonym", "Настройка"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("form_usage", "OBJECT"); //$NON-NLS-1$ //$NON-NLS-2$
        request.put("managed", Boolean.TRUE); //$NON-NLS-1$
        request.put("set_as_default", Boolean.TRUE); //$NON-NLS-1$
        request.put("wait_ms", Double.valueOf(20000.0)); //$NON-NLS-1$
        return request;
    }

    private static Map<String, Object> normalize(Map<String, Object> payload) {
        return new MetadataRequestValidationService().normalizePayload(
                new ValidationRequest("P", ValidationOperation.ADD_METADATA_CHILD, payload), //$NON-NLS-1$
                new ArrayList<>());
    }

    /** То, что тул передаёт в сверку с токеном: его собственная свёртка и та же нормализация. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolPayload(Map<String, Object> parameters) throws Exception {
        Method merge = AddMetadataChildTool.class.getDeclaredMethod(
                "mergeFormOptions", Map.class, Map.class, String.class); //$NON-NLS-1$
        merge.setAccessible(true);
        Object base = parameters.get("properties"); //$NON-NLS-1$
        Map<String, Object> merged;
        try {
            merged = (Map<String, Object>) merge.invoke(new AddMetadataChildTool(),
                    base instanceof Map ? base : Map.of(), parameters, parameters.get("child_kind")); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
        return new MetadataRequestValidationService().normalizeAddChildPayload(
                (String) parameters.get("project"), //$NON-NLS-1$
                (String) parameters.get("parent_fqn"), //$NON-NLS-1$
                (String) parameters.get("child_kind"), //$NON-NLS-1$
                (String) parameters.get("name"), //$NON-NLS-1$
                (String) parameters.get("synonym"), //$NON-NLS-1$
                (String) parameters.get("comment"), //$NON-NLS-1$
                merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> normalized) {
        Object value = normalized.get("properties"); //$NON-NLS-1$
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
