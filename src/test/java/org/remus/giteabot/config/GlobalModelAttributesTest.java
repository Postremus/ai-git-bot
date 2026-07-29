package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalModelAttributesTest {

    @Test
    void appVersion_withoutBuildProperties_fallsBackToDev() {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.empty());

        GlobalModelAttributes attributes = new GlobalModelAttributes(provider);

        assertEquals("dev", attributes.appVersion());
    }

    @Test
    void appVersion_withBuildProperties_returnsBuildVersion() {
        Properties entries = new Properties();
        entries.put("version", "1.19.0");
        BuildProperties buildProperties = new BuildProperties(entries);
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> java.util.stream.Stream.of(buildProperties));

        assertTrue(new GlobalModelAttributes(provider).appVersion().contains("1."));

    }
}
