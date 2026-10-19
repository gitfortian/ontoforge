package io.ontoforge.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDevelopmentDefaultsTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsOneGeneratedSigningKeyAndReusesIt() throws IOException {
        Path config = temporaryDirectory.resolve("config/application.properties");

        LocalDevelopmentDefaults.ensureCursorSecret(config);
        String first = property(config, "ontoforge.objectset.cursor-signing-secret");
        LocalDevelopmentDefaults.ensureCursorSecret(config);

        assertThat(first).hasSizeGreaterThanOrEqualTo(32);
        assertThat(property(config, "ontoforge.objectset.cursor-signing-secret")).isEqualTo(first);
    }

    @Test
    void appendsKeyWithoutReplacingExistingLocalProperties() throws IOException {
        Path config = temporaryDirectory.resolve("application.properties");
        Files.writeString(config, "spring.datasource.password=local-only\n");

        LocalDevelopmentDefaults.ensureCursorSecret(config);

        assertThat(property(config, "spring.datasource.password")).isEqualTo("local-only");
        assertThat(property(config, "ontoforge.objectset.cursor-signing-secret")).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void onlyEnablesAutomaticKeySetupForTheIdeaLocalProfile() {
        assertThat(LocalDevelopmentDefaults.ideaProfileActive(new String[] {"--spring.profiles.active=idea-local"})).isTrue();
        assertThat(LocalDevelopmentDefaults.ideaProfileActive(new String[] {"--spring.profiles.active=production"})).isFalse();
    }

    @Test
    void suppliesThePersistedKeyToSpringAsAHighPriorityProperty() throws IOException {
        Path config = temporaryDirectory.resolve("config/application.properties");
        String previous = System.getProperty("ontoforge.objectset.cursor-signing-secret");
        try {
            LocalDevelopmentDefaults.ensureForIdeaProfile(
                    new String[] {"--spring.profiles.active=idea-local"}, config);
            assertThat(System.getProperty("ontoforge.objectset.cursor-signing-secret"))
                    .isEqualTo(property(config, "ontoforge.objectset.cursor-signing-secret"));
        } finally {
            if (previous == null) System.clearProperty("ontoforge.objectset.cursor-signing-secret");
            else System.setProperty("ontoforge.objectset.cursor-signing-secret", previous);
        }
    }

    @Test
    void restrictsGeneratedConfigurationToTheCurrentWindowsUserWhenAclIsAvailable() throws IOException {
        Path config = temporaryDirectory.resolve("private/application.properties");
        LocalDevelopmentDefaults.ensureCursorSecret(config);

        AclFileAttributeView acl = Files.getFileAttributeView(config, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            assertThat(acl.getAcl()).hasSize(1);
            assertThat(acl.getAcl().getFirst().principal().getName())
                    .isEqualTo(config.getFileSystem().getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name")).getName());
        }
    }

    private static String property(Path file, String name) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties.getProperty(name);
    }
}
