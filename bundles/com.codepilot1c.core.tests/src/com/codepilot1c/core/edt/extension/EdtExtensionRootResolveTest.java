package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Парный контроль резолва корня конфигурации в extension_manage set_state.
 *
 * До фикса корень не резолвился вовсе: findSourceObject обходит только
 * containment-коллекции конфигурации, а корень — не элемент собственных
 * коллекций, поэтому set_state по корневым свойствам расширения (usePurposes
 * и т. п.) отвечал METADATA_NOT_FOUND (живое репро 2026-08-24 на проекте
 * ИИКона_разработки). Каждая проверка ниже — парой «обязан найти» /
 * «обязан промолчать», чтобы резолвер не начал хватать чужие ссылки.
 */
public class EdtExtensionRootResolveTest {

    private Configuration config(String name) {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName(name);
        return configuration;
    }

    @Test
    public void bareKindResolvesRoot() {
        Configuration configuration = config("Демо");
        assertSame(configuration,
                ExtensionRootResolver.resolve(configuration, "Configuration"));
    }

    @Test
    public void kindWithNameResolvesCaseInsensitive() {
        Configuration configuration = config("БиблиотекаСтандартныхПодсистемДемо");
        assertSame(configuration, ExtensionRootResolver.resolve(
                configuration, "configuration.библиотекаСтандартныхПодсистемДемо"));
    }

    @Test
    public void foreignNameIsRejected() {
        assertNull(ExtensionRootResolver.resolve(
                config("Демо"), "Configuration.Чужая"));
    }

    @Test
    public void otherKindIsRejected() {
        assertNull(ExtensionRootResolver.resolve(config("Демо"), "Catalog.Демо"));
        assertNull(ExtensionRootResolver.resolve(config("Демо"), "Демо"));
    }

    @Test
    public void blankAndNullAreRejected() {
        Configuration configuration = config("Демо");
        assertNull(ExtensionRootResolver.resolve(configuration, null));
        assertNull(ExtensionRootResolver.resolve(configuration, "   "));
        assertNull(ExtensionRootResolver.resolve(configuration, "Configuration."));
        assertNull(ExtensionRootResolver.resolve(null, "Configuration"));
    }

    @Test
    public void nullConfigurationNameMatchesOnlyBareKind() {
        Configuration configuration = config(null);
        assertSame(configuration,
                ExtensionRootResolver.resolve(configuration, "Configuration"));
        assertNull(ExtensionRootResolver.resolve(configuration, "Configuration.Демо"));
    }
}
